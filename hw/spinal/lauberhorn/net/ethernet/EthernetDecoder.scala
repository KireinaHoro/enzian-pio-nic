package lauberhorn.net.ethernet

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn._
import lauberhorn.net.Decoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

class EthernetDecoder extends Decoder[EthernetRxMeta] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", name, attr = RO))
    }
    busCtrl.readAndWrite(logic.macAddress, alloc("ctrl", "macAddress"))
  }

  val logic = during setup new Area {
    private val payload = Axi4Stream(macIf.axisConfig)
    private val metadata = Stream(EthernetRxMeta())

    // zuestoll01 FPGA MAC address: 0C:53:31:03:00:28
    // stored as Big Endian
    val macAddress = Reg(Bits(48 bits)) init EndiannessSwap(B("48'x0C_53_31_03_00_28"))

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, EthernetHeader().getBitsWidth / 8)()
    decoder.io.input << macIf.rxStream

    // TODO: dropped packets counter
    val drop = Bool()
    payload << decoder.io.output.throwFrameWhen(drop && decoder.io.header.fire)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      new Composite(this, "remap") {
        val meta = EthernetRxMeta()
        meta.hdr.assignFromBits(hdr)
        meta.frameLen := macIf.frameLen

        // allow unicast and broadcast
        // TODO: multicast?
        val isBroadcast = meta.hdr.dst.andR
        drop := macAddress =/= meta.hdr.dst && !isBroadcast && !isPromisc
      }.meta
    }

    // frameLen.valid must be high when we have a metadata fire
    macIf.frameLen.ready := metadata.fire

    produce(metadata, payload)
    produceDone()
  }
}
