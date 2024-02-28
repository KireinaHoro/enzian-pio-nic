package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc.{Profiler, Timestamps}
import spinal.core.fiber.Retainer
import spinal.lib.bus.amba4.axi.Axi4

import scala.language.postfixOps

// service for potential other mac interface
trait MacInterfaceService {
  def retainer: Retainer
  def txDmaConfig: AxiDmaConfig
  def txProfiler: Profiler

  def rxDmaConfig: AxiDmaConfig
  def rxProfiler: Profiler

  def packetBufDmaMaster: Axi4
}

class XilinxCmacPlugin(implicit config: PioNicConfig) extends FiberPlugin with MacInterfaceService {
  // break logic access loop: we access logic of the core already so we need to expose these
  // tracking: https://github.com/SpinalHDL/SpinalHDL/issues/1331
  var txDmaConfig: AxiDmaConfig = null
  var rxDmaConfig: AxiDmaConfig = null
  var txProfiler: Profiler = null
  var rxProfiler: Profiler = null
  var packetBufDmaMaster: Axi4 = null

  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]
  lazy val hs = host[HostService]
  val retainer = Retainer()

  val logic = during setup new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val Entry = NamedType(Timestamp) // packet data from CMAC, without ANY queuing (async)
    val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer
    rxProfiler = Profiler(Entry, AfterRxQueue)(config.collectTimestamps)

    val Exit = NamedType(Timestamp)
    txProfiler = Profiler(Exit)(config.collectTimestamps)

    val txAxisConfig = config.axisConfig.copy(userWidth = config.coreIDWidth, useUser = true)
    txDmaConfig = AxiDmaConfig(config.axiConfig, txAxisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)
    rxDmaConfig = txDmaConfig.copy(axisConfig = rxProfiler augment config.axisConfig)

    val hostLock = hs.retainer()

    val m_axis_tx = master(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacRxClock)

    awaitBuild()

    implicit val clock = csr.status.cycles

    // capture cmac rx lastFire
    val rxTimestamps = rxProfiler.timestamps.clone
    val rxLastFireCdc = PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain)
    rxProfiler.fillSlot(rxTimestamps, Entry, rxLastFireCdc)

    // CDC for tx
    val txFifo = AxiStreamAsyncFifo(txAxisConfig, frameFifo = true, depthBytes = config.roundMtu)()(clockDomain, cmacTxClock)
    txFifo.masterPort.translateInto(m_axis_tx)(_ <<? _) // ignore TUSER

    val txTimestamps = txProfiler.timestamps.clone

    val txClkArea = new ClockingArea(cmacTxClock) {
      val lastCoreIDFlow = ValidFlow(RegNext(txFifo.masterPort.user)).takeWhen(txFifo.masterPort.lastFire)
    }

    val lastCoreIDFlowCdc = txClkArea.lastCoreIDFlow.ccToggle(cmacTxClock, clockDomain)
    txProfiler.fillSlot(txTimestamps, Exit, lastCoreIDFlowCdc.fire)

    // demux timestamps: generate valid for Flow[Timestamps] back into Control
    val txTimestampsFlows = Seq.tabulate(cores.length) { coreID =>
      val delayedCoreIDFlow = RegNext(lastCoreIDFlowCdc)
      ValidFlow(txTimestamps).takeWhen(delayedCoreIDFlow.valid && delayedCoreIDFlow.payload === coreID)
    }

    // CDC for rx
    // buffer incoming packet for packet length
    val rxFifo = AxiStreamAsyncFifo(config.axisConfig, frameFifo = true, depthBytes = config.roundMtu)()(cmacRxClock, clockDomain)
    rxFifo.slavePort << s_axis_rx
    // derive cmac incoming packet length

    // report overflow
    val rxOverflow = Bool()
    val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
    csr.status.rxOverflowCount := Counter(config.regWidth bits, rxOverflowCdc)

    val cmacReq = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val cmacReqCdc = cmacReq.clone
    SimpleAsyncFifo(cmacReq, cmacReqCdc, 2, cmacRxClock, clockDomain)

    // only dispatch to enabled cores
    val dispatchedCmacRx = StreamDispatcherWithEnable(
      input = cmacReqCdc,
      outputCount = cores.length,
      enableMask = csr.ctrl.dispatchMask,
    ).setName("packetLenDemux")

    // TX DMA USER: core ID to demux timestamp
    val axiDmaReadMux = new AxiDmaDescMux(txDmaConfig, numPorts = cores.length, arbRoundRobin = false)
    val axiDmaWriteMux = new AxiDmaDescMux(rxDmaConfig, numPorts = cores.length, arbRoundRobin = false)

    val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig)
    axiDma.readDataMaster.translateInto(txFifo.slavePort) { case (fifo, dma) =>
      fifo.user := dma.user.resized
      fifo.assignUnassignedByName(dma)
    }
    val rxFifoTimestamped = rxProfiler.timestamp(rxFifo.masterPort, AfterRxQueue, base = rxTimestamps)
    axiDma.writeDataSlave << rxFifoTimestamped

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    packetBufDmaMaster = axiDma.io.m_axi
    hostLock.release()

    // mux descriptors
    axiDmaReadMux.connectRead(axiDma)
    axiDmaWriteMux.connectWrite(axiDma)

    retainer.await()

    // core control modules
    cores.foreach { c =>
      c.logic.ctrl.driveMacIf(
        rdMux = axiDmaReadMux,
        wrMux = axiDmaWriteMux,
        cmacRx = dispatchedCmacRx(c.coreID),
        txTimestamps = txTimestampsFlows(c.coreID),
      )
    }
  } setName ""
}