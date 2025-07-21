package lauberhorn.net.oncrpc

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.net.{PacketDescData, ProtoDecoder, ProtoEncoder, ProtoMetadata}
import spinal.core._
import spinal.lib.bus.misc.BusSlaveFactory

// TODO: used for nested RPC replies (incoming)
class OncRpcReplyDecoder extends ProtoDecoder[OncRpcReplyMetadata] {
  override def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = ???
}


