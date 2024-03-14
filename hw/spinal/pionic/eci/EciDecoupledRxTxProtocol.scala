package pionic.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import pionic.{GlobalCSRPlugin, PacketLength, PioNicConfig}
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4SlaveFactory}
import spinal.lib.bus.misc.{SingleMapping, SizeMapping}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

case class PacketCtrlInfo()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketCtrlInfo()

  val size = PacketLength()

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= 512, "packet info larger than half a cacheline")
}

class EciDecoupledRxTxProtocol(coreID: Int)(implicit val config: PioNicConfig) extends FiberPlugin with EciPioProtocol {
  withPrefix(s"core_$coreID")

  lazy val csr = host[GlobalCSRPlugin].logic
  lazy val numOverflowCls = (host[EciInterfacePlugin].sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt
  lazy val overflowCountWidth = log2Up(numOverflowCls)
  lazy val txOffset = host[EciInterfacePlugin].sizePerMtuPerDirection

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

  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits]): Unit = new Area {
    // address offset for this core in CoreControl descriptors
    val descOffset = config.pktBufSizePerCore * coreID

    val rxSize = host[EciInterfacePlugin].rxSizePerCore
    val txSize = host[EciInterfacePlugin].txSizePerCore

    // remap packet data: first cacheline inline packet data -> second one
    // such that we have continuous address when handling packet data
    val busCtrl = Axi4SlaveFactory(bus).remapAddress { addr =>
      addr.mux(
        U(0x40) -> U(0xc0),
        U(txOffset + 0x40) -> U(txOffset + 0xc0),
        default -> addr
      )
    }

    hostRxNextReq := False
    logic.txReq := False
    Seq(0, 1) foreach { idx => new Area {
      val rxCtrlAddr = idx * 0x80
      busCtrl.onReadPrimitive(SingleMapping(rxCtrlAddr), false, null) {
        hostRxNextReq := True
      }

      // readStreamBlockCycles report timeout on last beat of stream, but we need to issue it after the entire reload is finished
      val streamTimeout = Bool()
      val bufferedStreamTimeout = Reg(Bool()) init False
      bufferedStreamTimeout.setWhen(streamTimeout)
      busCtrl.readStreamBlockCycles(logic.rxPacketCtrl(idx), rxCtrlAddr, csr.ctrl.rxBlockCycles, streamTimeout)
      logic.rxTriggerInv(idx) := False
      busCtrl.onRead(0xc0) {
        logic.rxTriggerInv(idx) := bufferedStreamTimeout | streamTimeout
        bufferedStreamTimeout.clear()
      }

      val txCtrlAddr = txOffset + idx * 0x80
      busCtrl.driveStream(logic.txPacketCtrl(idx), txCtrlAddr)
      // dummy read for tx ctrl cacheline loads
      busCtrl.readAllOnes(txCtrlAddr, 64)
      busCtrl.onReadPrimitive(SingleMapping(txCtrlAddr), false, null) {
        logic.txReq := True
      }
    }.setCompositeName(this, "driveBusCtrl")
    }

    val rxBufMapping = SizeMapping(descOffset, rxSize)

    // cpu not supposed to modify rx packet data, so omitting write
    busCtrl.readSyncMemWordAligned(rxPktBuffer, 0xc0, memOffset = rxBufMapping.removeOffset(logic.savedHostRx.addr.bits).resized)

    // tx buffer always start at 0
    busCtrl.writeMemWordAligned(txPktBuffer, txOffset + 0xc0)
    // dummy read for tx overflow cacheline loads
    busCtrl.readAllOnes(txOffset + 0xc0, txSize)
  }.setName("driveDcsBus")

  val logic = during build new Area {
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    val rxTriggerInv = Vec.fill(2)(Bool())

    val txReq = Bool()

    lci.setIdle()
    lci.valid.setAsReg()
    lci.payload.setAsReg()

    val ulFlow = Flow(ul.payload.clone).setIdle()
    val ulOverflow = Bool()
    // max number of inflight ULs: overflow CLs + 2 ctrl CLs
    ul << ulFlow.toStream(ulOverflow).queue(numOverflowCls + 2)
    assert(
      assertion = !ulOverflow,
      message = s"UL flow overflow",
      severity = FAILURE
    )

    lcia.setBlocked()

    hostRxNextAck.setIdle()
    hostTx.setBlocked()

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    val savedHostRx = RegNextWhen(hostRxNext.payload, hostRxNext.fire)

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxOverflowToInvalidate.clearAll()
          rxOverflowInvAcked.clear()
          rxOverflowInvIssued.clear()

          when (hostRxNext.fire) {
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(hostRxNext.payload.size.bits)
            goto(gotPacket)
          }
          when (rxTriggerInv(rxCurrClIdx.asUInt)) {
            goto(invalidateCtrl)
          }
        }
      }
      val gotPacket: State = new State {
        whenIsActive {
          when (hostRxNextReq) {
            // current control already is valid, must be the other one
            hostRxNextAck.payload := savedHostRx
            hostRxNextAck.valid := True
            when (hostRxNextAck.fire) {
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
          when (rxOverflowInvIssued < rxOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(rxOverflowInvIssued.valueNext)
            lci.valid := True
            when (lci.fire) {
              rxOverflowInvIssued.increment()
              when (rxOverflowInvIssued === rxOverflowToInvalidate) { lci.valid := False }
            }
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

            goto(idle)
          }
        }
      }
    }
    val ctrlInfo = PacketCtrlInfo()
    ctrlInfo.size := hostRxNext.payload.size
    val rxCtrlInfoStream = hostRxNext.translateWith(ctrlInfo).continueWhen(rxFsm.isActive(rxFsm.idle))
    val rxPacketCtrl = StreamDemux(rxCtrlInfoStream, rxCurrClIdx.asUInt, 2)

    when (txReq) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }
    val txPacketCtrl = Vec.fill(2)(Stream(PacketCtrlInfo()))
    val muxed = StreamMux(txCurrClIdx.asUInt, txPacketCtrl)
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (txReq) { goto(waitPacket) }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowInvIssued.clear()
          txOverflowInvAcked.clear()
          txOverflowToInvalidate.clearAll()
          when (txReq) {
            when (muxed.fire) {
              val toInv = txOverflowToInvalidate.clone
              toInv := packetSizeToNumOverflowCls(muxed.payload.size.bits)
              txOverflowToInvalidate := toInv
              when (toInv > 0) {
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
          when(txOverflowInvIssued < txOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(txOverflowInvIssued, isTx = true)
            lci.valid := True
            when (lci.fire) {
              txOverflowInvIssued.increment()
              when (txOverflowInvIssued === txOverflowToInvalidate) { lci.valid := False }
            }
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            txOverflowInvAcked.increment()
          }

          when (txOverflowInvAcked === txOverflowToInvalidate) {
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(txCurrClIdx.asUInt, isTx = true)
          lci.valid := True
          when (lci.fire) { lci.valid := False }

          lcia.freeRun()
          when (lcia.fire) {
            // immediately unlock
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            goto(tx)
          }
        }
      }
      val tx: State = new State {
        whenIsActive {
          txCurrClIdx.toggleWhen(True)
          goto(waitPacket)
        }
      }
    }
    hostTxAck << muxed.translateWith(muxed.payload.size).continueWhen(txFsm.isActive(txFsm.tx))
  }
}
