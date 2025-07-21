package lauberhorn.net

import lauberhorn.MacInterfaceService
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream

case class NoMetadata() extends Bundle with ProtoMetadata {
  def getType = PacketDescType.raw
  def getPayloadSize: UInt = U(0)
  def collectHeaders: Bits = B(0)
  def asUnion = {
    val ret = PacketDescData().assignDontCare()
    ret
  }
}

class RawEncoder extends ProtoEncoder[NoMetadata] {
  def getMetadata = NoMetadata()
  lazy val ms = host[MacInterfaceService]
  val logic = during setup new Area {
    val md = Stream(NoMetadata())
    val pld = Axi4Stream(ms.axisConfig)

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true)

    // we discard the dummy metadata
    md.ready := True
    ms.txStream << pld
  }
}
