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
  def axisConfig: Axi4StreamConfig

  def txStream: Axi4Stream
  def rxStream: Axi4Stream

  // FIXME: this is the decoded descriptor and should be produced by the decoder pipeline
  def dispatchedCmacRx: Vec[Stream[PacketLength]]
}

class XilinxCmacPlugin(implicit config: PioNicConfig) extends FiberPlugin with MacInterfaceService {
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]
  lazy val p = host[ProfilerPlugin]

  // matches Xilinx CMAC configuration
  val axisConfig = Axi4StreamConfig(
    dataWidth = config.axisDataWidth,
    useKeep = true,
    useLast = true,
  )

  def rxStream = logic.rxFifo.masterPort
  def txStream = logic.txFifo.slavePort

  def dispatchedCmacRx: Vec[Stream[PacketLength]] = logic.dispatchedCmacRx

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)

    val txFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = config.roundMtu)()(clockDomain, cmacTxClock)
    txFifo.masterPort >> m_axis_tx

    val rxFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = config.roundMtu)()(cmacRxClock, clockDomain)
    rxFifo.slavePort << s_axis_rx

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

    // profile timestamps
    p.profile(
      p.RxCmacEntry -> PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain),
      p.RxAfterCdcQueue -> rxFifo.masterPort.fire,
      p.TxBeforeCdcQueue -> txFifo.slavePort.fire,
      p.TxCmacExit -> PulseCCByToggle(m_axis_tx.lastFire, cmacTxClock, clockDomain),
    )
  }
}