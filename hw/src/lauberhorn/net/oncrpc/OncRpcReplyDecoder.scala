package lauberhorn.net.oncrpc

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global.{ONCRPC_INLINE_BYTES, PKT_BUF_LEN_WIDTH}
import lauberhorn.{PID, PacketID, RpcID, TraceData}
import lauberhorn.net.{PacketDescData, Decoder, DecoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib.bus.amba4.axilite.AxiLite4

import scala.language.postfixOps

case class OncRpcReplyRxMeta() extends Bundle with DecoderMetadata {
  override def clone = OncRpcReplyRxMeta()

  val packetId = UInt(PacketID.width bits)
  val rpcId = UInt(RpcID.width bits)
  val pid = PID()
  val cookie = Bits(32 bits)
  val xid = Bits(32 bits)
  val saddr = Bits(32 bits)
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)

  def getType: PacketDescType.E = PacketDescType.oncRpcReplyRx
  override def traceData: Seq[TraceData] = Seq(PacketID(packetId), RpcID(rpcId))

  def getPayloadSize: UInt = {
    val inlineLen = ONCRPC_INLINE_BYTES.get
    val payloadLen = udpPayloadSize - OncRpcReplyHeader().getBitsWidth / 8
    (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
  }

  def collectHeaders: Bits = ??? // never collected

  def asUnion: PacketDescData = {
    val ret = PacketDescData().assignDontCare()
    ret.oncRpcReplyRx.get := this
    ret
  }
}

// TODO: used for nested RPC replies (incoming)
class OncRpcReplyDecoder extends Decoder[OncRpcReplyRxMeta] {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = ???
}
