package lauberhorn.net.oncrpc

import lauberhorn.Global.{ONCRPC_INLINE_BYTES, PKT_BUF_LEN_WIDTH}
import lauberhorn.PID
import lauberhorn.net.{DecoderMetadata, PacketDescData, PacketDescType}
import spinal.core._

import scala.language.postfixOps

case class OncRpcCallRxMeta() extends Bundle with DecoderMetadata {
  override def clone = OncRpcCallRxMeta()

  val funcPtr = Bits(64 bits)
  val pid = PID()
  // first fields in the XDR payload
  val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  val hdr = OncRpcCallHeader()
  val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)

  def getType = PacketDescType.oncRpcCall

  def getPayloadSize: UInt = {
    val inlineLen = ONCRPC_INLINE_BYTES.get
    val payloadLen = udpPayloadSize - hdr.getBitsWidth / 8
    (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
  }

  def collectHeaders: Bits = ??? // never collected

  def asUnion: PacketDescData = {
    val ret = PacketDescData() setCompositeName(this, "union")
    ret.oncRpcCall.get := this
    ret
  }
}