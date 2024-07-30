package pionic.net

import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.misc.plugin._

case class DecodedDesc()(implicit config: PioNicConfig) extends Bundle {
  val ty = ProtoType()
  val meta = ProtoMetadata()
}

class DecoderPipelinePlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val macIf = host[MacInterfaceService]
  // from MacInterface
  lazy val igStream = Axi4Stream(macIf.axisConfig)

  // to AxiDmaPlugin
  lazy val decodedAuxData = Axi4Stream(macIf.axisConfig)
  // to core control plugins
  lazy val decodedDescs = Stream(DecodedDesc())

  val logic = during build new Area {
    macIf.rxStream >> igStream
  }
}
