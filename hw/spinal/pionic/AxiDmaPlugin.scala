package pionic

import jsteward.blocks.axi._
import jsteward.blocks.misc.RichBundle
import pionic.host.HostService
import pionic.net.ProtoMetadata
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin._

class AxiDmaPlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val cores = host.list[CoreControlPlugin]
  lazy val hs = host[HostService]

  lazy val ms = host[MacInterfaceService]

  // config for packetBufDmaMaster
  // we fix here and downstream should adapt
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
  )

  // FIXME: get tag width from RxDmaTag (instead of hardcoding)
  lazy val dmaConfig = AxiDmaConfig(axiConfig, ms.axisConfig, tagWidth = 460, lenWidth = config.pktBufLenWidth)

  val logic = during build new Area {
    val axiDmaReadMux = new AxiDmaDescMux(dmaConfig, numPorts = cores.length, arbRoundRobin = false)
    val axiDmaWriteMux = new AxiDmaDescMux(dmaConfig, numPorts = cores.length, arbRoundRobin = false)

    // select and demux payload

    val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig)
    // TODO: replace with encoders input
    axiDma.readDataMaster >> ms.txStream
    axiDma.writeDataSlave << host[PacketSinkService].packetSink

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    // mux descriptors
    axiDmaReadMux.connectRead(axiDma)
    axiDmaWriteMux.connectWrite(axiDma)

    // consume DMA descriptor interface of core control modules
    cores.foreach { c =>
      val cio = c.logic.io
      cio.readDesc >> axiDmaReadMux.s_axis_desc(c.coreID)
      cio.readDescStatus <<? axiDmaReadMux.m_axis_desc_status(c.coreID)

      // dma write desc port does not have id, dest, user
      axiDmaWriteMux.s_axis_desc(c.coreID).translateFrom(cio.writeDesc)(_ <<? _)
      cio.writeDescStatus << axiDmaWriteMux.m_axis_desc_status(c.coreID)
    }
  }

  def packetBufDmaMaster: Axi4 = logic.axiDma.io.m_axi
}
