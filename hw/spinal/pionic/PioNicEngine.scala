package pionic

// alexforencich IPs

import axi._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

case class PioNicConfig(
                         axiConfig: Axi4Config = Axi4Config(
                           addressWidth = 64,
                           dataWidth = 512,
                           idWidth = 4,
                         ),
                         axisConfig: Axi4StreamConfig = Axi4StreamConfig(
                           dataWidth = 64, // BYTES
                           useKeep = true,
                           useLast = true,
                         ),
                         regWidth: Int = 64,
                         pktBufAddrWidth: Int = 16, // 64KB
                         pktBufSizePerCore: Int = 16 * 1024, // 16KB
                         mtu: Int = 9600,
                         maxRxPktsInFlight: Int = 128,
                         maxRxBlockCycles: Int = 10000,
                         numCores: Int = 4,
                       )

class PioNicEngine(implicit config: PioNicConfig) extends Component {
  private val axiConfig = config.axiConfig
  private val axisConfig = config.axisConfig

  val io = new Bundle {
    val s_axi = slave(Axi4(axiConfig))
    val s_axis_rx = slave(Axi4Stream(axisConfig))
    val m_axis_tx = master(Axi4Stream(axisConfig))
  }

  // derive cmac incoming packet length
  val cmacRx = Stream(PacketLength())
  val dispatchedCmacRx = StreamDispatcherSequencial(
    input = cmacRx,
    outputCount = config.numCores,
  )
  // TODO: calculate length of incoming packet
  cmacRx.assignDontCare()

  val pktBufferSize = config.numCores * config.pktBufSizePerCore
  val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufferSize)))

  val dmaConfig = AxiDmaConfig(axiConfig, axisConfig, tagWidth = 32, lenWidth = config.pktBufAddrWidth)
  val axiDmaReadMux = new AxiDmaDescMux(dmaConfig, numPorts = config.numCores)
  val axiDmaWriteMux = new AxiDmaDescMux(dmaConfig, numPorts = config.numCores)

  val axiDma = new AxiDma(axiDmaReadMux.masterDmaConfig)
  axiDma.io.m_axi >> pktBuffer.io.s_axi_a
  axiDma.readDataMaster >> io.m_axis_tx
  axiDma.writeDataSlave << io.s_axis_rx

  axiDma.io.read_enable := True
  axiDma.io.write_enable := True
  axiDma.io.write_abort := False

  // mux descriptors
  axiDmaReadMux.connectRead(axiDma)
  axiDmaWriteMux.connectWrite(axiDma)

  val axiWideConfigNode = Axi4(axiConfig)

  val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))
  val globalCtrl = busCtrl.createReadAndWrite(GlobalControlBundle(), 0)

  for (id <- 0 until config.numCores) {
    new PioCoreControl(dmaConfig, id)
      .driveFrom(busCtrl, (1 + id) * 0x1000)(
        globalCtrl = globalCtrl,
        rdMux = axiDmaReadMux,
        wrMux = axiDmaWriteMux,
        cmacRx = dispatchedCmacRx(id),
      )
  }

  Axi4CrossbarFactory()
    .addSlaves(
      axiWideConfigNode -> (0x0, (config.numCores + 1) * 0x1000),
      pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
    )
    .addConnections(
      io.s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b),
    )
    .build()
}

object PioNicEngineVerilog extends App {
  Config.spinal.generateVerilog(new PioNicEngine()(PioNicConfig())).mergeRTLSource("Merged")
}
