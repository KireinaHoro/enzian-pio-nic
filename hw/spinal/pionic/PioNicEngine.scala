package pionic

// alexforencich IPs

import axi._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

case class PioNicConfig(axiConfig: Axi4Config = Axi4Config(32, 64, 4),
                        axisConfig: Axi4StreamConfig = Axi4StreamConfig(64),
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
  val cmacRx = Stream(UInt(config.pktBufAddrWidth bits))
  val dispatchedCmacRx = StreamDispatcherSequencial(
    input = cmacRx,
    outputCount = config.numCores,
  )
  // TODO: calculate length of incoming packet
  cmacRx.assignDontCare()

  val pktBufferSize = config.numCores * config.pktBufSizePerCore
  val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufferSize)))

  val dmaConfig = AxiDmaConfig(axiConfig, axisConfig)
  val axiDma = new AxiDma(dmaConfig)
  axiDma.io.m_axi >> pktBuffer.io.s_axi_a
  axiDma.io.m_axis_read_data >> io.m_axis_tx
  axiDma.io.s_axis_write_data << io.s_axis_rx

  val axiConfigNode = Axi4(axiConfig)

  val busCtrl = Axi4SlaveFactory(axiConfigNode)
  val globalCtrl = busCtrl.createReadAndWrite(GlobalControlBundle(), 0)

  for (id <- 0 until config.numCores) {
    new PioCoreControl(dmaConfig, id)
      .driveFrom(busCtrl, (1 + id) * 0x1000)(
        globalCtrl = globalCtrl,
        dma = axiDma,
        cmacRx = dispatchedCmacRx(id),
      )
  }

  Axi4CrossbarFactory()
    .addSlaves(
      axiConfigNode -> (0x0, (config.numCores + 1) * 0x1000),
      pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
    )
    .addConnections(
      io.s_axi -> Seq(axiConfigNode, pktBuffer.io.s_axi_b),
    )
    .build()
}

object PioNicEngineVerilog extends App {
  Config.spinal.generateVerilog(new PioNicEngine()(PioNicConfig())).mergeRTLSource("Merged")
}
