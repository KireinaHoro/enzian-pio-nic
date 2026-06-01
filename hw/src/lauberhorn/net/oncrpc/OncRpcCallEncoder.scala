package lauberhorn.net.oncrpc

import lauberhorn.net._
import lauberhorn.{RpcID, TraceData}
import spinal.core._

case class OncRpcCallTxMeta() extends Bundle with EncoderMetadata {
  val rpcId = UInt(RpcID.width bits)

  def getType: PacketDescType.E = PacketDescType.oncRpcCall
  override def traceData: Seq[TraceData] = Seq(RpcID(rpcId))
}

// TODO: for nested RPC calls (outgoing)
class OncRpcCallEncoder extends Encoder[OncRpcCallTxMeta] {
  def getMetadata = ???
}
