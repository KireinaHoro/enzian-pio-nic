package pionic

import org.pcap4j.packet._
import jsteward.blocks.misc.sim._
import org.pcap4j.packet.namednumber._
import org.pcap4j.util.MacAddress

import java.net.Inet4Address
import scala.util.Random

package object sim {
  sealed trait PacketType
  case object Ethernet extends PacketType
  case object Ip extends PacketType
  case object Udp extends PacketType

  def randomExclude(n: Int)(excludes: Int*): Int = {
    var attempt = Random.nextInt(n)
    for (e <- excludes.sorted) {
      if (attempt < e) {
        return attempt
      } else {
        attempt += 1
      }
    }
    attempt
  }

  def ethernetPacket(ty: EtherType, payloadBuilder: Packet.Builder) =
    (new EthernetPacket.Builder)
      .srcAddr(MacAddress.getByAddress(Random.nextBytes(6)))
      .dstAddr(MacAddress.getByAddress(Random.nextBytes(6)))
      .`type`(ty)
      .payloadBuilder(payloadBuilder)
      .build()

  def ipPacket(ty: IpNumber, payloadBuilder: Packet.Builder) = {
    val ipBuilder = (new IpV4Packet.Builder)
      .version(IpVersion.IPV4)
      .srcAddr(new Inet4Address("src", Random.nextBytes(4)))
      .dstAddr(new Inet4Address("dst", Random.nextBytes(4)))
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .protocol(ty)
      .payloadBuilder(payloadBuilder)

    ethernetPacket(EtherType.IPV4, ipBuilder)
  }

  def udpPacket(sport: Int, dport: Int, payloadBuilder: Packet.Builder) = {
    // we ignore UDP checksum here
    val udpBuilder = (new UdpPacket.Builder)
      .srcPort(UdpPort.getInstance(sport.toShort))
      .dstPort(UdpPort.getInstance(dport.toShort))
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(payloadBuilder)

    ipPacket(IpNumber.UDP, udpBuilder)
  }


  def randomPacket(mtu: Int)(protocols: PacketType*): Packet = {
    val proto = choose(protocols.iterator, Random)
    var payloadLen: Int = 0

    def randomPayloadLen(headerLen: Int) = {
      val totalLen = Random.nextInt(mtu)
      if (totalLen < headerLen) 0
      else totalLen - headerLen
    }

    def rawPayloadBuilder() = (new UnknownPacket.Builder).rawData(Random.nextBytes(payloadLen))

    val ret = proto match {
      case Ethernet =>
        payloadLen = randomPayloadLen(14)
        ethernetPacket(
          EtherType.getInstance(randomExclude(0x10000)(0x0800).toShort),
          rawPayloadBuilder())

      case Ip =>
        payloadLen = randomPayloadLen(14 + 20) // no IP extensions
        ipPacket(
          IpNumber.getInstance(randomExclude(256)(6, 17).toByte),
          rawPayloadBuilder())

      case Udp =>
        payloadLen = randomPayloadLen(14 + 20 + 8)
        udpPacket(
          Random.nextInt(65536),
          Random.nextInt(65536),
          rawPayloadBuilder())
    }

    println(s"prepared $proto packet with payload len $payloadLen")

    ret
  }
}
