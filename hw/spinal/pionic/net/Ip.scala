package pionic.net

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.misc.BusSlaveFactory

import scala.language.postfixOps

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

case class IpMetadata()(implicit c: ConfigDatabase) extends Bundle with ProtoPacketDesc {
  override def clone = IpMetadata()

  val hdr = IpHeader()
  val ethMeta = EthernetMetadata()

  def getType = ProtoPacketDescType.ip
  def getPayloadSize: UInt = ethMeta.getPayloadSize - hdr.getBitsWidth / 8
  def collectHeaders: Bits = hdr.asBits ## ethMeta.collectHeaders
  def asUnion: ProtoPacketDescData = {
    val ret = ProtoPacketDescData().assignDontCare()
    ret.ip.get := this
    ret
  }
}

class IpDecoder extends ProtoDecoder[IpMetadata] {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin].logic

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

    from[EthernetMetadata, EthernetDecoder](
      _.hdr.etherType === EndiannessSwap(B("16'x0800")),
      ethernetHeader, ethernetPayload
    )

    awaitBuild()

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(IpMetadata())
    produce(metadata, payload)
    produceDone()

    val decoder = AxiStreamExtractHeader(macIf.axisConfig, IpHeader().getBitsWidth / 8)() // IPv4 without options
    // TODO: chain output with secondary decoder to decode IP options

    val lastEthMeta = ethernetHeader.toReg()

    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop
    ethernetPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = IpMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.ethMeta := lastEthMeta

      // TODO: verify header checksum, version, etc.
      drop := meta.hdr.daddr =/= EndiannessSwap(ipAddress) && !csr.ctrl.promisc

      meta
    }
  }
}
