package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

import scala.language.postfixOps

// service for potential other mac interface
trait MacInterfaceService {
  def txProfiler: Profiler
  def rxProfiler: Profiler
  def axisConfig: Axi4StreamConfig

  def txStream: Axi4Stream
  def rxStream: Axi4Stream

  // FIXME: this is the decoded descriptor and should be produced by the decoder pipeline
  def dispatchedCmacRx: Vec[Stream[PacketLength]]
}

class XilinxCmacPlugin(implicit config: PioNicConfig) extends FiberPlugin with MacInterfaceService {
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]

  val pk = new Area {
    val Entry = NamedType(Timestamp) // packet data from CMAC, without ANY queuing (async)
    val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer
    val Exit = NamedType(Timestamp)
  } setName ""

  val rxProfiler = Profiler(pk.Entry, pk.AfterRxQueue)(config.collectTimestamps)
  val txProfiler = Profiler(pk.Exit)(config.collectTimestamps)

  // matches Xilinx CMAC configuration
  val axisConfig = Axi4StreamConfig(
    dataWidth = 64, // BYTES
    useKeep = true,
    useLast = true,
  )

  def rxStream = logic.rxFifo.masterPort
  def txStream = logic.txFifo.masterPort

  def dispatchedCmacRx: Vec[Stream[PacketLength]] = logic.dispatchedCmacRx

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)

    implicit val clock = csr.status.cycles

    // capture cmac rx lastFire
    val rxTimestamps = rxProfiler.timestamps.clone
    val rxLastFireCdc = PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain)
    rxProfiler.fillSlot(rxTimestamps, pk.Entry, rxLastFireCdc)

    val txFifoAxisConfig = axisConfig.copy(userWidth = config.coreIDWidth, useUser = true)
    val txFifo = AxiStreamAsyncFifo(txFifoAxisConfig, frameFifo = true, depthBytes = config.roundMtu)()(clockDomain, cmacTxClock)
    txFifo.masterPort.translateInto(m_axis_tx)(_ <<? _) // drop TUSER that contains core ID

    val txTimestamps = txProfiler.timestamps.clone

    val txClkArea = new ClockingArea(cmacTxClock) {
      // Tx AXIS from DMA carries core ID of initiator in USER field
      // used to fill timestamps of the correct core
      val lastCoreIDFlow = ValidFlow(RegNext(txFifo.masterPort.user)).takeWhen(txFifo.masterPort.lastFire)
    }

    val lastCoreIDFlowCdc = txClkArea.lastCoreIDFlow.ccToggle(cmacTxClock, clockDomain)
    txProfiler.fillSlot(txTimestamps, pk.Exit, lastCoreIDFlowCdc.fire)

    // demux timestamps: generate valid for Flow[Timestamps] back into Control
    val txTimestampsFlows = Seq.tabulate(host.list[CoreControlPlugin].length) { coreID =>
      val delayedCoreIDFlow = RegNext(lastCoreIDFlowCdc)
      ValidFlow(txTimestamps).takeWhen(delayedCoreIDFlow.valid && delayedCoreIDFlow.payload === coreID)
    }

    // CDC for rx
    // buffer incoming packet for packet length
    // FIXME: how much buffering do we need?
    val rxFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = config.roundMtu)()(cmacRxClock, clockDomain)
    rxFifo.slavePort << s_axis_rx
    rxProfiler.fillSlot(rxTimestamps, pk.AfterRxQueue, rxStream.lastFire)

    // report overflow
    val rxOverflow = Bool()
    val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
    csr.status.rxOverflowCount := Counter(config.regWidth bits, rxOverflowCdc)

    // extract frame length
    // TODO: attach point for (IP/UDP/RPC prognum) decoder pipeline.  Produce necessary info for scheduler
    //       pipeline should emit:
    //       - a control struct (length of payload stream + scheduler command + additional decoded data, e.g. RPC args)
    //       - a payload stream to DMA
    // FIXME: do we actually need more than one proc pipelines on the NIC?
    val cmacReq = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val cmacReqCdc = cmacReq.clone
    // FIXME: how much buffering do we need?
    val cmacReqCdcFifo = SimpleAsyncFifo(cmacReq, cmacReqCdc, config.maxRxPktsInFlight, cmacRxClock, clockDomain)

    // round-robin dispatch to enabled CPU cores
    // TODO: replace with more complicated scheduler after decoder pipeline, based on info from the decoder
    val dispatchedCmacRx = StreamDispatcherWithEnable(
      input = cmacReqCdc,
      outputCount = cores.length,
      enableMask = csr.ctrl.dispatchMask,
      maskChanged = csr.status.dispatchMaskChanged,
    ).setName("packetLenDemux")
  }
}