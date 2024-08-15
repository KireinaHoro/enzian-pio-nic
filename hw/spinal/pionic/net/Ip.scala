package pionic.net

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.misc.BusSlaveFactory

// IPv4
case class IpHeader() extends Bundle {
  val version = Bits(4 bits)
  val ihl = Bits(4 bits)
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

case class IpMetadata()(implicit config: PioNicConfig) extends Bundle with ProtoMetadata {
  val hdr = IpHeader()
  val ethMeta = EthernetMetadata()

  def getType = ProtoMetadataType.ip
  def getPayloadSize: UInt = ethMeta.getPayloadSize - hdr.getBitsWidth / 8
  def collectHeaders: Bits = ethMeta.collectHeaders ## hdr.asBits
}

class IpDecoder(implicit config: PioNicConfig) extends ProtoDecoder[IpMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
    logic.decoder.io.statistics.flattenForeach { stat =>
      busCtrl.read(stat, alloc("ipStats", stat.getName()))
    }
    busCtrl.readAndWrite(logic.ipAddress, alloc("ipCtrl", "ipAddress"))
  }

  val logic = during setup new Area {
    val ethernetHeader = Stream(EthernetMetadata())
    val ethernetPayload = Axi4Stream(macIf.axisConfig)

    val ipAddress = Reg(Bits(32 bits)) init B("32'xc0_a8_80_28") // 192.168.128.40; changed at runtime

    from[EthernetMetadata, EthernetDecoder].apply(
      _.hdr.etherType === B("0800"),
      ethernetHeader, ethernetPayload
    )

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, IpHeader().getBitsWidth / 8) // IPv4 without options
    // TODO: chain output with secondary decoder to decode IP options

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(IpMetadata())
    val lastEthMeta = ethernetHeader.toReg()

    val drop = Bool()
    ethernetPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(drop)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = IpMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.ethMeta := lastEthMeta

      // TODO: verify header checksum
      drop := meta.hdr.daddr =/= ipAddress

      meta
    }

    connectConsumers(metadata, payload)
  }
}
