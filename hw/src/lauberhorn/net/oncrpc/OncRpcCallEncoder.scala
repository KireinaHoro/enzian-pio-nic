package lauberhorn.net.oncrpc

import lauberhorn.net._
import spinal.core._

case class OncRpcCallTxMeta() extends Bundle with EncoderMetadata {
  def getType: PacketDescType.E = PacketDescType.oncRpcCall
}

// TODO: for nested RPC calls (outgoing)
class OncRpcCallEncoder extends Encoder[OncRpcCallTxMeta] {
  def getMetadata = ???
}
