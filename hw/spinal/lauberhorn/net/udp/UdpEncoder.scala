package lauberhorn.net.udp

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.MacInterfaceService
import lauberhorn.net.ip.{IpEncoder, IpTxMeta}
import lauberhorn.net.{Encoder, EncoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

case class UdpTxMeta() extends Bundle with EncoderMetadata {
  val daddr = Bits(32 bits)
  val dport = Bits(16 bits)
  val sport = Bits(16 bits)
  val pldLen = UInt(16 bits) // without UDP header!

  def getType = PacketDescType.udp
}

class UdpEncoder extends Encoder[UdpTxMeta] {
  def getMetadata: UdpTxMeta = UdpTxMeta()

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(UdpTxMeta())
    val pld = Axi4Stream(axisConfig)

    val outMd = Stream(IpTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[IpTxMeta, IpEncoder](outMd, outPld)

    awaitBuild()

    collectInto(md, pld)

    val encoder = AxiStreamInjectHeader(axisConfig, UdpHeader().getBitsWidth / 8)
    encoder.io.input << pld
    encoder.io.output >> outPld

    val forkedCmds = StreamFork(md, 2)
    forkedCmds(0).translateInto(encoder.io.header) { case (h, md) =>
      val hdr = UdpHeader()
      // assumes upstream always passes port in big endian
      hdr.sport := md.sport
      hdr.dport := md.dport
      hdr.len := EndiannessSwap(md.pldLen + 8).asBits
      hdr.csum := 0 // XXX: not calculating checksum as it's optional

      h := hdr.asBits
    }

    forkedCmds(1).translateInto(outMd) { case (ipMd, udpCmd) =>
      ipMd.daddr := udpCmd.daddr
      ipMd.pldLen := udpCmd.pldLen + 8
      ipMd.proto := 17
    }
  }
}
