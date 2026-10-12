package lauberhorn.host.eci

import jsteward.blocks.eci.EciIntcInterface
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.host.DeschedulingService
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.regif.AccessType.{RC, RO}

/** Cooperative handoff: idle reservations send a kick, ordinary reservations
  * deliver DESCHED through RX. Acceptance of DeschedInfo starts autonomous
  * cleanup; its response supplies the committed assignment. */
class EciDeschedulingControlPlugin(val coreID: Int) extends DeschedulingService {
  require(coreID > 0)
  withPrefix(s"worker_${coreID - 1}")

  val tp = during setup host[TracePlugin].makePort(s"desched_core$coreID", LauberhornTraceDma.NicHostInterfaceSlr)

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val ctrl = AxiLite4SlaveFactory(bus)
    val info = alloc("deschedInfo", attr = RC, readSensitive = true,
      desc = "Committed assignment; read acceptance relinquishes outgoing assignment",
      ty = s"""{
        | domain_code 19 "PA lifetime";
        | domain_slot ${log2Up(NUM_PROCS.get + 1)} "Scheduler slot";
        | rx_next 1 "Next RX parity";
        | tx_next 1 "Next TX parity";
        | _ ${64 - 21 - log2Up(NUM_PROCS.get + 1)} rsvd;
        |}""".stripMargin)
    ctrl.read(logic.snapshot, info)
    logic.accept := bus.ar.fire && ctrl.maskAddress(bus.ar.addr) === info
    logic.consumed := bus.r.fire && ctrl.readAddress() === info
    when(ctrl.readDataStage.valid && ctrl.readAddress() === info && !logic.response) {
      ctrl.readHalt()
    }
    ctrl.read(logic.idleNotification, alloc("idleNotification", attr = RO,
      desc = "Sticky idle reservation; consumed by one serialized DeschedInfo read"))
    ctrl.read(logic.active && !logic.idleNotification && !logic.relinquished,
      alloc("yieldRequested", attr = RO, desc = "Ordinary relinquishment is authorized; never poll for work"))
    ctrl.readAndWrite(logic.realCoreId, alloc("realCoreId",
      desc = "Linux CPU receiving idle-dispatch kicks"))
  }

  val requestPort = during setup Stream(DeschedReq())

  val logic = during setup new Area {
    val deschedReq = requestPort.get
    val ipiToIntc = Stream(EciIntcInterface())
    val realCoreId = Reg(UInt(log2Up(MAX_CORE_ID) bits)) init coreID
    val accept, consumed = Bool()
    val active = RegInit(False)
    val relinquished = RegInit(False)
    val response = RegInit(False)
    val idleNotification = RegInit(False)
    val kickPending = RegInit(False)
    val targetSlot = Reg(UInt(log2Up(NUM_PROCS.get + 1) bits)) init 0
    val targetCode = Reg(UInt(EciAddressMap.domain_bits bits)) init 0
    val snapshot = Reg(Bits(REG_WIDTH bits)) init 0

    awaitBuild()

    val ownership = host[EciOwnership].logic
    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)
    val worker = coreID - 1
    deschedReq.ready := False
    proto.deschedReq.valid := relinquished && !response
    proto.logic.yieldRequested := active && !idleNotification && !relinquished
    proto.logic.fatalRelease := ownership.failedWorkers(worker)
    proto.logic.externalQuiet := !ownership.busy(worker)
    ownership.fence(worker) := relinquished
    ownership.install(worker).setIdle()

    ipiToIntc.cmd := 0
    ipiToIntc.intId := 8
    ipiToIntc.affLvl0 := UIntToOh(realCoreId(3 downto 0))
    ipiToIntc.affLvl1 := realCoreId(realCoreId.getWidth - 1 downto 4).asBits.resized
    ipiToIntc.valid := kickPending
    when(ipiToIntc.fire) { kickPending := False }

    when(!active && deschedReq.valid && !ownership.failedWorkers(worker)) {
      val slot = host[Scheduler].logic.corePidMap(worker)
      assert(slot =/= 0 && ownership.domainBound(slot), "reservation without bound domain")
      assert(!deschedReq.kill, "legacy kill scheduling is unsupported")
      targetSlot := slot.resized
      targetCode := ownership.domainCodes(slot)
      active := True
      idleNotification := deschedReq.outOfIdle
      kickPending := deschedReq.outOfIdle
    }
    when(accept) {
      tp.trace("YieldAccepted", CoreID(B(coreID, CoreID.width bits)), DomainCode(targetCode.asBits),
        QueueIndex(targetSlot), DeschedOutOfIdle(idleNotification))
      assert(active && !relinquished && !response, "unauthorized DeschedInfo read")
      relinquished := True
      idleNotification := False
    }
    when(proto.deschedReq.fire) {
      tp.trace("ActivationCommitted", CoreID(B(coreID, CoreID.width bits)), DomainCode(targetCode.asBits),
        QueueIndex(targetSlot), RxNext(proto.logic.rxCurrClIdx), TxNext(proto.logic.txCurrClIdx))
      // Cleanup and all accepted AXI responses have completed. Parities are
      // sampled here, after final control invalidation, without software writes.
      snapshot := (proto.logic.txCurrClIdx.asBits ## proto.logic.rxCurrClIdx.asBits ##
        targetSlot.asBits ## targetCode.asBits).resized
      ownership.install(worker).valid := True
      ownership.install(worker).payload := targetCode
      deschedReq.ready := True
      response := True
    }
    when(consumed) {
      tp.trace("ActivationDelivered", CoreID(B(coreID, CoreID.width bits)), DomainCode(targetCode.asBits),
        QueueIndex(targetSlot))
      assert(response)
      active := False
      relinquished := False
      response := False
    }
  }

  override def deschedReq: Stream[DeschedReq] = requestPort.get
}
