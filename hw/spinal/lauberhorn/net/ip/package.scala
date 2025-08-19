package lauberhorn.net

import lauberhorn.net.ethernet.EthernetRxMeta
import spinal.core._
import spinal.lib._

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

    def setCsum(): Unit = {
      val hdrCsumCleared = IpHeader()
      hdrCsumCleared := this
      hdrCsumCleared.csum.allowOverride()
      hdrCsumCleared.csum := 0

      val words = hdrCsumCleared.asBits.subdivideIn(16 bits).map(_.asUInt)
      val sum = words.reduceBalancedTree { (a, b) =>
        val noOverflow = a +^ b
        val overflow = a +^ b + 1
        noOverflow.msb.mux(overflow, noOverflow).dropHigh(1).asUInt
      }

      csum := ~sum.asBits
    }
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

  case class IpTxMeta() extends Bundle with EncoderMetadata {
    val daddr = Bits(32 bits)
    val pldLen = UInt(16 bits) // without IP header!
    val proto = Bits(8 bits)

    def getType = PacketDescType.ip
  }

  case class IpNeighborDef() extends Bundle {
    val ipAddr = Bits(32 bits)
    val macAddr = Bits(48 bits)
    val valid = Bool()
  }
}
