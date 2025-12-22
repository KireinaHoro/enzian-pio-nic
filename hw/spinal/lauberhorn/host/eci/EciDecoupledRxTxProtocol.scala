package lauberhorn.host.eci

import jsteward.blocks.eci.{EciCmdDefs, EciIntcInterface}
import jsteward.blocks.misc.RegBlockAlloc
import jsteward.blocks.axi._
import lauberhorn._
import lauberhorn.host.DatapathPlugin
import lauberhorn.host.eci.EciDecoupledRxTxProtocol.emittedMackerel
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4CrossbarFactory}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.misc.{BusSlaveFactory, SizeMapping}
import spinal.lib.bus.regif.AccessType.{RO, RW, WO}
import spinal.lib.fsm._
import Global._

import scala.language.postfixOps
import scala.math.BigInt.int2bigInt

class EciDecoupledRxTxProtocol(coreID: Int) extends DatapathPlugin(coreID) with EciPioProtocol {
  val isBypass = coreID == 0
  lazy val debug = host[DebugPlugin]

  if (isBypass) {
    withPrefix("proto_bypass")
  } else {
    withPrefix(s"proto_worker_${coreID - 1}")
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    // TODO: emit constants type for FSM state value
    busCtrl.read(logic.rxFsm.stateReg, alloc("rxFsmState", attr = RO,
      desc = "state of the RX state machine (raw value)"))
    busCtrl.read(logic.txFsm.stateReg, alloc("txFsmState", attr = RO,
      desc = "state of the TX state machine (raw value)"))

    busCtrl.readAndWrite(logic.txCurrClIdx, alloc("txCurrClIdx", attr = RW,
      desc = "parity (next CL to write) of the TX state machine"))
    busCtrl.readAndWrite(logic.rxCurrClIdx, alloc("rxCurrClIdx", attr = RW,
      desc = "parity (next CL to read) of the RX state machine"))

    debug.postDebug(s"core${coreID}_rxFsm_state", logic.rxFsm.stateReg)
    debug.postDebug(s"core${coreID}_txFsm_state", logic.txFsm.stateReg)
    debug.postDebug(s"core${coreID}_rxClIdx", logic.rxCurrClIdx)
    debug.postDebug(s"core${coreID}_txClIdx", logic.txCurrClIdx)

    if (isBypass) {
      busCtrl.write(logic.bypassIrqArea.irqInject,
        alloc("irqInject", attr = WO, desc = "inject IRQ to bypass core"))
      busCtrl.read(logic.bypassIrqArea.irqFsm.stateReg,
        alloc("irqFsmState", attr = RO, desc = "state of the bypass IRQ state machine (raw value)"))

      debug.postDebug(s"core${coreID}_irqFsm_state", logic.bypassIrqArea.irqFsm.stateReg)
    }

    busCtrl.read(logic.numRetired.value, alloc("numRetired", attr = RO, desc = "number of retired requests"))
    busCtrl.read(logic.numReq.value, alloc("numReq", attr = RO, desc = "number of requests observed"))
    busCtrl.read(logic.numNack.value, alloc("numNack", attr = RO, desc = "number of NACKs observed"))
    busCtrl.read(logic.numPreempted.value, alloc("numPreempted", attr = RO, desc = "times this worker has been preempted"))
  }
  lazy val overflowCountWidth = log2Up(numOverflowCls)

  // two control half CLs, one extra first word half CL, one MTU
  // CL#0: [ control | first data (aliased) ]
  // CL#1: [ control | first data (aliased) ]
  // CL#2...: [ rest data ]
  lazy val sizePerMtuPerDirection = (512 / 8) * 3 + ROUNDED_MTU
  lazy val numOverflowCls = (sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt

  // map at aligned address to eliminate long comb paths
  val txOffset = 0x8000
  val sizePerCore = 2 * txOffset

  private def packetSizeToNumOverflowCls(s: UInt): UInt = {
    val clSize = EciCmdDefs.ECI_CL_SIZE_BYTES
    ((s <= 64) ? U(0) | ((s - 64 + clSize - 1) / clSize)).resize(overflowCountWidth)
  }

  private def overflowIdxToAddr(idx: UInt, isTx: Boolean = false): Bits = {
    val offset = if (isTx) U(txOffset) else U(0)
    // two control cachelines before overflow
    ((idx + 2) * EciCmdDefs.ECI_CL_SIZE_BYTES + offset).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  private def ctrlToAddr(currIdx: UInt, isTx: Boolean = false): Bits = {
    (currIdx * EciCmdDefs.ECI_CL_SIZE_BYTES + (if (isTx) U(txOffset) else U(0))).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  def makeAccessPorts(dcsConfig: Axi4Config, memConfig: Axi4Config): (Seq[(Axi4, SizeMapping)], Seq[Axi4]) = new Area {
    // RX router
    val rxRouter = DcsRxAxiRouter(dcsConfig, memConfig)

    // No need to halt the stream here during preemption: host can't be reading
    // when preemption happens, since it is out of the critical region where a
    // read can happen.
    rxRouter.rxDesc << hostRx
    rxRouter.currCl := logic.rxCurrClIdx.asUInt
    rxRouter.invDone := logic.rxInvDone
    rxRouter.doPreempt := preemptReq.valid
    logic.rxSentNack := rxRouter.nackSent
    logic.rxReqs := rxRouter.hostReq

    // TX router
    val txRouter = DcsTxAxiRouter(dcsConfig, memConfig)
    txRouter.txDesc >> hostTxAck
    txRouter.currCl := logic.txCurrClIdx.asUInt
    txRouter.txAddr := logic.savedTxAddr.addr
    txRouter.invDone := logic.txInvDone
    txRouter.doPreempt := preemptReq.valid
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
      rxRouter.blockCycles := host[EciInterfacePlugin].rxBlockCycles
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
    // The kernel will update these on thread resume
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    // Preemption request from [[EciPreemptionControlPlugin]].  This will only come
    // after the thread is out of the ready/busy critical section and spinning on !ready
    val preemptReq = Event

    // invalidation done for routers
    val rxInvDone = Bool()
    val txInvDone = Bool()

    // the router sent back a NACK
    val rxSentNack = Bool()

    ECI_RX_BASE.set(0)
    ECI_TX_BASE.set(txOffset)
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

    lci.setIdle()
    lci.valid.setAsReg() init False
    lci.payload.setAsReg()
    lci.assertPersistence()
    preemptReq.setBlocked()

    rxInvDone := False
    txInvDone := False

    val ulFlow = Flow(EciCmdDefs.EciAddress).setIdle()
    val ulOverflow = Bool()
    // max number of inflight ULs: overflow CLs + 2 ctrl CLs
    ul << ulFlow.toStream(ulOverflow).queue(numOverflowCls + 2)
    assert(
      assertion = !ulOverflow,
      message = s"UL flow overflow",
      severity = FAILURE
    )

    lcia.setBlocked()

    hostRxAck.setIdle()
    hostTx.setBlocked()
    hostRxReq := False

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val rxSlotToFree = Reg(PacketBufDesc())

    val rxSlotCaptured = hostRx.toFlowFire.map(_.buffer).toReg()
    val rxSlotCapturedValid = Reg(Bool()).setWhen(hostRx.fire).init(False)

    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    val rxFsm = new StateMachine {
      def handlePreempt() = {
        when (preemptReq.valid) {
          assert(!rxReqs.orR, "critical section violation: no read is allowed during preemption")
          preemptReq.ready := True
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
            assert(!preemptReq.valid, "critical section violation: no preemption is allowed during read")
            hostRxReq := True // only used for timestamping...
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
            goto(repeatDesc)
          } elsewhen (rxSentNack) {
            // No packet arrived in time, the router delivered a NACK
            rxOverflowToInvalidate := 0
            rxSlotToFree.clearAll()
            numNack.increment()
            goto(repeatDesc)
          } otherwise { handlePreempt() }
        }
      }
      val repeatDesc: State = new State {
        whenIsActive {
          // We got the first read.  The router repeats until the
          // CPU acks the descriptor (or NACK) by reading the opposite CL
          when (rxTriggerNew) {
            when (rxSlotToFree.size.bits === 0) {
              // nothing to free or invalidate, just invalidate this NACK
              goto(invalidateCtrl)
            } otherwise {
              goto(freeSlot)
            }
          } otherwise { handlePreempt() }
        }
      }
      val freeSlot: State = new State {
        whenIsActive {
          hostRxAck.payload := rxSlotToFree
          hostRxAck.valid := True
          when (hostRxAck.fire) {
            rxOverflowInvAcked.clear()
            rxOverflowInvIssued.clear()
            goto(invalidatePacketData)
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when (rxOverflowInvIssued.valueNext < rxOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(rxOverflowInvIssued.valueNext)
            lci.valid := True
          }

          when (lci.fire) {
            rxOverflowInvIssued.increment()
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately since we guarantee that the CPU only loads the overflow cachelines after control
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            rxOverflowInvAcked.increment()
          }

          // count till all overflow cachelines are invalidated
          when (rxOverflowInvAcked === rxOverflowToInvalidate) {
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(rxCurrClIdx.asUInt)
          lci.valid := True
          when(lci.fire) {
            lci.valid := False
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.fire) {
            // FIXME: should we check the response address?

            // immediately unlock so that we can see later load requests
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            // always toggle, even if NACK was sent
            rxCurrClIdx.toggleWhen(True)

            numRetired.increment()

            // Invalidation is triggered by reading opposite
            rxInvDone := True
            goto(hostReadPending)
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
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (txReqs(txCurrClIdx.asUInt)) { goto(waitPacket) }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowToInvalidate.clearAll()
          when (txReqs(1 - txCurrClIdx.asUInt)) {
            // invalidate control first to know how many overflows do we need to invalidate
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(txCurrClIdx.asUInt, isTx = true)
          lci.valid := True
          when(lci.fire) {
            lci.valid := False
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.fire) {
            // immediately unlock
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            // we should've latched tx descriptor in savedTxDesc
            val toInvalidate = packetSizeToNumOverflowCls(txInvLen.bits)
            txOverflowToInvalidate := toInvalidate
            when (toInvalidate > 0) {
              txOverflowInvIssued.clear()
              txOverflowInvAcked.clear()
              goto(invalidatePacketData)
            } otherwise {
              goto(tx)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when(txOverflowInvIssued.valueNext < txOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(txOverflowInvIssued.valueNext, isTx = true)
            lci.valid := True
          }

          when (lci.fire) {
            txOverflowInvIssued.increment()
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            txOverflowInvAcked.increment()
          }

          when (txOverflowInvAcked === txOverflowToInvalidate) {
            goto(tx)
          }
        }
      }
      val tx: State = new State {
        whenIsActive {
          // allow router to send tx descriptor
          txInvDone := True

          when (hostTxAck.fire) {
            txCurrClIdx.toggleWhen(True)
            goto(idle)
          }
        }
      }
    }

    // make rx and tx fsm states available to top for debug
    rxFsm.build()
    txFsm.build()

    // if this is the bypass core, emit IRQ when the RX queue is not empty
    val bypassIrqArea: Area{val irqInject: Bool; val irqFsm: StateMachine} = isBypass generate new Composite(this, "irqGen") {
      val irqInject = RegInit(False)

      irqOut.setIdle()

      // Level-triggered interrupt for NAPI
      val irqFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when ((hostRx.isStall || irqInject) && irqEn) {
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
      EciHostCtrlInfo().addMackerel
      emittedMackerel = true
    }
  }
}

object EciDecoupledRxTxProtocol {
  var emittedMackerel = false
}
