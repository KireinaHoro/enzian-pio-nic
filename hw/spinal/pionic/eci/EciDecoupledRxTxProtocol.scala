package pionic.eci

import jsteward.blocks.axi.RichAxi4
import pionic.{GlobalCSRPlugin, PacketLength, PioNicConfig}
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4SlaveFactory}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

case class PacketCtrlInfo()(implicit config: PioNicConfig) extends Bundle {
  val size = PacketLength()

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= 512, "packet info larger than half a cacheline")
}

class EciDecoupledRxTxProtocol(coreID: Int)(implicit val config: PioNicConfig) extends FiberPlugin with EciPioProtocol {
  lazy val csr = host[GlobalCSRPlugin].logic

  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits]): Unit = new Area {
    val txOffset = host[EciInterfacePlugin].sizePerMtuPerDirection
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

    Seq(0, 1) foreach { idx =>
      busCtrl.readStreamBlockCycles(logic.rxPacketCtrl(idx), idx * 0x80, csr.ctrl.rxBlockCycles, logic.rxTimeouts(idx))

      // allow read since host need to bring cacheline in to modify
      busCtrl.readAndWrite(logic.txPacketCtrl(idx), txOffset + idx * 0x80)
    }

    val rxMapping = SizeMapping(descOffset, rxSize)
    val txMapping = SizeMapping(descOffset + rxSize, txSize)

    // cpu not supposed to modify rx packet data
    busCtrl.readSyncMemWordAligned(rxPktBuffer, 0xc0, memOffset = rxMapping.removeOffset(logic.rxNextAddr.bits).resized)

    // dummy read for tx cacheline reload
    busCtrl.readPrimitive(Bits(busCtrl.busDataWidth bits).setAll(), txMapping, 0, null)
    busCtrl.writeMemWordAligned(txPktBuffer, txOffset + 0xc0)
  }.setName("driveDcsBus")

  val logic = during build new Area {
    val ctrlInfo = PacketCtrlInfo()
    ctrlInfo.size := hostRxNext.payload.size
    val ctrlInfoStream = hostRxNext.translateWith(ctrlInfo)
    val dispatchIdx = UInt(1 bits) init 0

    val rxPacketCtrl = StreamDemux(ctrlInfoStream, dispatchIdx, 2)
    val rxNextAddr = RegNextWhen(hostRxNext.payload.addr, hostRxNext.valid)
    val rxTimeouts = Vec.fill(2)(Bool())

    val txPacketCtrl = Vec.fill(2)(Stream(PacketCtrlInfo()))
    txPacketCtrl.assignDontCare()

    val rxFsm = new StateMachine {

    }
    val txFsm = new StateMachine {

    }

    lci.setIdle()
    lcia.setBlocked()
    ul.setIdle()

    hostRxNextReq := False
    hostRxNext.setBlocked()
    hostRxNextAck.setIdle()
    hostTx.setBlocked()
    hostTxAck.setIdle()
  }
}
