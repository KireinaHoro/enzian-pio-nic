package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc.Profiler

import scala.language.postfixOps

class CmacInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin {
  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    implicit val globalStatus = GlobalStatusBundle()
    implicit val clock = globalStatus.cyclesCount

    val m_axis_tx = master(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacRxClock)
    val Entry = NamedType(Timestamp) // packet data from CMAC, without ANY queuing (async)
    val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer

    println(Entry, AfterRxQueue)
    val rxProfiler = Profiler(Entry, AfterRxQueue)(config.collectTimestamps)

    val Exit = NamedType(Timestamp)
    val txProfiler = Profiler(Exit)(config.collectTimestamps)

    // capture cmac rx lastFire
    val rxTimestamps = rxProfiler.timestamps.clone
    val rxLastFireCdc = PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain)
    rxProfiler.fillSlot(rxTimestamps, Entry, rxLastFireCdc)
    val txAxisConfig = config.axisConfig.copy(userWidth = config.coreIDWidth, useUser = true)

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
    val txTimestampsFlows = Seq.tabulate(config.numCores) { coreID =>
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
    val rxOverflowCounter = Counter(config.regWidth bits, rxOverflowCdc)

    val cmacReq = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val cmacReqCdc = cmacReq.clone
    SimpleAsyncFifo(cmacReq, cmacReqCdc, 2, cmacRxClock, clockDomain)

    // only dispatch to enabled cores
    val dispatchMask = Bits(config.numCores bits)
    val dispatchedCmacRx = StreamDispatcherWithEnable(
      input = cmacReqCdc,
      outputCount = config.numCores,
      enableMask = dispatchMask,
    ).setName("packetLenDemux")

    // TX DMA USER: core ID to demux timestamp
    val txDmaConfig = AxiDmaConfig(config.axiConfig, txAxisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)
    val axiDmaReadMux = new AxiDmaDescMux(txDmaConfig, numPorts = config.numCores, arbRoundRobin = false)
    val rxDmaConfig = txDmaConfig.copy(axisConfig = rxProfiler augment config.axisConfig)
    val axiDmaWriteMux = new AxiDmaDescMux(rxDmaConfig, numPorts = config.numCores, arbRoundRobin = false)

    val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig, enableUnaligned = true)
    axiDma.readDataMaster.translateInto(txFifo.slavePort) { case (fifo, dma) =>
      fifo.user := dma.user.resized
      fifo.assignUnassignedByName(dma)
    }
    val rxFifoTimestamped = rxProfiler.timestamp(rxFifo.masterPort, AfterRxQueue, base = rxTimestamps)
    axiDma.writeDataSlave << rxFifoTimestamped

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    // mux descriptors
    axiDmaReadMux.connectRead(axiDma)
    axiDmaWriteMux.connectWrite(axiDma)

    val globalCtrl = Reg(GlobalControlBundle())
    globalCtrl.rxBlockCycles init 10000

    // core control modules
    val coreCtrls = (0 until config.numCores) map { id =>
      val ctrl = new PioCoreControl(rxDmaConfig, txDmaConfig, id, rxProfiler, txProfiler).setName(s"coreCtrl_$id")

      ctrl.drivePlatformAgnostic(
        globalCtrl = globalCtrl,
        rdMux = axiDmaReadMux,
        wrMux = axiDmaWriteMux,
        cmacRx = dispatchedCmacRx(id),
        txTimestamps = txTimestampsFlows(id),
      )

      ctrl
    } toList
  } setName ""
}