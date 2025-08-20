package lauberhorn

import jsteward.blocks.misc.sim._
import org.pcap4j.core.PcapDumper
import org.pcap4j.packet._
import org.pcap4j.packet.namednumber._
import org.pcap4j.util.MacAddress
import org.scalatest.Tag
import spinal.core.IntToBuilder

import java.net.{Inet4Address, InetAddress}
import scala.util.Random

package object sim {
  object Rx extends Tag("lauberhorn.sim.tags.Rx")
  object Tx extends Tag("lauberhorn.sim.tags.Tx")

  object PacketType extends Enumeration {
    type PacketType = Value
    val Ethernet, Ip, Udp, OncRpcCall, OncRpcReply = Value
  }
  import PacketType._

  /** construct a Pcap4j packet from a bypass RX descriptor */
  def typeToPcap4jClass(packetType: PacketType): Class[_ <: Packet] = packetType match {
    case Ethernet => classOf[EthernetPacket]
    case Ip => classOf[IpV4Packet]
    case Udp => classOf[UdpPacket]
    case OncRpcCall =>
      // TODO: write custom OncRpcCall packet class
      println("parsing bypass OncRpcCall not implemented yet")
      ???
    case _ =>
      assert(false, "unexpected packet type on bypass receive" + packetType)
      ???
  }


  def pcap4jPacketToType(packet: EthernetPacket): PacketType = {
    if (!packet.contains(classOf[IpV4Packet])) Ethernet
    else if (!packet.contains(classOf[UdpPacket])) Ip
    else Udp
  }

  def checkHeader(packetType: PacketType, expected: Packet, got: Packet): Unit = {
    def checkLevel(proto: PacketType) = {
      if (packetType >= proto) {
        val e = expected.get(typeToPcap4jClass(proto)).getHeader
        val p4jClass = typeToPcap4jClass(proto)
        assert(got.contains(p4jClass), s"$proto not in packet $got")
        val g = got.get(p4jClass).getHeader
        assert(e == g, s"$proto header mismatch: expected $e, got $g")
        println(s"$proto matched successfully: ${g.toString}")
      }
    }
    Seq(Ethernet, Ip, Udp) foreach checkLevel
  }

  def randomExclude(lowerInclusive: Int, upperInclusive: Int)(excludes: Int*): Int = {
    var attempt = Random.between(lowerInclusive, upperInclusive)
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
      .tos(IpV4Rfc1349Tos.newInstance(0))
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

  def oncRpcCallPacket(sport: Int, dport: Int, prog: Int, progVer: Int, procNum: Int, payload: List[Byte], xid: Int) = {
    // construct header
    println(f"XID $xid%#x prog $prog%#x progVer $progVer%#x procNum $procNum%#x")

    udpPacket(sport, dport,
      rawPayloadBuilder(
        Seq(
          xid,
          0, // message type: Call (0)
          2, // rpc version
          prog,
          progVer,
          procNum,
        ).flatMap(intToBytesBE(_)).toArray
          // 16B credentials + verifier
          ++ Array.fill(16)(0.toByte)
          ++ payload
      )
    )
  }

  def rawPayloadBuilder(payload: Array[Byte]) = (new UnknownPacket.Builder).rawData(payload)

  def randomPacket(mtu: Int, randomizeLen: Boolean = true)(protocols: PacketType*): (Packet, PacketType) = {
    val proto = choose(protocols.iterator, Random)
    var payloadLen: Int = 0

    def randomPayloadLen(headerLen: Int) = {
      val totalLen = if (randomizeLen) Random.nextInt(mtu) else mtu
      if (totalLen < headerLen) 0
      else totalLen - headerLen
    }

    def builder() = rawPayloadBuilder(Random.nextBytes(payloadLen))

    val ret = proto match {
      case Ethernet =>
        payloadLen = randomPayloadLen(14)
        ethernetPacket(
          EtherType.getInstance(
            // we want random EtherType values, not 802.3 Length
            // exclude IP (0x0800)
            randomExclude(EtherType.IEEE802_3_MAX_LENGTH + 1, 0xFFFF)(0x0800).toShort),
          builder())

      case Ip =>
        payloadLen = randomPayloadLen(14 + 20) // no IP extensions
        ipPacket(
          IpNumber.getInstance(randomExclude(0, 255)(6, 17).toByte),
          builder())

      case Udp =>
        payloadLen = randomPayloadLen(14 + 20 + 8)
        udpPacket(
          Random.nextInt(65536),
          Random.nextInt(65536),
          builder())
    }

    println(s"prepared $proto packet with payload len $payloadLen, mtu is $mtu")

    (ret, proto)
  }

  def getPayloadAndCheckLen(packet: Packet, proto: PacketType, descLen: Int) = {
    val payload = packet.get(typeToPcap4jClass(proto)).getPayload.getRawData.toList
    // check payload length
    // Ethernet enforces a 64 B frame => 14 B hdr + 46 B payload + 4 B FCS.  Smaller packets will be padded
    val minDescLen = proto match {
      case Ethernet => 46
      case Ip => 26
      case Udp => 18
      case _ => 0
    }
    val expectedLen = if (payload.length < minDescLen) minDescLen else payload.length
    assert(descLen == expectedLen, s"packet length mismatch: expected $expectedLen, got $descLen")
    payload
  }

  def getIpPacket(srcIpAddr: Inet4Address, dstIpAddr: Inet4Address, srcMacAddr: MacAddress, dstMacAddr: MacAddress, pldLen: Int)
                 (implicit dumper: PcapDumper) = {
    val ipBuilder = (new IpV4Packet.Builder)
      .version(IpVersion.IPV4)
      .protocol(IpNumber.getInstance(randomExclude(0, 255)(6, 17).toByte))
      .tos(IpV4Rfc1349Tos.newInstance(0))
      .ttl(Random.nextInt().toByte)
      .srcAddr(srcIpAddr)
      .dstAddr(dstIpAddr)
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(rawPayloadBuilder(Random.nextBytes(pldLen)))

    val ret = (new EthernetPacket.Builder)
      .srcAddr(srcMacAddr)
      .dstAddr(dstMacAddr)
      .`type`(EtherType.IPV4)
      .paddingAtBuild(true)
      .payloadBuilder(ipBuilder)
      .build()

    dumper.dump(ret)
    dumper.flush()

    ret
  }

  def enzianIpMacAddrs(hostNum: Int) = {
    val hostId = hostNum * 32 + 8
    val ipAddr = InetAddress.getByName(s"192.168.128.$hostId").asInstanceOf[Inet4Address]
    val macAddr = MacAddress.getByName(f"0c:53:31:03:00:$hostId%x")

    (ipAddr, macAddr)
  }

  def getIpPacketToEnzian(hostNum: Int, pldLen: Int)(implicit dumper: PcapDumper) = {
    val srcIpAddr = InetAddress.getByAddress(Random.nextBytes(4)).asInstanceOf[Inet4Address]
    val srcMacAddr = MacAddress.getByAddress(Random.nextBytes(6))
    val (dstIpAddr, dstMacAddr) = enzianIpMacAddrs(hostNum)

    getIpPacket(srcIpAddr, dstIpAddr, srcMacAddr, dstMacAddr, pldLen)
  }

  def getIpPacketFromEnzian(hostNum: Int, pldLen: Int)(implicit dumper: PcapDumper) = {
    val dstIpAddr = InetAddress.getByAddress(Random.nextBytes(4)).asInstanceOf[Inet4Address]
    val dstMacAddr = MacAddress.getByAddress(Random.nextBytes(6))
    val (srcIpAddr, srcMacAddr) = enzianIpMacAddrs(hostNum)

    getIpPacket(srcIpAddr, dstIpAddr, srcMacAddr, dstMacAddr, pldLen)
  }
}
