package lauberhorn.host.eci

import jsteward.blocks.misc.RegBlockAlloc
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
class EciFixedWorkerRouter extends FiberPlugin {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val ctrl = AxiLite4SlaveFactory(bus)
    ctrl.read(U(2, REG_WIDTH bits), alloc("abi", attr = RO,
      desc = "Fixed-worker cooperative ABI version; must equal software version"))
    // A 20-bit saturating counter distinguishes exhausted from code zero.
    val allocate = alloc("allocateDomain", attr = RC, readSensitive = true,
      desc = "Allocate one non-reused 19-bit PA lifetime; 524288 means exhausted")
    ctrl.read(logic.nextDomain, allocate)
    ctrl.onRead(allocate) {
      when(!logic.resetRequired && logic.nextDomain < FixedWorkerMap.domain_limit) {
        logic.nextDomain := logic.nextDomain + 1
      }
    }
    val code = UInt(FixedWorkerMap.domain_bits bits)
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
    ctrl.read(logic.resetRequired, alloc("resetRequired", attr = RO,
      desc = "Fatal lifecycle failure: full coherence reset required"))
    val fail = alloc("fenceSession", attr = WO,
      desc = "Sticky fail-closed session fence; no software clear")
    ctrl.onWrite(fail) { logic.resetRequired := True }
  }

  val logic = during setup new Area {
    val workers = NUM_WORKER_CORES.get
    require(workers >= 1 && workers <= FixedWorkerMap.max_workers)
    val appReset = ClockDomain.current.isResetActive
    // VIO probe_out5 can reset the application without resetting THX coherence.
    // FPGA initialization, rather than that reset input, initializes these regs.
    val persistent = new ClockingArea(ClockDomain.current.withBootReset()) {
      val nextDomain = Reg(UInt(20 bits)) init 0
      val resetRequired = RegInit(False)
      when(appReset && nextDomain =/= 0) { resetRequired := True }
    }
    val nextDomain = persistent.nextDomain
    val resetRequired = persistent.resetRequired
    val domainCodes = Vec.fill(NUM_PROCS.get + 1)(Reg(UInt(FixedWorkerMap.domain_bits bits)) init 0)
    val domainBound = Vec.fill(NUM_PROCS.get + 1)(RegInit(False))
    val owners = Vec.fill(workers)(Reg(UInt(FixedWorkerMap.domain_bits bits)) init 0)
    val enabled = Reg(Bits(workers bits)) init 0
    val fence = Bits(workers bits)
    val install = Vec.fill(workers)(Flow(UInt(FixedWorkerMap.domain_bits bits)))
    val ingress = Seq.fill(2)(FixedWorkerAxiIngress(host[EciInterfacePlugin].axiConfig, workers))
    val busy = Bits(workers bits)

    awaitBuild()

    ingress.foreach { port =>
      port.io.owners := owners
      port.io.enabled := enabled
      port.io.fenced := fence | B(workers bits, default -> resetRequired)
    }
    busy := ingress.map(_.io.busy).reduce(_ | _)
    for (w <- 0 until workers) {
      when(install(w).valid) {
        assert(!busy(w), "owner changed with accepted AXI traffic outstanding")
        assert(!resetRequired, "activation after session fence")
        owners(w) := install(w).payload
        enabled(w) := True
      }
    }

    /** Only use for new protocol-local LCI creation while ownership is held.
      * Once offered, the command must retain this full address until LCIA. */
    def fullAddress(worker: Int, offset: Bits): Bits = {
      val base = FixedWorkerMap.domainBasis.zipWithIndex.foldLeft(
        B(FixedWorkerMap.workerBases(worker), offset.getWidth bits)) {
        case (value, (vector, bit)) => value ^ Mux(owners(worker)(bit),
          B(vector, offset.getWidth bits), B(0, offset.getWidth bits))
      }
      base | offset
    }
  }
}
