package pionic.host.pcie

import jsteward.blocks.axi._
import pionic._
import pionic.host.HostService
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class PcieBridgeInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  // PCIe bridge block AXI config
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
  )

  val logic = during build new Area {
    val s_axi = slave(Axi4(axiConfig))

    val axiWideConfigNode = Axi4(axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(axiConfig.dataWidth)
    csr.readAndWrite(busCtrl, alloc(_))

    private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, config.pktBufSize, config.pktBufSize)(axiConfig.dataWidth)

    // TODO: partition buffer for each core (and steer DMA writes) for max throughput
    val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(config.pktBufSize)))

    Axi4CrossbarFactory()
      .addSlaves(
        axiWideConfigNode -> (0x0, (cores.length + 1) * 0x1000),
        pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), config.pktBufSize),
      )
      .addConnection(s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b))
      .build()

    retainer.await()

    // FIXME: we could need an adapter here
    host[AxiDmaPlugin].packetBufDmaMaster >> pktBuffer.io.s_axi_a

    // drive control interface (packet action)
    cores foreach { c =>
      val baseAddress = (1 + c.coreID) * 0x1000
      val cio = c.logic.io

      val alloc = config.allocFactory("control", c.coreID)(baseAddress, 0x1000, config.regWidth / 8)(axiConfig.dataWidth)

      val rxAddr = alloc("hostRx", readSensitive = true)
      busCtrl.readStreamBlockCycles(cio.hostRx, rxAddr, csr.logic.ctrl.rxBlockCycles)
      busCtrl.driveStream(cio.hostRxAck, alloc("hostRxAck"))

      // on read primitive (AR for AXI), set hostRxReq for timing ReadStart
      cio.hostRxReq := False
      busCtrl.onReadPrimitive(SingleMapping(rxAddr), haltSensitive = false, "read request issued") {
        cio.hostRxReq := True
      }

      // should not block; only for profiling (to use ready signal)
      busCtrl.readStreamNonBlocking(cio.hostTx, alloc("hostTx", readSensitive = true))
      busCtrl.driveStream(cio.hostTxAck, alloc("hostTxAck"))

      c.logic.connectControl(busCtrl, alloc(_))
      c.logic.reportStatistics(busCtrl, alloc(_, _))
    }

    host[ProfilerPlugin].logic.reportTimestamps(busCtrl, alloc(_, _))
  }
}
