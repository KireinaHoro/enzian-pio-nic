package pionic

// alexforencich IPs

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._
import spinal.lib.eda._

import jsteward.blocks.axi._
import jsteward.blocks.misc.{Profiler, RegAllocatorFactory}

import scala.language.postfixOps
import mainargs._

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

  def writeHeader(outPath: os.Path): Unit = {
    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_CONFIG_H__
          |#define __PIONIC_CONFIG_H__
          |
          |#define PIONIC_NUM_CORES $numCores
          |#define PIONIC_PKT_ADDR_WIDTH $pktBufAddrWidth
          |#define PIONIC_PKT_ADDR_MASK ((1 << PIONIC_PKT_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_LEN_WIDTH $pktBufLenWidth
          |#define PIONIC_PKT_LEN_MASK ((1 << PIONIC_PKT_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${Config.spinal.defaultClockDomainFrequency.getValue.toLong}
          |
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }
}

case class PioNicEngine(cmacRxClock: ClockDomain = ClockDomain.external("cmacRxClock"),
                        cmacTxClock: ClockDomain = ClockDomain.external("cmacTxClock"))(implicit config: PioNicConfig) extends Component {
  // allow second run of elaboration to work
  config.allocFactory.clear()

  private val axiConfig = config.axiConfig
  private val axisConfig = config.axisConfig

  // global cycles counter for measurements
  implicit val globalStatus = GlobalStatusBundle()
  implicit val clock = globalStatus.cyclesCount

  val io = new Bundle {
    val s_axi = slave(Axi4(axiConfig))
    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)
  }

  val Entry = NamedType(Timestamp) // packet data from CMAC, without ANY queuing (async)
  val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer
  val profiler = Profiler(Entry, AfterRxQueue)(config.collectTimestamps)
  val profiledAxisConfig = profiler augment axisConfig

  // capture cmac rx lastFire
  val timestamps = profiler.timestamps.clone

  val rxLastFireCdc = PulseCCByToggle(io.s_axis_rx.lastFire, cmacRxClock, clockDomain)
  profiler.fillSlot(timestamps, Entry, rxLastFireCdc)

  val packetBeats = config.roundMtu / config.axisConfig.dataWidth

  // CDC for tx
  val txFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthWords = packetBeats)()(clockDomain, cmacTxClock)
  txFifo.masterPort >> io.m_axis_tx

  // CDC for rx
  // buffer incoming packet for packet length
  val rxFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthWords = packetBeats)()(cmacRxClock, clockDomain)
  rxFifo.slavePort << io.s_axis_rx
  // derive cmac incoming packet length

  // report overflow
  val rxOverflow = Bool()
  val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
  val rxOverflowCounter = Counter(config.regWidth bits, rxOverflowCdc)

  val cmacReq = io.s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
  val cmacReqCdc = cmacReq.clone
  SimpleAsyncFifo(cmacReq, cmacReqCdc, 2, cmacRxClock, clockDomain)

  // only dispatch to enabled cores
  val dispatchMask = Bits(config.numCores bits)
  val dispatchedCmacRx = StreamDispatcherWithEnable(
    input = cmacReqCdc,
    outputCount = config.numCores,
    enableMask = dispatchMask,
  ).setName("packetLenDemux")

  val pktBufferSize = config.numCores * config.pktBufSizePerCore
  val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufferSize)))

  val txDmaConfig = AxiDmaConfig(axiConfig, axisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)
  val axiDmaReadMux = new AxiDmaDescMux(txDmaConfig, numPorts = config.numCores, arbRoundRobin = false)
  val rxDmaConfig = txDmaConfig.copy(axisConfig = profiledAxisConfig)
  val axiDmaWriteMux = new AxiDmaDescMux(rxDmaConfig, numPorts = config.numCores, arbRoundRobin = false)

  val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig, enableUnaligned = true)
  axiDma.io.m_axi >> pktBuffer.io.s_axi_a
  axiDma.readDataMaster.translateInto(txFifo.slavePort)(_ <<? _) // ignore TUSER
  axiDma.writeDataSlave << profiler.timestamp(rxFifo.masterPort, AfterRxQueue, base = timestamps)

  axiDma.io.read_enable := True
  axiDma.io.write_enable := True
  axiDma.io.write_abort := False

  // mux descriptors
  axiDmaReadMux.connectRead(axiDma)
  axiDmaWriteMux.connectWrite(axiDma)

  val axiWideConfigNode = Axi4(axiConfig)

  val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

  private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)
  private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, pktBufferSize, pktBufferSize)

  val globalCtrl = busCtrl.createReadAndWrite(GlobalControlBundle(), alloc("ctrl"))
  globalCtrl.rxBlockCycles init 10000
  busCtrl.driveAndRead(dispatchMask, alloc("dispatchMask")) init (1 << config.numCores) - 1

  // global statistics
  busCtrl.read(rxOverflowCounter.value, alloc("rxOverflowCount"))

  val cyclesCounter = CounterFreeRun(config.regWidth bits)
  globalStatus.cyclesCount.bits := cyclesCounter
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
    renameAxi4IO
    renameAxi4StreamIO(alwaysAddT = true)
  }
}

object PioNicEngineVerilog {
  implicit val config = PioNicConfig()

  @main
  def run(@arg(doc = "generate driver headers")
          genHeaders: Boolean = true,
          @arg(doc = "print register map")
          printRegMap: Boolean = true,
         ): Unit = {
    val report = Config.spinal.generateVerilog(PioNicEngine())
    report.mergeRTLSource("Merged")
    report.writeConstraints()
    if (printRegMap) config.allocFactory.dumpAll()
    if (genHeaders) {
      val genDir = os.pwd / os.RelPath(Config.outputDirectory)
      config.allocFactory.writeHeader("pionic", genDir / "regs.h")
      config.writeHeader(genDir / "config.h")
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
