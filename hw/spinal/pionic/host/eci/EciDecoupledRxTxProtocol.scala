package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import jsteward.blocks.misc.RegBlockAlloc
import pionic._
import pionic.host.DatapathPlugin
import pionic.host.eci.EciDecoupledRxTxProtocol.emittedMackerel
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4CrossbarFactory}
import spinal.lib.bus.misc.{BusSlaveFactory, SizeMapping}
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._

import scala.language.postfixOps
import scala.math.BigInt.int2bigInt

import Global._

class EciDecoupledRxTxProtocol(coreID: Int) extends DatapathPlugin(coreID) with EciPioProtocol {
  withPrefix(s"core_$coreID")

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc) = {
    busCtrl.read(logic.rxFsm.stateReg, alloc("rxFsmState", attr = RO))
    busCtrl.read(logic.rxCurrClIdx, alloc("rxCurrClIdx", attr = RO))

    busCtrl.read(logic.txFsm.stateReg, alloc("txFsmState", attr = RO))
    busCtrl.read(logic.txCurrClIdx, alloc("txCurrClIdx", attr = RO))
  }
  lazy val csr = host[GlobalCSRPlugin].logic
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

  def driveDcsBus(bus: Axi4, pktBufAxiNode: Axi4): Unit = new Area {
    // RX router
    val rxRouter = DcsRxAxiRouter(bus.config, pktBufAxiNode.config)
    rxRouter.rxDesc << hostRx
    rxRouter.currCl := logic.rxCurrClIdx.asUInt
    rxRouter.invDone := logic.rxInvDone
    logic.rxReqs := rxRouter.hostReq

    // TX router
    val txRouter = DcsTxAxiRouter(bus.config, pktBufAxiNode.config)
    txRouter.txDesc >> hostTxAck
    txRouter.currCl := logic.txCurrClIdx.asUInt
    txRouter.txAddr := logic.savedTxAddr.addr
    txRouter.invDone := logic.txInvDone
    logic.txInvLen := txRouter.currInvLen
    logic.txReqs := txRouter.hostReq

    // mux RX and TX routers to DCS master
    Axi4CrossbarFactory()
      .addSlaves(
        rxRouter.dcsAxi -> SizeMapping(0, txOffset),
        txRouter.dcsAxi -> SizeMapping(txOffset, txOffset)
      )
      .addConnection(bus, Seq(rxRouter.dcsAxi, txRouter.dcsAxi))
      .build()

    // mux packet buffer access nodes
    Axi4CrossbarFactory()
      .addSlave(pktBufAxiNode, SizeMapping(0, PKT_BUF_SIZE))
      .addConnections(
        rxRouter.pktBufAxi -> Seq(pktBufAxiNode),
        txRouter.pktBufAxi -> Seq(pktBufAxiNode),
      )
      .build()

    val blockCycles = CombInit(csr.ctrl.rxBlockCycles)
    // disable block cycles, when a preemption request is under way
    // this way we immediately return a NACK, instead of waiting until timeout
    when (preemptReq.valid) { blockCycles.clearAll() }
    rxRouter.blockCycles := blockCycles
  }.setName(s"driveDcsBus_core$coreID")

  def preemptReq = logic.preemptReq

  val logic = during setup new Area {
    // these will be hooked by [[EciPreemptionControlPlugin]]
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False
    val preemptReq = Event

    // invalidation done for routers
    val rxInvDone = Bool()
    val txInvDone = Bool()

    awaitBuild()

    assert(txOffset >= sizePerMtuPerDirection, "tx offset does not allow one MTU for rx")

    postConfig("eci rx base", 0)
    postConfig("eci tx base", txOffset)
    postConfig("eci overflow offset", 0x100)
    postConfig("eci num overflow cl", numOverflowCls)

    // corner case: when nack comes in after a long packet, this could be delivered before all LCIs for packets
    // finish issuing
    // ASSUMPTION: two control cachelines will never trigger NACK invalidate, thus shared
    val rxNackTriggerInv = Reg(Bool()) init False

    val rxReqs = Vec(Bool(), 2)
    val txReqs = Vec(Bool(), 2)

    lci.setIdle()
    lci.valid.setAsReg()
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

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    // register accepted host rx packet for:
    // - generating hostRxAck
    // - driving mem offset for packet buffer load
    val rxPktBufSaved = Reg(PacketBufDesc())

    // read start is when request for the selected CL is active for the first time
    val hostFirstRead = Reg(Bool()) init False
    hostRxReq := hostFirstRead

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        onEntry {
          rxNackTriggerInv.clear()
        }
        whenIsActive {
          rxOverflowToInvalidate.clearAll()
          rxOverflowInvAcked.clear()
          rxOverflowInvIssued.clear()
          hostFirstRead.clear()

          when (rxReqs(rxCurrClIdx.asUInt)) {
            hostFirstRead.set()
            goto(hostWaiting)
          } elsewhen (preemptReq.valid) {
            preemptReq.ready := True
          }
        }
      }
      val hostWaiting: State = new State {
        whenIsActive {
          when (hostRx.fire) {
            // a packet arrived in time
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(hostRx.buffer.size.bits)
            goto(repeatPacket)
          } elsewhen (rxNackTriggerInv) {
            // we returned NACK due to:
            // - packet did not come in time, or
            // - timeout terminated due to preemption
            goto(noPacket)
          }
        }
      }
      val noPacket: State = new State {
        whenIsActive {
          when (rxReqs(1 - rxCurrClIdx.asUInt) || preemptReq.valid) {
            goto(invalidateCtrl)
          }
        }
      }
      // we got at least one read, repeating until CPU ack'ed or preempted
      val repeatPacket: State = new State {
        whenIsActive {
          when (rxReqs(1 - rxCurrClIdx.asUInt) || preemptReq.valid) {
            hostRxAck.payload := rxPktBufSaved
            hostRxAck.valid := True
            when (hostRxAck.fire) {
              when (rxOverflowToInvalidate > 0) {
                goto(invalidatePacketData)
              } otherwise {
                goto(invalidateCtrl)
              }
            }
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

            when (preemptReq.valid) {
              // ack preempReq but DON'T toggle parity
              // both control CLs are in invalid
              // only need to make sure parity tracked by CPU and Protocol are the same
              preemptReq.ready := True
            } otherwise {
              // always toggle, even if NACK was sent
              rxCurrClIdx.toggleWhen(True)
            }

            rxInvDone := True
            goto(idle)
          }
        }
      }
    }

    when (txReqs.reduce(_ || _)) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }
    val savedTxAddr = hostTx.toFlowFire.toReg()
    val txInvLen = PacketLength()
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (txReqs(txCurrClIdx.asUInt)) { goto(waitPacket) }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowInvIssued.clear()
          txOverflowInvAcked.clear()
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
          goto(idle)
        }
      }
    }

    // make rx and tx fsm states available to top for debug
    rxFsm.build()
    txFsm.build()
    host[DebugPlugin].postDebug(s"rxFsm_${coreID}_stateReg", rxFsm.stateReg)
    host[DebugPlugin].postDebug(s"rxFsm_${coreID}_currClIdx", rxCurrClIdx)
    host[DebugPlugin].postDebug(s"txFsm_${coreID}_stateReg", txFsm.stateReg)
    host[DebugPlugin].postDebug(s"txFsm_${coreID}_currClIdx", txCurrClIdx)
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