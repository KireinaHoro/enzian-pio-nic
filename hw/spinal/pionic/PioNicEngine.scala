package pionic

// alexforencich IPs

import axi._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

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
                         pktBufLenWidth: Int = 16, // max 64KB per packet
                         pktBufSizePerCore: Int = 16 * 1024, // 16KB
                         pktBufAllocSizeMap: Seq[(Int, Double)] = Seq(
                           (128, .6), // 60% 128B packets
                           (1518, .4), // 30% 1518B packets (max Ethernet frame with MTU 1500)
                           // (9618, .1), // 10% 9618B packets (max jumbo frame)
                         ),
                         maxRxPktsInFlight: Int = 128,
                         rxBlockCyclesWidth: Int = log2Up(BigInt(5) * 1000 * 1000 * 1000 / 4), // 5 s @ 250 MHz
                         numCores: Int = 4,
                         // for Profiling
                         collectTimestamps: Boolean = true,
                         timestampWidth: Int = 32, // width for a single timestamp
                       ) {
  def pktBufAddrMask = (BigInt(1) << pktBufAddrWidth) - BigInt(1)

  def pktBufLenMask = (BigInt(1) << pktBufLenWidth) - BigInt(1)

  def mtu = pktBufAllocSizeMap.map(_._1).max

  def roundMtu = roundUp(mtu, axisConfig.dataWidth).toInt

  val allocFactory = new RegAllocatorFactory
}

case class PioNicEngine()(implicit config: PioNicConfig) extends Component {
  // allow second run of elaboration to work
  config.allocFactory.clear()

  private val axiConfig = config.axiConfig
  private val axisConfig = config.axisConfig

  // global cycles counter for measurements
  implicit val globalStatus = GlobalStatusBundle()

  val io = new Bundle {
    val s_axi = slave(Axi4(axiConfig))
    val s_axis_rx = slave(Axi4Stream(axisConfig))
    val m_axis_tx = master(Axi4Stream(axisConfig))
  }

  val Entry = NamedType(Timestamp) // packet data from CMAC
  val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer
  val profiler = Profiler(Entry, AfterRxQueue)()
  val rxAxisConfig = profiler augment axisConfig
  println(rxAxisConfig)

  // buffer incoming packet for packet length
  val rxFifo = AxiStreamFifo(rxAxisConfig, frameFifo = true, depthBytes = config.roundMtu)()
  rxFifo.slavePort << profiler.timestamp(io.s_axis_rx, Entry)
  // derive cmac incoming packet length

  // report overflow
  val rxOverflow = Bool()
  val rxOverflowCounter = Counter(config.regWidth bits, rxOverflow)

  // only dispatch to enabled cores
  val dispatchMask = Bits(config.numCores bits)
  val dispatchedCmacRx = StreamDispatcherWithEnable(
    input = rxFifo.slavePort.frameLength
      .map(_.resized.toPacketLength)
      .toStream(rxOverflow),
    outputCount = config.numCores,
    enableMask = dispatchMask,
  ).setName("packetLenDemux")

  val pktBufferSize = config.numCores * config.pktBufSizePerCore
  val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufferSize)))

  val txDmaConfig = AxiDmaConfig(axiConfig, axisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)
  val axiDmaReadMux = new AxiDmaDescMux(txDmaConfig, numPorts = config.numCores, arbRoundRobin = false)
  val rxDmaConfig = txDmaConfig.copy(axisConfig = rxAxisConfig)
  val axiDmaWriteMux = new AxiDmaDescMux(rxDmaConfig, numPorts = config.numCores, arbRoundRobin = false)

  val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig, enableUnaligned = true)
  axiDma.io.m_axi >> pktBuffer.io.s_axi_a
  axiDma.readDataMaster.translateInto(io.m_axis_tx)(_ <<? _) // ignore TUSER
  axiDma.writeDataSlave << profiler.timestamp(rxFifo.masterPort, AfterRxQueue)

  axiDma.io.read_enable := True
  axiDma.io.write_enable := True
  axiDma.io.write_abort := False

  // mux descriptors
  axiDmaReadMux.connectRead(axiDma)
  axiDmaWriteMux.connectWrite(axiDma)

  val axiWideConfigNode = Axi4(axiConfig)

  val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

  val alloc = config.allocFactory("global", 0, 0x1000, config.regWidth / 8)
  val pktBufferAlloc = config.allocFactory("pktBuffer", 0x100000, pktBufferSize, pktBufferSize)

  val globalCtrl = busCtrl.createReadAndWrite(GlobalControlBundle(), alloc("globalCtrl"))
  globalCtrl.rxBlockCycles init 10000
  busCtrl.driveAndRead(dispatchMask, alloc("dispatchMask")) init (1 << config.numCores) - 1

  // global statistics
  busCtrl.read(rxOverflowCounter.value, alloc("rxOverflowCount"))

  val cyclesCounter = CounterFreeRun(config.regWidth bits)
  globalStatus.cyclesCount := cyclesCounter
  busCtrl.read(cyclesCounter.value, alloc("cyclesCount")) // for host reference

  for (id <- 0 until config.numCores) {
    new PioCoreControl(rxDmaConfig, txDmaConfig, id, profiler).setName(s"coreCtrl_$id")
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
      pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), pktBufferSize),
      // pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
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
  implicit val config = PioNicConfig()
  Config.spinal.generateVerilog(PioNicEngine()).mergeRTLSource("Merged")
  config.allocFactory.dumpAll()
}
