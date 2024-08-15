package pionic.net

import jsteward.blocks.axi.AxiStreamExtractHeader
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory

case class UdpHeader() extends Bundle {
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val len = Bits(16 bits)
  val csum = Bits(16 bits)
}

case class UdpMetadata()(implicit config: PioNicConfig) extends Bundle with ProtoMetadata {
  val hdr = UdpHeader()
  val ipMeta = IpMetadata()

  def getType = ProtoMetadataType.udp
  def getPayloadSize: UInt = ipMeta.getPayloadSize - hdr.getBitsWidth / 8
}

class UdpDecoder(implicit config: PioNicConfig) extends ProtoDecoder[UdpMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
    logic.decoder.io.statistics.flattenForeach { stat =>
      busCtrl.read(stat, alloc("udpStats", stat.getName()))
    }
  }

  val logic = during setup new Area {
    val ipHeader = Stream(IpMetadata())
    val ipPayload = Axi4Stream(macIf.axisConfig)

    from[IpMetadata, IpDecoder].apply(
      _.hdr.proto === B("11"), // 17 for UDP
      ipHeader, ipPayload
    )

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, UdpHeader().getBitsWidth / 8)

    val currentIpHeader = ipHeader.toReg()
    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(UdpMetadata())

    ipPayload >> decoder.io.input
    payload << decoder.io.output
    metadata << decoder.io.header.map { hdr =>
      val meta = UdpMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.ipMeta := currentIpHeader
      // TODO: drop packet based on checksum?

      meta
    }

    connectConsumers(metadata, payload)
  }
}
