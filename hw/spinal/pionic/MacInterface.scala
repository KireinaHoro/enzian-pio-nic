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

  def frameLen: Stream[PacketLength]
}

class XilinxCmacPlugin extends PioNicPlugin with MacInterfaceService {
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]
  lazy val p = host[ProfilerPlugin]

  val axisDataWidth = 64
  postConfig("axis data width", axisDataWidth)

  // matches Xilinx CMAC configuration
  val axisConfig = Axi4StreamConfig(
    dataWidth = axisDataWidth,
    useKeep = true,
    useLast = true,
  )

  def rxStream = logic.rxFifo.masterPort
  def txStream = logic.txFifo.slavePort

  def frameLen = logic.frameLenCdc

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)

    val txFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = roundMtu)()(clockDomain, cmacTxClock)
    txFifo.masterPort >> m_axis_tx

    val rxFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = roundMtu)()(cmacRxClock, clockDomain)
    rxFifo.slavePort << s_axis_rx

    // report overflow
    val rxOverflow = Bool()
    val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
    csr.status.rxOverflowCount := Counter(regWidth bits, rxOverflowCdc)

    // extract frame length
    val frameLen = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val frameLenCdc = frameLen.clone
    // FIXME: how much buffering do we need?
    val frameLenCdcFifo = SimpleAsyncFifo(frameLen, frameLenCdc, c[Int]("max rx pkts in flight"), cmacRxClock, clockDomain)

    // profile timestamps
    p.profile(
      p.RxCmacEntry -> PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain),
      p.RxAfterCdcQueue -> rxFifo.masterPort.fire,
      p.TxBeforeCdcQueue -> txFifo.slavePort.fire,
      p.TxCmacExit -> PulseCCByToggle(m_axis_tx.lastFire, cmacTxClock, clockDomain),
    )
  }
}