package pionic.host.pcie

import pionic._
import pionic.net.ProtoPacketDescType
import spinal.core._

/**
  * Control info struct sent to the CPU in RX descriptor load through hostRx,
  * as well as struct received from the CPU for TX through hostTxAck.  Same
  * principle as [[pionic.host.eci.EciHostCtrlInfo]].
  */
// TODO: reduce redundant code
case class PcieHostCtrlInfo()(implicit c: ConfigDatabase) extends Bundle {
  override def clone: PcieHostCtrlInfo = PcieHostCtrlInfo()

  // reserve one bit from valid in readStream
  val buffer = PacketBufDesc() // 24 + 16b
  val ty = HostPacketDescType() // 2b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = ProtoPacketDescType() // 2b
      val xb19 = Bits(19 bits) // make sure header is word aligned
      val hdr = Bits(Widths.bphw bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcCallBundle() extends Bundle {
      val xb21 = Bits(21 bits)
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val args = Bits(Widths.oargw bits)
    }
    val oncRpcCall = newElement(OncRpcCallBundle())
  }

  def unpackTo(desc: HostPacketDesc) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostPacketDescType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
      }
      is (HostPacketDescType.oncRpcCall) {
        desc.data.oncRpcCall.assignSomeByName(data.oncRpcCall)
      }
    }
    desc.buffer := buffer
  }

  def addMackerel = ???
}

object PcieHostCtrlInfo {
  def packFrom(desc: HostPacketDesc)(implicit c: ConfigDatabase) = new Area {
    val ret = PcieHostCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostPacketDescType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb19 := 0
      }
      is (HostPacketDescType.oncRpcCall) {
        ret.data.oncRpcCall.assignSomeByName(desc.data.oncRpcCall)
        ret.data.oncRpcCall.xb21 := 0
      }
    }
    ret.buffer := desc.buffer
  }.ret
}
