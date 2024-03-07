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
  lazy val csr = host[GlobalCSRPlugin].logic
  lazy val numOverflowCls = host[EciInterfacePlugin].sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1
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
    (currIdx + (if (isTx) U(txOffset) else U(0))).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits]): Unit = new Area {
    val descOffset = config.pktBufSizePerCore * coreID
    val rxSize = host[EciInterfacePlugin].rxSizePerCore
    val txSize = host[EciInterfacePlugin].txSizePerCore

    // remap packet data
    val busCtrl = Axi4SlaveFactory(bus.remapAddr { addr =>
      addr.mux(
        U(0x40) -> U(0xc0),
        U(txOffset + 0x40) -> U(txOffset + 0xc0),
        default -> addr
      )
    })

    hostRxNextReq := False
    Seq(0, 1) foreach { idx =>
      busCtrl.onReadPrimitive(SingleMapping(idx * 0x80), false, null) {
        hostRxNextReq := True
      }

      // only allow loads when no invalidation is pending
      busCtrl.readStreamBlockCycles(logic.rxPacketCtrl(idx), idx * 0x80, csr.ctrl.rxBlockCycles, logic.rxTimeouts(idx))

      // allow read since host need to bring cacheline in to modify
      busCtrl.readAndWrite(logic.txPacketCtrl(idx), txOffset + idx * 0x80)
    }

    val rxMapping = SizeMapping(descOffset, rxSize)
    val txMapping = SizeMapping(descOffset + rxSize, txSize)

    // cpu not supposed to modify rx packet data
    busCtrl.readSyncMemWordAligned(rxPktBuffer, 0xc0, memOffset = rxMapping.removeOffset(logic.savedHostRx.addr.bits).resized)

    // dummy read for tx cacheline reload
    busCtrl.readPrimitive(Bits(busCtrl.busDataWidth bits).setAll(), txMapping, 0, null)
    busCtrl.writeMemWordAligned(txPktBuffer, txOffset + 0xc0)
  }.setName("driveDcsBus")

  val logic = during build new Area {
    val ctrlInfo = PacketCtrlInfo()
    ctrlInfo.size := hostRxNext.payload.size
    val rxDispatchIdx = Reg(Bool()) init False
    val rxTimeouts = Vec.fill(2)(Bool())

    val txPacketCtrl = Vec.fill(2)(Stream(PacketCtrlInfo()))
    txPacketCtrl.assignDontCare()

    lci.setIdle()
    lci.valid.setAsReg()
    lci.payload.setAsReg()

    ul.setIdle()
    ul.valid.setAsReg()
    ul.payload.setAsReg()

    lcia.setBlocked()

    hostRxNextAck.setIdle()
    hostTx.setBlocked()
    hostTxAck.setIdle()

    val overflowInvIssued, overflowInvAcked = Counter(overflowCountWidth bits)
    val overflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    val savedHostRx = RegNextWhen(hostRxNext.payload, hostRxNext.fire)

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          overflowToInvalidate.clearAll()
          overflowInvAcked.clear()
          overflowInvIssued.clear()

          when (hostRxNext.fire) {
            overflowToInvalidate := packetSizeToNumOverflowCls(savedHostRx.size.bits)
            goto(gotPacket)
          }
          when (rxTimeouts(rxDispatchIdx.asUInt)) {
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
              goto(invalidatePacketData)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when (overflowInvIssued < overflowToInvalidate) {
            lci.payload := overflowIdxToAddr(overflowInvIssued)
            lci.valid := True
            when (lci.fire) {
              overflowInvIssued.increment()
              when (overflowInvIssued === overflowToInvalidate) { lci.valid := False }
            }
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately since we guarantee that the CPU only loads the overflow cachelines after control
            ul.payload := lcia.payload
            ul.valid := True

            overflowInvAcked.increment()
          }

          // count till all overflow cachelines are invalidated
          when (overflowInvAcked === overflowToInvalidate) {
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(rxDispatchIdx.asUInt)
          lci.valid := True
          when (lci.fire) { lci.valid := False }

          lcia.freeRun()
          when (lcia.fire) {
            // FIXME: should we check the response address?

            // immediately unlock so that we can see later load requests
            ul.payload := lcia.payload
            ul.valid := True

            // always toggle, even if NACK was sent
            rxDispatchIdx.toggleWhen(True)

            goto(idle)
          }
        }
      }
    }
    val ctrlInfoStream = hostRxNext.translateWith(ctrlInfo).continueWhen(rxFsm.isActive(rxFsm.idle))
    val rxPacketCtrl = StreamDemux(ctrlInfoStream, rxDispatchIdx.asUInt, 2)

    val txFsm = new StateMachine {
      val stateIdle: State = new State with EntryPoint {
        whenIsActive {
        }
      }
    }
  } setCompositeName(this, s"logic_$coreID")
}
