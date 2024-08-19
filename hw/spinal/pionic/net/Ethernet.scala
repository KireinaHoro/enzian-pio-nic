package pionic.net

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory

case class EthernetHeader() extends Bundle {
  val dst = Bits(48 bits)
  val src = Bits(48 bits)
  val etherType = Bits(16 bits)
}

case class EthernetMetadata()(implicit c: ConfigDatabase) extends Bundle with ProtoPacketDesc {
  override def clone = EthernetMetadata()

  val frameLen = PacketLength()
  val hdr = EthernetHeader()

  def getType = ProtoPacketDescType.ethernet
  def getPayloadSize: UInt = frameLen.bits - hdr.getBitsWidth / 8
  def collectHeaders: Bits = hdr.asBits
  def asUnion: ProtoPacketDescData = {
    val ret = ProtoPacketDescData().assignDontCare()
    ret.ethernet.get := this
    ret
  }
}

class EthernetDecoder extends ProtoDecoder[EthernetMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
    logic.decoder.io.statistics.flattenForeach { stat =>
      busCtrl.read(stat, alloc("ethernetStats", stat.getName()))
    }
    busCtrl.readAndWrite(logic.macAddress, alloc("ethernetCtrl", "macAddress"))
  }

  val logic = during setup new Area {
    private val payload = Axi4Stream(macIf.axisConfig)
    private val metadata = Stream(EthernetMetadata())

    val macAddress = Reg(Bits(48 bits)) init B("48'x0C_53_31_03_00_28") // zuestoll01 FPGA; changed at runtime

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, EthernetHeader().getBitsWidth / 8)
    decoder.io.input << macIf.rxStream

    // extract frame length
    // FIXME: backpressure so that overflow reporting actually works
    val lastFrameLen = macIf.frameLen.toReg()

    val drop = Bool()
    payload << decoder.io.output.throwFrameWhen(drop)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = EthernetMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.frameLen := lastFrameLen

      // allow unicast and broadcast, if promisc mode is not on
      // TODO: multicast?
      drop := macAddress =/= meta.hdr.dst

      meta
    }

    produce(metadata, payload)
    produceDone()
  }
}