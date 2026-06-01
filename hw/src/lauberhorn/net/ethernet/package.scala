package lauberhorn.net

import spinal.core._

import lauberhorn._

import scala.language.postfixOps

package object ethernet {
  case class EthernetHeader() extends Bundle {
    val dst = Bits(48 bits)
    val src = Bits(48 bits)
    val etherType = Bits(16 bits)
  }

  case class EthernetRxMeta() extends Bundle with DecoderMetadata {
    override def clone = EthernetRxMeta()

    val packetId = UInt(PacketID.width bits)
    val frameLen = PacketLength()
    val hdr = EthernetHeader()

    def getType = PacketDescType.ethernet
    def getPayloadSize: UInt = frameLen.bits - hdr.getBitsWidth / 8
    def collectHeaders: Bits = hdr.asBits
    def asUnion: PacketDescData = {
      val ret = PacketDescData().assignDontCare()
      ret.ethernetRx.get := this
      ret
    }
  }

  case class EthernetTxMeta() extends Bundle with EncoderMetadata {
    val packetId = UInt(PacketID.width bits)
    val dst = Bits(48 bits)
    val etherType = Bits(16 bits)

    def getType = PacketDescType.ethernet
    override def traceData: Seq[TraceData] = Seq(PacketID(packetId))
  }
}
