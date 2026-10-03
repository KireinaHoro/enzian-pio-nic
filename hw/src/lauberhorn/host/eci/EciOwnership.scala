package lauberhorn.host.eci

import jsteward.blocks.misc.RegBlockAlloc
import jsteward.blocks.eci.EciIntcInterface
import lauberhorn.Global._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.regif.AccessType.{RC, RO, WO}
import spinal.lib.misc.plugin.FiberPlugin

/** Fixed physical-worker ownership and non-reused domain lifetimes.
  * Lifetime state survives application reset and driver reload. FPGA image
  * programming still requires a full coherence reset, not just driver reload.
  */
class EciOwnership extends FiberPlugin {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val ctrl = AxiLite4SlaveFactory(bus)
    ctrl.read(U(2, REG_WIDTH bits), alloc("abi", attr = RO,
      desc = "Cooperative ECI ABI version; must equal software version"))
    // A 20-bit saturating counter distinguishes exhausted from code zero.
    val allocate = alloc("allocateDomain", attr = RC, readSensitive = true,
      desc = "Allocate one non-reused 19-bit PA lifetime; 524288 means exhausted")
    ctrl.read(logic.nextDomain, allocate)
    logic.allocator.io.allocate := False
    ctrl.onRead(allocate) { logic.allocator.io.allocate := True }
    val code = UInt(EciAddressMap.domain_bits bits)
    ctrl.drive(code, alloc("domainCode", attr = WO,
      desc = "PA lifetime to bind to an unused scheduler slot"))
    val slot = UInt(log2Up(NUM_PROCS.get + 1) bits)
    slot := 0
    val bind = alloc("domainSlot", attr = WO,
      desc = "Commit immutable scheduler-slot to PA-lifetime binding")
    ctrl.write(slot, bind)
    ctrl.onWrite(bind) {
      assert(!logic.resetRequired, "domain binding after session fence")
      assert(slot =/= 0 && slot <= NUM_PROCS.get)
      assert(!logic.domainBound(slot), "domain slot reuse requires coherence reset")
      assert(code.resize(logic.nextDomain.getWidth) < logic.nextDomain,
        "unallocated PA lifetime")
      logic.domainCodes(slot) := code
      logic.domainBound(slot) := True
    }
    ctrl.read(logic.faults.io.failed, alloc("failedDomains", attr = RO,
      desc = "Sticky domain-slot faults; kill these domains and do not recycle"))
    ctrl.read(logic.resetRequired, alloc("resetRequired", attr = RO,
      desc = "Fatal lifecycle failure: full coherence reset required"))
    val failedSlot = UInt(log2Up(NUM_PROCS.get + 1) bits)
    failedSlot := 0
    logic.softwareFault.setIdle()
    val fail = alloc("failDomain", attr = WO,
      desc = "Fatal domain-slot failure; retain resources until coherence reset")
    ctrl.write(failedSlot, fail)
    ctrl.onWrite(fail) {
      assert(failedSlot =/= 0 && failedSlot <= NUM_PROCS.get && logic.domainBound(failedSlot))
      logic.softwareFault.valid := True
      logic.softwareFault.payload := logic.domainCodes(failedSlot)
    }
  }

  val logic = during setup new Area {
    val workers = NUM_WORKER_CORES.get
    require(workers >= 1 && workers <= EciAddressMap.max_workers)
    val appReset = Bool()
    appReset := ClockDomain.current.isResetActive
    // VIO probe_out5 can reset the application without resetting THX coherence.
    // FPGA initialization, rather than that reset input, initializes these regs.
    val allocator = EciDomainAllocator()
    allocator.io.appReset := appReset
    val nextDomain = allocator.io.next
    val resetRequired = allocator.io.resetRequired
    val domainCodes = Vec.fill(NUM_PROCS.get + 1)(Reg(UInt(EciAddressMap.domain_bits bits)) init 0)
    val domainBound = Vec.fill(NUM_PROCS.get + 1)(RegInit(False))
    val owners = Vec.fill(workers)(Reg(UInt(EciAddressMap.domain_bits bits)) init 0)
    val enabled = Reg(Bits(workers bits)) init 0
    val fence = Bits(workers bits)
    val install = Vec.fill(workers)(Flow(UInt(EciAddressMap.domain_bits bits)))
    val ingress = Seq.fill(2)(EciAxiIngress(host[EciInterfacePlugin].axiConfig, workers))
    val busy = Bits(workers bits)
    val faults = EciDomainFaults(NUM_PROCS.get + 1, 3)
    val softwareFault = Flow(UInt(EciAddressMap.domain_bits bits))
    val faultIrq = Stream(EciIntcInterface())
    val failedWorkers = Bits(workers bits)

    awaitBuild()

    faults.io.codes := domainCodes
    faults.io.bound := domainBound.asBits
    faults.io.violations.take(2).zip(ingress).foreach { case (fault, port) => fault << port.io.violation }
    faults.io.violations(2) << softwareFault
    faultIrq.valid := faults.io.interrupt.valid
    faults.io.interrupt.ready := faultIrq.ready
    faultIrq.cmd := 0
    faultIrq.intId := 9
    faultIrq.affLvl0 := 1
    faultIrq.affLvl1 := 0
    allocator.io.fatal := faults.io.failed.orR
    for (w <- 0 until workers) {
      // Reset owner bits are not an assignment. In particular, code zero is a
      // real first lifetime: its failure must not fence never-installed workers.
      failedWorkers(w) := enabled(w) && (1 to NUM_PROCS.get).map(s =>
        faults.io.failed(s) && domainBound(s) && owners(w) === domainCodes(s)).reduce(_ || _)
    }
    ingress.foreach { port =>
      port.io.owners := owners
      port.io.enabled := enabled
      port.io.fenced := fence | failedWorkers
    }
    busy := ingress.map(_.io.busy).reduce(_ | _)
    for (w <- 0 until workers) {
      when(install(w).valid) {
        assert(!busy(w), "owner changed with accepted AXI traffic outstanding")
        owners(w) := install(w).payload
        enabled(w) := True
      }
    }

    /** Only use for new protocol-local LCI creation while ownership is held.
      * Once offered, the command must retain this full address until LCIA. */
    def fullAddress(worker: Int, offset: Bits): Bits = {
      val base = EciAddressMap.domainBasis.zipWithIndex.foldLeft(
        B(EciAddressMap.workerBases(worker), offset.getWidth bits)) {
        case (value, (vector, bit)) => value ^ Mux(owners(worker)(bit),
          B(vector, offset.getWidth bits), B(0, offset.getWidth bits))
      }
      base | offset
    }
  }
}
