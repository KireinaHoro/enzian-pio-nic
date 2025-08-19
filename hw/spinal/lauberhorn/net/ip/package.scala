package lauberhorn.net

import lauberhorn.net.ethernet.EthernetRxMeta
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

  case class IpRxMeta() extends Bundle with DecoderMetadata {
    override def clone = IpRxMeta()

    val hdr = IpHeader()
    val ethMeta = EthernetRxMeta()

    def getType = PacketDescType.ip
    def getPayloadSize: UInt = ethMeta.getPayloadSize - hdr.getBitsWidth / 8
    def collectHeaders: Bits = hdr.asBits ## ethMeta.collectHeaders
    def asUnion: PacketDescData = {
      val ret = PacketDescData().assignDontCare()
      ret.ipRx.get := this
      ret
    }
  }
}
