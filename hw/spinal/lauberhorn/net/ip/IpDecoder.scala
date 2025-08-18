package lauberhorn.net.ip

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn._
import lauberhorn.net.ethernet.{EthernetDecoder, EthernetMetadata}
import lauberhorn.net.ProtoDecoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

class IpDecoder extends ProtoDecoder[IpMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", name, attr = RO))
    }

    val littleEndianIp = B("32'x0")
    busCtrl.readAndWrite(littleEndianIp, alloc("ctrl", "ipAddress"))
    logic.ipAddress := EndiannessSwap(littleEndianIp)
  }

  val logic = during setup new Area {
    val ethernetHeader = Stream(EthernetMetadata())
    val ethernetPayload = Axi4Stream(macIf.axisConfig)

    val ipAddress = Reg(Bits(32 bits)) init EndiannessSwap(B("32'xc0_a8_80_28")) // 192.168.128.40; changed at runtime

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

    val lastEthMeta = ethernetHeader.toFlowFire.toReg()
    ethernetHeader.ready.setAsReg().init(True)
      .clearWhen(ethernetHeader.fire)
      .setWhen(decoder.io.header.fire)

    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop
    ethernetPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = IpMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.ethMeta := lastEthMeta

      // TODO: verify header checksum, version, etc.
      drop := meta.hdr.daddr =/= ipAddress && !isPromisc

      meta
    }
  }
}
