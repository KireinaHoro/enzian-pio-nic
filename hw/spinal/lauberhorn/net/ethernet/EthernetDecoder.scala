package lauberhorn.net.ethernet

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn._
import lauberhorn.net.ProtoDecoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

class EthernetDecoder extends ProtoDecoder[EthernetMetadata] {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin].logic

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("ethernetStats", name, attr = RO))
    }
    busCtrl.readAndWrite(logic.macAddress, alloc("ethernetCtrl", "macAddress"))
  }

  val logic = during setup new Area {
    private val payload = Axi4Stream(macIf.axisConfig)
    private val metadata = Stream(EthernetMetadata())

    val macAddress = Reg(Bits(48 bits)) init B("48'x0C_53_31_03_00_28") // zuestoll01 FPGA; changed at runtime

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, EthernetHeader().getBitsWidth / 8)()
    decoder.io.input << macIf.rxStream

    // TODO: dropped packets counter
    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      new Composite(this, "remap") {
        val meta = EthernetMetadata()
        meta.hdr.assignFromBits(hdr)
        meta.frameLen := macIf.frameLen

        // allow unicast and broadcast
        // TODO: multicast?
        val isBroadcast = meta.hdr.dst.andR
        drop := (EndiannessSwap(macAddress) =/= meta.hdr.dst || !isBroadcast) && !csr.ctrl.promisc
      }.meta
    }

    // frameLen.valid must be high when we have a metadata fire
    macIf.frameLen.ready := metadata.fire

    produce(metadata, payload)
    produceDone()
  }
}