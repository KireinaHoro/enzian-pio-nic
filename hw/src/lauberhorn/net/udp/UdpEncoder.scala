package lauberhorn.net.udp

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.{CoreID, HostMsgID, LauberhornTraceDma, MacInterfaceService, PacketID, RpcID, TracePlugin}
import lauberhorn.net.ip.{IpEncoder, IpTxMeta}
import lauberhorn.net.{Encoder, EncoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

case class UdpTxMeta() extends Bundle with EncoderMetadata {
  val rpcId = UInt(RpcID.width bits)
  val native = Bool()
  val hostMsgId = UInt(HostMsgID.width bits)
  val coreId = UInt(CoreID.width bits)
  val daddr = Bits(32 bits)
  val dport = Bits(16 bits)
  val sport = Bits(16 bits)
  val pldLen = UInt(16 bits) // without UDP header!

  def getType = PacketDescType.udp
}

case class UdpTaggedTxMeta() extends Bundle {
  val packetId = UInt(PacketID.width bits)
  val udp = UdpTxMeta()
}

class UdpEncoder extends Encoder[UdpTxMeta] {
  def getMetadata: UdpTxMeta = UdpTxMeta()

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val nativeTp = during setup host[TracePlugin].makePort("udp_host_packet", LauberhornTraceDma.NicDecoderSlr)

  val logic = during setup new Area {
    val md = Stream(UdpTxMeta())
    val pld = Axi4Stream(axisConfig)
    val nextPacketId = Reg(UInt(PacketID.width bits)) init 0

    val outMd = Stream(IpTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[IpTxMeta, IpEncoder](outMd, outPld)

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true, payloadBytes = _.pldLen)

    val forkedEmpty = Bool()
    val encoder = lauberhorn.net.oncrpc.RpcHeaderInjector(axisConfig, UdpHeader().getBitsWidth / 8)
    encoder.io.empty := forkedEmpty
    encoder.io.input << pld
    encoder.io.output >> outPld

    val taggedMd = Stream(UdpTaggedTxMeta())
    taggedMd.translateFrom(md) { case (tagged, input) =>
      tagged.packetId := nextPacketId
      tagged.udp := input
    }
    nativeTp.trace("UdpHostPacket", PacketID(taggedMd.packetId), HostMsgID(taggedMd.udp.hostMsgId), CoreID(taggedMd.udp.coreId)) :=
      taggedMd.fire && taggedMd.udp.native
    when (taggedMd.fire) {
      nextPacketId := nextPacketId + 1
    }

    val forkedCmds = StreamFork(taggedMd, 2)
    forkedEmpty := forkedCmds(0).udp.pldLen === 0
    forkedCmds(0).translateInto(encoder.io.header) { case (h, md) =>
      val hdr = UdpHeader()
      // assumes upstream always passes port in big endian
      hdr.sport := md.udp.sport
      hdr.dport := md.udp.dport
      hdr.len := EndiannessSwap(md.udp.pldLen + 8).asBits
      hdr.csum := 0 // XXX: not calculating checksum as it's optional

      h := hdr.asBits
    }

    forkedCmds(1).translateInto(outMd) { case (ipMd, udpCmd) =>
      ipMd.packetId := udpCmd.packetId
      ipMd.rpcId := udpCmd.udp.rpcId
      ipMd.daddr := udpCmd.udp.daddr
      ipMd.pldLen := udpCmd.udp.pldLen + 8
      ipMd.proto := 17
    }
  }
}
