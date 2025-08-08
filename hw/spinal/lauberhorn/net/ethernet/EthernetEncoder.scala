package lauberhorn.net.ethernet

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.MacInterfaceService
import lauberhorn.net._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream

class EthernetEncoder extends ProtoEncoder[EthernetMetadata] {
  def getMetadata: EthernetMetadata = EthernetMetadata()

  lazy val ms = host[MacInterfaceService]

  val logic = during setup new Area {
    val md = Stream(EthernetMetadata())
    val pld = Axi4Stream(ms.axisConfig)

    awaitBuild()

    // allow host to send raw Ethernet frames (e.g. for ARP)
    collectInto(md, pld, acceptHostPackets = true)

    val encoder = AxiStreamInjectHeader(ms.axisConfig, EthernetHeader().getBitsWidth / 8)
    encoder.io.header << md.map(_.collectHeaders)
    encoder.io.input << pld
    ms.txStream << encoder.io.output
  }
}
