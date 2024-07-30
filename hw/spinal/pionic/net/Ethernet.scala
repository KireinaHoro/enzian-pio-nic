package pionic.net

import pionic._
import spinal.core._
import spinal.lib.misc.pipeline._

case class EthernetMetadata()(implicit config: PioNicConfig) extends Bundle {
  val etherType = Bits(16 bits)
  // we use Ethernet II frame format, so packet length is calculated from AXI stream length
  // used to generate DMA descriptors for bypass packets
  val len = PacketLength()
}

class EthernetDecoder extends ProtoDecoder {
  lazy val macIf = host[MacInterfaceService]

  val logic = during build new Area {
    // when (macIf.rxStream.) {

    // }
  }
}