package lauberhorn.host.eci

import jsteward.blocks.eci.{EciCmdDefs, EciIntcInterface}
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global._
import lauberhorn._
import lauberhorn.host.DatapathPlugin
import lauberhorn.host.eci.EciDecoupledRxTxProtocol.emittedMackerel
import lauberhorn.net.invalidTraceId
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType.{RC, RO, RW}
import spinal.lib.fsm._

import scala.language.postfixOps
import scala.math.BigInt.int2bigInt

class EciDecoupledRxTxProtocol(coreID: Int) extends DatapathPlugin(coreID) with EciPioProtocol {
  val isBypass = coreID == 0
  lazy val debug = host[DebugPlugin]

  // potentially RX and TX can run at the same time (for bypass)
  val rxTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx", LauberhornTraceDma.NicHostInterfaceSlr)
  val txTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx", LauberhornTraceDma.NicHostInterfaceSlr)
  val rxLciTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx_lcl_lci", LauberhornTraceDma.NicHostInterfaceSlr)
  val rxLciaTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx_lcl_lcia", LauberhornTraceDma.NicHostInterfaceSlr)
  val txLciTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx_lcl_lci", LauberhornTraceDma.NicHostInterfaceSlr)
  val txLciaTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx_lcl_lcia", LauberhornTraceDma.NicHostInterfaceSlr)
  val irqTp = if (isBypass) during setup host[TracePlugin].makePort("datapath_bypass_irq", LauberhornTraceDma.NicHostInterfaceSlr) else null

  if (isBypass) {
    withPrefix("proto_bypass")
  } else {
    withPrefix(s"proto_worker_${coreID - 1}")
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    // TODO: emit constants type for FSM state value
    busCtrl.read(logic.rxFsm.stateReg, alloc("stat", subName = "rxFsmState", attr = RO,
      desc = "state of the RX state machine (raw value)"))
    busCtrl.read(logic.txFsm.stateReg, alloc("stat", subName = "txFsmState", attr = RO,
      desc = "state of the TX state machine (raw value)"))

    busCtrl.read(logic.txCurrClIdx, alloc("ctrl", subName = "txCurrClIdx", attr = RO,
      desc = "parity (next CL to write) of the TX state machine"))
    busCtrl.read(logic.rxCurrClIdx, alloc("ctrl", subName = "rxCurrClIdx", attr = RO,
      desc = "parity (next CL to read) of the RX state machine"))

    debug.postDebug(s"core${coreID}_rxFsm_state", logic.rxFsm.stateReg)
    debug.postDebug(s"core${coreID}_txFsm_state", logic.txFsm.stateReg)
    debug.postDebug(s"core${coreID}_rxClIdx", logic.rxCurrClIdx)
    debug.postDebug(s"core${coreID}_txClIdx", logic.txCurrClIdx)

    if (isBypass) {
      busCtrl.read(logic.bypassIrqArea.irqFsm.stateReg,
        alloc("stat", subName = "irqFsmState", attr = RO, desc = "state of the bypass IRQ state machine (raw value)"))
      busCtrl.read(logic.bypassIrqArea.issued.value,
        alloc("stat", subName = "irqsIssued", attr = RO, desc = "number of bypass IRQs issued"))

      debug.postDebug(s"core${coreID}_irqFsm_state", logic.bypassIrqArea.irqFsm.stateReg)
    }

    busCtrl.read(logic.numRetired.value,
      alloc("stat", subName = "numRetired", attr = RO, desc = "number of retired requests"))
    busCtrl.read(logic.numReq.value,
      alloc("stat", subName = "numReq", attr = RO, desc = "number of requests observed"))
    busCtrl.read(logic.numNack.value,
      alloc("stat", subName = "numNack", attr = RO, desc = "number of NACKs observed"))
    busCtrl.read(logic.numPreempted.value,
      alloc("stat", subName = "numPreempted", attr = RO, desc = "times this worker has been preempted"))
  }

  // called for driving the non-existent preemption control for core#0
  def driveBypassIrqCtrl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    // generate IRQ enable reg for bypass
    val irqEnAddr = alloc("irqEn", desc = "Enable IRQ to this core")
    busCtrl.driveAndRead(logic.irqEn, irqEnAddr) init False

  }

  lazy val overflowCountWidth = log2Up(numOverflowCls)

  // two control half CLs, one extra first word half CL, one MTU
  // CL#0: [ control | first data (aliased) ]
  // CL#1: [ control | first data (aliased) ]
  // CL#2...: [ rest data ]
  lazy val sizePerMtuPerDirection = (512 / 8) * 3 + ROUNDED_MTU
  lazy val numOverflowCls = (sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt

  // map at aligned address to eliminate long comb paths
  val txOffset = 0x1000
  val sizePerCore = 2 * txOffset

  private def packetSizeToNumOverflowCls(s: UInt): UInt = {
    val clSize = EciCmdDefs.ECI_CL_SIZE_BYTES
    ((s <= 64) ? U(0) | ((s - 64 + clSize - 1) / clSize)).resize(overflowCountWidth)
  }

  private def overflowIdxToAddr(idx: UInt, isTx: Boolean = false): Bits = {
    val offset = if (isTx) U(EciAddressMap.tx_control) else U(EciAddressMap.rx_control)
    // two control cachelines before overflow
    ((idx + 2) * EciCmdDefs.ECI_CL_SIZE_BYTES + offset).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  private def ctrlToAddr(currIdx: UInt, isTx: Boolean = false): Bits = {
    (currIdx * EciCmdDefs.ECI_CL_SIZE_BYTES + (if (isTx) U(EciAddressMap.tx_control) else U(EciAddressMap.rx_control))).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  def makeAccessPorts(dcsConfig: Axi4Config, memConfig: Axi4Config): (Seq[(Axi4, SizeMapping)], Seq[Axi4]) = new Area {
    // RX router
    val rxRouter = DcsRxAxiRouter(dcsConfig, memConfig)

    // PREEMPT is returned at idle RX without consuming a packet.
    rxRouter.rxDesc.translateFrom(hostRx) { case (rxDesc, hostReq) =>
      rxDesc := hostReq.req
    }
    rxRouter.cancelRxDesc := (if(coreID == 0) False else host[Scheduler].logic.coreCancel(coreID - 1))
    rxRouter.currCl := logic.rxCurrClIdx.asUInt
    rxRouter.invDone := logic.rxInvDone
    rxRouter.doPreempt := preemptReq.fire
    rxRouter.yieldRequested := logic.yieldRequested
    rxRouter.fatalRelease := logic.fatalRelease
    logic.rxRouterQuiescent := rxRouter.quiescent
    logic.rxSentNack := rxRouter.nackSent
    logic.rxReqs := rxRouter.hostReq

    // TX router
    val txRouter = DcsTxAxiRouter(dcsConfig, memConfig)
    hostTxAck.translateFrom(txRouter.txDesc) { case (hostReq, txDesc) =>
      hostReq.req := txDesc
      hostReq.hostMsgId := logic.txHostMsgId
    }
    txRouter.txResult := hostTxResult
    logic.execution := txRouter.execution
    logic.executionOnly := txRouter.executionOnly
    txRouter.currCl := logic.txCurrClIdx.asUInt
    txRouter.txAddr := logic.savedTxAddr.addr
    txRouter.invDone := logic.txInvDone
    txRouter.doPreempt := preemptReq.fire
    logic.txRouterQuiescent := txRouter.quiescent
    logic.txInvLen := txRouter.currInvLen
    logic.txReqs := txRouter.hostReq

    if (isBypass) {
      // bypass core will have non-blocking poll of cachelines
      // this will allow NAPI-based Linux driver implementation
      rxRouter.blockCycles := 0
    } else {
      // We use normal block cycles for all worker cores.
      // No need to disable this on preemption: no read request will be in progress
      // when the preemption request comes from [[EciPreemptionControlPlugin]]
      rxRouter.blockCycles := host[EciInterfacePlugin].hostIfCtrl.rxBlockCycles
    }

    debug.postDebug(s"core${coreID}_rxRouter_state", rxRouter.stateOut)
    debug.postDebug(s"core${coreID}_txRouter_read_state", txRouter.readStateOut)
    debug.postDebug(s"core${coreID}_txRouter_write_state", txRouter.writeStateOut)

    val ret = (Seq(
      (rxRouter.dcsAxi, SizeMapping(0, txOffset)),
      (txRouter.dcsAxi, SizeMapping(txOffset, txOffset)),
    ), Seq(rxRouter.pktBufAxi, txRouter.pktBufAxi))
  }.setCompositeName(this, "makeAccessPorts").ret

  def preemptReq = logic.preemptReq

  val logic = during setup new Area {
    // Authoritative transport parities are returned in the activation snapshot.
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    // Cleanup starts after the outgoing thread has entered kernel yield.
    val execution = Flow(Bits(2 bits))
    val executionOnly = Bool()
    val preemptReq = Event
    val yieldRequested, fatalRelease, externalQuiet = Bool()
    val rxRouterQuiescent, txRouterQuiescent = Bool()
    val rxDrained = Bool()

    // invalidation done for routers
    val rxInvDone = Bool()
    val txInvDone = Bool()

    // the router sent back a NACK
    val rxSentNack = Bool()

    ECI_RX_BASE.set(EciAddressMap.rx_control)
    ECI_TX_BASE.set(EciAddressMap.tx_control)
    ECI_OVERFLOW_OFFSET.set(0x100)
    ECI_NUM_OVERFLOW_CL.set(numOverflowCls)

    val irqOut = isBypass generate Stream(EciIntcInterface())
    val irqEn = isBypass generate Bool()

    val numRetired, numReq, numNack, numPreempted = Counter(REG_WIDTH bits)

    awaitBuild()

    assert(txOffset >= sizePerMtuPerDirection, "tx offset does not allow one MTU for rx")

    val rxReqs = Vec(Bool(), 2)

    // A read from the CPU to the opposite CL to fetch a new request.
    val rxTriggerNew = rxReqs(1 - rxCurrClIdx.asUInt)

    val txReqs = Vec(Bool(), 2)

    Seq(rxLcl.lci, txLcl.lci).foreach { lci =>
      lci.setIdle()
      lci.valid.setAsReg() init False
      lci.payload.setAsReg()
      lci.assertPersistence()
    }
    rxDrained := False

    rxInvDone := False
    txInvDone := False

    rxLcl.lcia.setBlocked()
    txLcl.lcia.setBlocked()

    hostRxAck.setIdle()
    hostTx.setBlocked()

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val rxSlotToFree = Reg(PacketBufDesc())
    val rxHostMsgId = Reg(UInt(HostMsgID.width bits)) init invalidTraceId(HostMsgID.width)

    val rxSlotCaptured = hostRx.toFlowFire.map(_.req.buffer).toReg()
    val rxSlotCapturedValid = Reg(Bool()).setWhen(hostRx.fire).init(False)
    when (hostRx.fire) {
      rxHostMsgId := hostRx.hostMsgId
    }

    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val nextTxHostMsgId = Reg(UInt(HostMsgID.width bits)) init 0
    val txHostMsgId = Reg(UInt(HostMsgID.width bits)) init invalidTraceId(HostMsgID.width)

    // trace data
    val coreTd = CoreID(B(coreID))
    val rxHostMsgTd = HostMsgID(rxHostMsgId)
    val txHostMsgTd = HostMsgID(txHostMsgId)
    val rxReadTd = Seq(coreTd, CacheLineIndex(rxCurrClIdx))
    val rxClTd = Seq(coreTd, rxHostMsgTd, CacheLineIndex(rxCurrClIdx))
    val txClTd = Seq(coreTd, txHostMsgTd, CacheLineIndex(txCurrClIdx))
    val rxOverflowTd = Seq(coreTd, rxHostMsgTd, OverflowCount(rxOverflowToInvalidate))
    val txOverflowTd = Seq(coreTd, txHostMsgTd, OverflowCount(txOverflowToInvalidate))
    val invalidHostMsgTd = HostMsgID(B(invalidTraceId(HostMsgID.width), HostMsgID.width bits))

    def traceLcl(tp: TracePlugin#TracePort, eventName: String, hostMsgTd: TraceData, address: Bits): Bool = {
      tp.trace(eventName, coreTd, hostMsgTd, LclAddress(address))
    }

    def recvLcia(lcia: Stream[Bits], counter: Counter, lciaTracePort: TracePlugin#TracePort, hostMsgTd: TraceData): Unit = {
      lcia.freeRun()
      when (lcia.fire) {
        traceLcl(lciaTracePort, "EciLclLciaFire", hostMsgTd, lcia.payload)
        if (counter != null)
          counter.increment()
      }
    }


    val rxFsm = new StateMachine {
      def handlePreempt() = {
        when (preemptReq.valid && !rxReqs.orR) {
          rxDrained := True
        }
        when (preemptReq.fire) {
          rxHostMsgId := invalidTraceId(HostMsgID.width)
          numPreempted.increment()
          goto(waitHostRead)
        }
      }
      val waitHostRead: State = new State with EntryPoint {
        whenIsActive {
          // Wait for the very first read from host:
          // - after boot
          // - after preemption
          // In other cases, first read for the next request IS the one triggering the switch,
          // so we'd always end in hostIssuedRead
          when (rxReqs(rxCurrClIdx.asUInt)) {
            assert(!preemptReq.valid, "fresh RX read after relinquishment")
            rxTp.trace("EciRxReadFirst", rxReadTd: _*)
            goto(hostReadPending)
          } otherwise { handlePreempt() }
        }
      }
      val hostReadPending: State = new State {
        whenIsActive {
          when (rxSlotCapturedValid) {
            // A packet arrived in time.  Save the buffer that we sent to host and wait until
            // we need to invalidate the descriptor AND overflow data
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(rxSlotCaptured.size.bits)
            rxSlotToFree := rxSlotCaptured
            rxSlotCapturedValid := False
            numReq.increment()
            rxTp.trace("EciRxDescSent", rxOverflowTd: _*)
            goto(repeatDesc)
          } elsewhen (rxSentNack) {
            // No packet arrived in time, the router delivered a NACK
            rxOverflowToInvalidate := 0
            rxSlotToFree.clearAll()
            numNack.increment()
            rxTp.trace("EciRxNackSent", coreTd)
            goto(repeatDesc)
          } otherwise { handlePreempt() }
        }
      }
      val repeatDesc: State = new State {
        whenIsActive {
          // We got the first read.  The router repeats until the
          // CPU acks the descriptor (or NACK) by reading the opposite CL
          when (rxTriggerNew) {
            rxTp.trace("EciRxReadNew", rxReadTd: _*)
            when (rxSlotToFree.size.bits === 0) {
              // nothing to free or invalidate, just invalidate this NACK
              goto(invalidateCtrl)
            } otherwise {
              goto(freeSlot)
            }
          } elsewhen(preemptReq.valid) {
            // The old thread is stopped. Retire its last delivered RX slot and
            // invalidate its cachelines before the kernel changes the mapping.
            when(rxSlotToFree.size.bits === 0) { goto(invalidateCtrl) }
              .otherwise { goto(freeSlot) }
          }
        }
      }
      val freeSlot: State = new State {
        whenIsActive {
          hostRxAck.payload := rxSlotToFree
          hostRxAck.valid := True
          when (hostRxAck.fire) {
            rxOverflowInvAcked.clear()
            rxOverflowInvIssued.clear()
            when (rxOverflowToInvalidate > 0) {
              rxTp.trace("EciRxDataLciStart", rxOverflowTd: _*)
              goto(invalidatePacketData)
            } otherwise {
              rxTp.trace("EciRxFreeSlot", rxOverflowTd: _*)
              goto(invalidateCtrl)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when (rxOverflowInvIssued.valueNext < rxOverflowToInvalidate) {
            rxLcl.lci.payload := overflowIdxToAddr(rxOverflowInvIssued.valueNext)
            rxLcl.lci.valid := True
          }

          when (rxLcl.lci.fire) {
            traceLcl(rxLciTp, "EciLclLciFire", rxHostMsgTd, rxLcl.lci.payload)
            rxOverflowInvIssued.increment()
            when (rxOverflowInvIssued.valueNext === rxOverflowToInvalidate) {
              rxTp.trace("EciRxDataLciDone", rxOverflowTd: _*)
              goto(waitDataLciaDone)
            }
          }

          recvLcia(rxLcl.lcia, rxOverflowInvAcked, rxLciaTp, rxHostMsgTd)
        }
      }
      val waitDataLciaDone: State = new State {
        whenIsActive {
          recvLcia(rxLcl.lcia, rxOverflowInvAcked, rxLciaTp, rxHostMsgTd)

          when (rxOverflowInvAcked === rxOverflowToInvalidate) {
            rxTp.trace("EciRxDataLciaDone", rxOverflowTd: _*)
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          rxLcl.lci.payload := ctrlToAddr(rxCurrClIdx.asUInt)
          rxLcl.lci.valid := True
          when(rxLcl.lci.fire) {
            traceLcl(rxLciTp, "EciLclLciFire", rxHostMsgTd, rxLcl.lci.payload)
            rxLcl.lci.valid := False
            rxTp.trace("EciRxCtrlInvalidate", rxClTd: _*)
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          recvLcia(rxLcl.lcia, numRetired, rxLciaTp, rxHostMsgTd)

          when (rxLcl.lcia.fire) {
            // always toggle, even if NACK was sent
            rxCurrClIdx.toggleWhen(True)

            // Invalidation is triggered by reading opposite
            rxInvDone := True
            rxTp.trace("EciRxCtrlInvalidated", rxClTd: _*)
            rxHostMsgId := invalidTraceId(HostMsgID.width)
            when(preemptReq.valid) { goto(waitHostRead) }
              .otherwise { goto(hostReadPending) }
          }
        }
      }
    }

    when (txReqs.reduce(_ || _)) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }
    val savedTxAddr = hostTx.asFlow.toReg()
    val txInvLen = PacketLength()
    val abandonTx = RegInit(False)
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          abandonTx := False
          when (txReqs(txCurrClIdx.asUInt)) {
            txHostMsgId := nextTxHostMsgId
            nextTxHostMsgId := nextTxHostMsgId + 1
            txTp.trace("EciTxAcquire",
              coreTd,
              HostMsgID(nextTxHostMsgId),
              CacheLineIndex(txCurrClIdx)) := True
            goto(waitPacket)
          }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowToInvalidate.clearAll()
          when (txReqs(1 - txCurrClIdx.asUInt)) {
            // invalidate control first to know how many overflows do we need to invalidate
            txTp.trace("EciTxCommitRead", txClTd: _*)
            goto(invalidateCtrl)
          } elsewhen(preemptReq.valid) {
            // A killed thread can leave a dirty, uncommitted TX. Flush it but
            // never turn the partially written descriptor into a packet.
            abandonTx := True
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          txLcl.lci.payload := ctrlToAddr(txCurrClIdx.asUInt, isTx = true)
          txLcl.lci.valid := True
          when(txLcl.lci.fire) {
            traceLcl(txLciTp, "EciLclLciFire", txHostMsgTd, txLcl.lci.payload)
            txLcl.lci.valid := False
            txTp.trace("EciTxCtrlInvalidate", txClTd: _*)
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          recvLcia(txLcl.lcia, null, txLciaTp, txHostMsgTd)

          when (txLcl.lcia.fire) {
            // we should've latched tx descriptor in savedTxDesc
            val toInvalidate = abandonTx ? U(numOverflowCls, overflowCountWidth bits) |
              packetSizeToNumOverflowCls(txInvLen.bits)
            txOverflowToInvalidate := toInvalidate
            when (toInvalidate > 0) {
              txOverflowInvIssued.clear()
              txOverflowInvAcked.clear()
              txTp.trace("EciTxDataLciStart", coreTd, txHostMsgTd, OverflowCount(toInvalidate))
              goto(invalidatePacketData)
            } otherwise {
              txTp.trace("EciTxCtrlInvalidated", txClTd: _*)
              goto(tx)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when(txOverflowInvIssued.valueNext < txOverflowToInvalidate) {
            txLcl.lci.payload := overflowIdxToAddr(txOverflowInvIssued.valueNext, isTx = true)
            txLcl.lci.valid := True
          }

          when (txLcl.lci.fire) {
            traceLcl(txLciTp, "EciLclLciFire", txHostMsgTd, txLcl.lci.payload)
            txOverflowInvIssued.increment()
            when (txOverflowInvIssued.valueNext === txOverflowToInvalidate) {
              txTp.trace("EciTxDataLciDone", txOverflowTd: _*)
              goto(waitDataLciaDone)
            }
          }

          recvLcia(txLcl.lcia, txOverflowInvAcked, txLciaTp, txHostMsgTd)
        }
      }
      val waitDataLciaDone: State = new State {
        whenIsActive {
          recvLcia(txLcl.lcia, txOverflowInvAcked, txLciaTp, txHostMsgTd)

          when (txOverflowInvAcked === txOverflowToInvalidate) {
            txTp.trace("EciTxDataLciaDone", txOverflowTd: _*)
            goto(tx)
          }
        }
      }
      val tx: State = new State {
        whenIsActive {
          // allow router to send tx descriptor
          txInvDone := !abandonTx

          when(abandonTx) { goto(idle) }

          when (hostTxAck.fire) {
            txCurrClIdx.toggleWhen(True)
            txTp.trace("EciTxSubmit", (txClTd ++ Seq(
              ExecutionCommand(execution.payload))): _*)
            txHostMsgId := invalidTraceId(HostMsgID.width)
            goto(idle)
          }
        }
      }
    }

    // make rx and tx fsm states available to top for debug
    rxFsm.build()
    txFsm.build()
    preemptReq.ready := rxDrained && txFsm.isActive(txFsm.idle) &&
      rxRouterQuiescent && txRouterQuiescent && externalQuiet

    // if this is the bypass core, emit IRQ when the RX queue is not empty
    val bypassIrqArea: Area {
      val issued : Counter
      val irqFsm: StateMachine
    } = isBypass generate new Composite(this, "irqGen") {
      irqOut.setIdle()

      val issued = Counter(REG_WIDTH bits)

      // If the bypass queue is non empty, the host needs to be notified to drain it
      def bypassStalled = hostRx.isStall
      def itrace(ev: String) = irqTp.trace(s"BypassIrq$ev",
        coreTd,
        InterruptEnabled(irqEn),
        BypassStalled(bypassStalled),
      )

      // Edge-triggered interrupt. Linux masks the interrupt in the ISR and
      // rearms it after NAPI drains the queue.
      val irqFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when (bypassStalled && irqEn) {
              itrace("Pending")
              goto(sendIrq)
            }
          }
        }
        val sendIrq: State = new State {
          whenIsActive {
            irqOut.valid   := True
            irqOut.affLvl0 := 1   // always send to core 0
            irqOut.affLvl1 := 0
            irqOut.cmd     := 0
            irqOut.intId   := 15  // use 15 for bypass interrupts
            when (irqOut.ready) {
              itrace("Issued")
              goto(waitMasked)
              issued.increment()
            }
          }
        }
        val waitMasked: State = new State {
          whenIsActive {
            when (!irqEn) {
              goto(idle)
            }
          }
        }
      }
      irqFsm.build()
    }
  }

  during build {
    if (!emittedMackerel) {
      EciHostRxCtrlInfo().addMackerel
      EciHostTxCtrlInfo.addMackerel()
      emittedMackerel = true
    }
  }
}

object EciDecoupledRxTxProtocol {
  var emittedMackerel = false
}
