package lauberhorn.net.ethernet

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

    collectInto(md, pld)

    // TODO: use AxiStreamAddHeader to add header onto stream

    val rawMd = Stream(NoMetadata())
    val rawPld = Axi4Stream(ms.axisConfig)

    // send to raw
    to[NoMetadata, RawEncoder](rawMd, rawPld)
  }
}
