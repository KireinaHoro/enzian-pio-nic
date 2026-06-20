package lauberhorn.net.oncrpc

import lauberhorn.net._
import lauberhorn.Global.ONCRPC_NESTED_CALL_INLINE_BYTES
import lauberhorn.{PID, PacketLength, RpcID, TraceData}
import spinal.core._

case class OncRpcCallTxMeta() extends Bundle with EncoderMetadata {
  val rpcId = UInt(RpcID.width bits)
  val pid = PID()
  val cookie = Bits(32 bits)
  val xid = Bits(32 bits)
  val daddr = Bits(32 bits)
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val progNum = Bits(32 bits)
  val progVer = Bits(32 bits)
  val proc = Bits(32 bits)
  val data = Bits(ONCRPC_NESTED_CALL_INLINE_BYTES * 8 bits)

  /** Total length of the call argument payload, including inlined bytes. */
  val callLen = PacketLength()

  def getType: PacketDescType.E = PacketDescType.oncRpcCallTx
  override def traceData: Seq[TraceData] = Seq(RpcID(rpcId))
}

// TODO: for nested RPC calls (outgoing)
class OncRpcCallEncoder extends Encoder[OncRpcCallTxMeta] {
  def getMetadata = OncRpcCallTxMeta()
}
