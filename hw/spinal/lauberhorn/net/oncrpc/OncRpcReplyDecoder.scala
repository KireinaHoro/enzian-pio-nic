package lauberhorn.net.oncrpc

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.net.{PacketDescData, Decoder, Encoder, DecoderMetadata}
import spinal.core._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.misc.BusSlaveFactory

/*
// TODO: used for nested RPC replies (incoming)
class OncRpcReplyDecoder extends Decoder[OncRpcReplyRxMeta] {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = ???
}
 */

