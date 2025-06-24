package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

import Global._

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
  lazy val p = host[ProfilerPlugin]

  DATAPATH_WIDTH.set(64)

  // matches Xilinx CMAC configuration
  lazy val axisConfig = Axi4StreamConfig(
    dataWidth = DATAPATH_WIDTH,
    useKeep = true,
    useLast = true,
  )

  def rxStream = logic.rxFifo.m_axis
  def txStream = logic.txFifo.s_axis

  def frameLen = logic.frameLenCdc

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)

    val txFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = ROUNDED_MTU)()(clockDomain, cmacTxClock)
    txFifo.m_axis >> m_axis_tx

    val rxFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = ROUNDED_MTU)()(cmacRxClock, clockDomain)
    rxFifo.s_axis << s_axis_rx

    // report overflow
    val rxOverflow = Bool()
    val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
    csr.status.rxOverflowCount := Counter(REG_WIDTH bits, rxOverflowCdc)

    // extract frame length
    val frameLen = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val frameLenCdc = frameLen.clone
    // XXX: this is only buffering packet length.  We should never drop anything here: the decoder pipeline
    //      should decode everything.  The place where a drop is allowed to happen, is in the scheduler
    // this FIFO needs to hold max burst rate * inter packet gap on decoder pipeline
    val frameLenCdcFifo = SimpleAsyncFifo(frameLen, frameLenCdc, 32, cmacRxClock, clockDomain)

    // profile timestamps
    p.profile(
      p.RxCmacEntry -> PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain),
      p.RxAfterCdcQueue -> rxFifo.m_axis.fire,
      p.TxBeforeCdcQueue -> txFifo.s_axis.fire,
      p.TxCmacExit -> PulseCCByToggle(m_axis_tx.lastFire, cmacTxClock, clockDomain),
    )
  }
}