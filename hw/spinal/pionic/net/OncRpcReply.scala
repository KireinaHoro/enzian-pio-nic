package pionic.net

import jsteward.blocks.misc.RegBlockAlloc
import pionic.ConfigDatabase
import spinal.core._
import spinal.lib.bus.misc.BusSlaveFactory

case class OncRpcReplyMetadata()(implicit c: ConfigDatabase) extends Bundle with ProtoMetadata {
  def getType = ???

  def getPayloadSize: UInt = ???

  def collectHeaders: Bits = ???

  def asUnion: PacketDescData = ???
}

// TODO: used for nested RPC replies (incoming)
class OncRpcReplyDecoder extends ProtoDecoder[OncRpcReplyMetadata] {
  override def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = ???
}

class OncRpcReplyEncoder extends ProtoEncoder[OncRpcReplyMetadata] {

}
