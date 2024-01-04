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
                         pktBufAllocSizeMap: Seq[(Int, Double)] = Seq(
                           (128, .6), // 60% 128B packets
                           (1518, .4), // 30% 1518B packets (max Ethernet frame with MTU 1500)
                           // (9618, .1), // 10% 9618B packets (max jumbo frame)
                         ),
                         mtu: Int = 1518, // Ethernet frame!  Don't forget the 802.1Q header
                         maxRxPktsInFlight: Int = 128,
                         maxRxBlockCycles: BigInt = 5 * 1000 * 1000 * 1000 / 4, // 5 s @ 250 MHz
                         numCores: Int = 4,
                       )

case class PioNicEngine(implicit config: PioNicConfig) extends Component {
  private val axiConfig = config.axiConfig
  private val axisConfig = config.axisConfig

  val io = new Bundle {
    val s_axi = slave(Axi4(axiConfig))
    val s_axis_rx = slave(Axi4Stream(axisConfig))
    val m_axis_tx = master(Axi4Stream(axisConfig))
  }

  // buffer incoming packet for packet length
  val rxFifo = AxiStreamFifo(axisConfig, frameFifo = true, depthBytes = config.mtu)()
  rxFifo.slavePort << io.s_axis_rx
  // derive cmac incoming packet length
  val dispatchedCmacRx = StreamDispatcherSequential(
    input = rxFifo.slavePort.frameLength.map(_.toPacketLength).toStream, // TODO: record & report overflow
    outputCount = config.numCores,
  ).setName("packetLenDemux")

  val pktBufferSize = config.numCores * config.pktBufSizePerCore
  val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufferSize)))

  val dmaConfig = AxiDmaConfig(axiConfig, axisConfig, tagWidth = 32, lenWidth = config.pktBufAddrWidth)
  val axiDmaReadMux = new AxiDmaDescMux(dmaConfig, numPorts = config.numCores, arbRoundRobin = false)
  val axiDmaWriteMux = new AxiDmaDescMux(dmaConfig, numPorts = config.numCores, arbRoundRobin = false)

  val axiDma = new AxiDma(axiDmaReadMux.masterDmaConfig, enableUnaligned = true)
  axiDma.io.m_axi >> pktBuffer.io.s_axi_a
  axiDma.readDataMaster >> io.m_axis_tx
  axiDma.writeDataSlave << rxFifo.masterPort

  axiDma.io.read_enable := True
  axiDma.io.write_enable := True
  axiDma.io.write_abort := False

  // mux descriptors
  axiDmaReadMux.connectRead(axiDma)
  axiDmaWriteMux.connectWrite(axiDma)

  val axiWideConfigNode = Axi4(axiConfig)

  val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))
  val alloc = RegAllocator("global", 0, 0x1000, config.regWidth / 8)

  val globalCtrl = busCtrl.createReadAndWrite(GlobalControlBundle(), alloc("globalCtrl"))

  for (id <- 0 until config.numCores) {
    new PioCoreControl(dmaConfig, id).setName(s"coreCtrl_$id")
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

  // rename ports so Vivado could infer interfaces automatically
  noIoPrefix()
  addPrePopTask { () =>
    axi.renameAxi4IO
    axi.renameAxi4StreamIO(alwaysAddT = true)
  }
}

object PioNicEngineVerilog extends App {
  Config.spinal.generateVerilog(PioNicEngine()(PioNicConfig())).mergeRTLSource("Merged")
}
