package lauberhorn.net

import lauberhorn.net.ethernet.EthernetMetadata
import spinal.core._

import scala.language.postfixOps

package object ip {
  // IPv4
  case class IpHeader() extends Bundle {
    val ihl = Bits(4 bits)
    val version = Bits(4 bits)
    val tos = Bits(8 bits)
    val len = Bits(16 bits)
    val id = Bits(16 bits)
    val flags = Bits(3 bits)
    val offset = Bits(13 bits)
    val ttl = Bits(8 bits)
    val proto = Bits(8 bits)
    val csum = Bits(16 bits)
    val saddr = Bits(32 bits)
    val daddr = Bits(32 bits)
  }

  case class IpMetadata() extends Bundle with ProtoMetadata {
    override def clone = IpMetadata()

    val hdr = IpHeader()
    val ethMeta = EthernetMetadata()

    def getType = PacketDescType.ip
    def getPayloadSize: UInt = ethMeta.getPayloadSize - hdr.getBitsWidth / 8
    def collectHeaders: Bits = hdr.asBits ## ethMeta.collectHeaders
    def assignFromHdrBits(b: Bits): Unit = {
      ethMeta.assignFromHdrBits(b)
      hdr.assignFromBits(
        b(hdr.getBitsWidth-1 + ethMeta.hdr.getBitsWidth downto ethMeta.hdr.getBitsWidth))
    }
    def asUnion: PacketDescData = {
      val ret = PacketDescData().assignDontCare()
      ret.ip.get := this
      ret
    }
  }
}
