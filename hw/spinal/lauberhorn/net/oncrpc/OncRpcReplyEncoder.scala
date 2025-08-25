package lauberhorn.net.oncrpc

import lauberhorn.net.{Encoder, EncoderMetadata, PacketDescType}
import spinal.core._

case class OncRpcReplyTxMeta() extends Bundle with EncoderMetadata {
  def getType = PacketDescType.oncRpcReply
}

class OncRpcReplyEncoder extends Encoder[OncRpcReplyTxMeta] {
  def getMetadata = OncRpcReplyTxMeta()
}
