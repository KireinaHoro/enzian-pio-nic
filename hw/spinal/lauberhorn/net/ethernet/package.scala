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

  case class EthernetMetadata() extends Bundle with ProtoMetadata {
    override def clone = EthernetMetadata()

    val frameLen = PacketLength()
    val hdr = EthernetHeader()

    def getType = PacketDescType.ethernet
    def getPayloadSize: UInt = frameLen.bits - hdr.getBitsWidth / 8
    def collectHeaders: Bits = hdr.asBits
    def assignFromHdrBits(b: Bits): Unit = {
      hdr.assignFromBits(b(hdr.getBitsWidth-1 downto 0))
    }
    def asUnion: PacketDescData = {
      val ret = PacketDescData().assignDontCare()
      ret.ethernet.get := this
      ret
    }
  }
}
