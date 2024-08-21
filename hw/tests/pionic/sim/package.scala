package pionic

import org.pcap4j.packet._
import jsteward.blocks.misc.sim._
import org.pcap4j.packet.namednumber._
import org.pcap4j.util.MacAddress

import java.net.{Inet4Address, InetAddress}
import scala.util.Random

package object sim {
  object PacketType extends Enumeration {
    type PacketType = Value
    val Ethernet, Ip, Udp, OncRpcCall = Value
  }
  import PacketType._

  def typeToPcap4jClass(packetType: PacketType): Class[_ <: Packet] = packetType match {
    case Ethernet => classOf[EthernetPacket]
    case Ip => classOf[IpV4Packet]
    case Udp => classOf[UdpPacket]
    // TODO: write custom OncRpcCall packet class
    // case OncRpcCall =>
  }

  def checkHeader(packetType: PacketType, expected: Packet, got: Packet): Unit = {
    def checkLevel(proto: PacketType) = {
      if (packetType >= proto) {
        val e = expected.get(typeToPcap4jClass(proto)).getHeader
        val g = got.get(typeToPcap4jClass(proto)).getHeader
        assert(e == g, s"$proto header mismatch: expected $e, got $g")
      }
    }
    Seq(Ethernet, Ip, Udp) foreach checkLevel
  }

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
      .paddingAtBuild(true)
      .payloadBuilder(payloadBuilder)
      .build()

  def ipPacket(ty: IpNumber, payloadBuilder: Packet.Builder) = {
    val srcAddr = InetAddress.getByAddress(Random.nextBytes(4)).asInstanceOf[Inet4Address]
    val dstAddr = InetAddress.getByAddress(Random.nextBytes(4)).asInstanceOf[Inet4Address]
    payloadBuilder match {
      case udpBuilder: UdpPacket.Builder =>
        udpBuilder
          .srcAddr(srcAddr)
          .dstAddr(dstAddr)
      case _ =>
    }

    val ipBuilder = (new IpV4Packet.Builder)
      .version(IpVersion.IPV4)
      .protocol(ty)
      .tos(IpV4Rfc791Tos.newInstance(0))
      .ttl(Random.nextInt().toByte)
      .srcAddr(srcAddr)
      .dstAddr(dstAddr)
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(payloadBuilder)

    ethernetPacket(EtherType.IPV4, ipBuilder)
  }

  def udpPacket(sport: Int, dport: Int, payloadBuilder: Packet.Builder) = {
    val udpBuilder = (new UdpPacket.Builder)
      .srcPort(UdpPort.getInstance(sport.toShort))
      .dstPort(UdpPort.getInstance(dport.toShort))
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(payloadBuilder)

    ipPacket(IpNumber.UDP, udpBuilder)
  }


  def randomPacket(mtu: Int)(protocols: PacketType*): (Packet, PacketType) = {
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

    println(s"prepared $proto packet with payload len $payloadLen, mtu is $mtu")

    (ret, proto)
  }
}
