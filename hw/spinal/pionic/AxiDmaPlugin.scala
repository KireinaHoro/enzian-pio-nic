package pionic

import jsteward.blocks.axi._
import jsteward.blocks.misc.RichBundle
import spinal.core._
import spinal.core.fiber._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin._

class AxiDmaPlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val csr = host[GlobalCSRPlugin].logic.get
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

  val dmaConfig = AxiDmaConfig(axiConfig, ms.axisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)

  val logic = during build new Area {
    val axiDmaReadMux = new AxiDmaDescMux(dmaConfig, numPorts = cores.length, arbRoundRobin = false)
    val axiDmaWriteMux = new AxiDmaDescMux(dmaConfig, numPorts = cores.length, arbRoundRobin = false)

    val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig)
    axiDma.readDataMaster.translateInto(ms.txStream) { case (fifo, dma) =>
      fifo.user := dma.user.resized
      fifo.assignUnassignedByName(dma)
    }
    axiDma.writeDataSlave << ms.rxStream

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    // mux descriptors
    axiDmaReadMux.connectRead(axiDma)
    axiDmaWriteMux.connectWrite(axiDma)

    // drive mac interface of core control modules
    cores.foreach { c =>
      val cio = c.logic.ctrl.io
      cio.readDesc >> axiDmaReadMux.s_axis_desc(c.coreID)
      cio.readDescStatus <<? axiDmaReadMux.m_axis_desc_status(c.coreID)

      // dma write desc port does not have id, dest, user
      axiDmaWriteMux.s_axis_desc(c.coreID).translateFrom(cio.writeDesc)(_ <<? _)
      cio.writeDescStatus << axiDmaWriteMux.m_axis_desc_status(c.coreID)

      // TODO: we should pass the decoded control structure (e.g. RPC call num + first args)
      //       instead of just length of packet
      cio.cmacRxAlloc << ms.dispatchedCmacRx(c.coreID)

      // exit timestamp for tx, demux'ed for this core
      cio.hostTxExitTimestamps << txTimestampsFlows(c.coreID)
    }
  }

  def packetBufDmaMaster: Axi4 = logic.axiDma.io.m_axi
}