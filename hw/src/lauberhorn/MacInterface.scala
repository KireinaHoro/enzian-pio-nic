package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import Global._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.regif.AccessType.{RO, RW}

import scala.language.postfixOps

// service for potential other mac interface
trait MacInterfaceService {
  def axisConfig: Axi4StreamConfig

  def txStream: Axi4Stream
  def rxStream: Axi4Stream

  def frameLen: Stream[PacketLength]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit
}

class XilinxCmacPlugin extends FiberPlugin with MacInterfaceService {
  val rxCmacClkTp, rxAppClkTp,
      txCmacClkTp, txAppClkTp = during setup host[TracePlugin].makePort(LauberhornTraceDma.NicDecoderSlr)

  // matches Xilinx CMAC configuration
  lazy val axisConfig = Axi4StreamConfig(
    dataWidth = DATAPATH_WIDTH,
    useKeep = true,
    useLast = true,
  )

  def rxStream = logic.rxFifo.m_axis
  def txStream = logic.txAligner.io.input

  def frameLen = logic.frameLenCdc

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val m_axis_tx = master(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(axisConfig)) addTag ClockDomainTag(cmacRxClock)

    val rxFifo = AxiStreamAsyncFifo(axisConfig,
      frameFifo = true,    // frame mode to allow frameLen to be produced before packet goes downstream
      dropWhenFull = true, // must set since nobody is listening to s_axis_rx.ready
      depthBytes = ROUNDED_MTU)()(cmacRxClock, clockDomain)

    val rxDropAll = RegInit(True)
    val rxDomain = new ClockingArea(cmacRxClock) {
      // we count directly in CMAC RX domain with s_status.overflow instead of counting
      // m_status.overflow, since this is a fast-to-slow CDC and we might lose pulses
      val overflowCount = Counter(REG_WIDTH bits, rxFifo.io.s_status.overflow)
      val dropAll = BufferCC.withTag(rxDropAll, True)
      val afterDrop = s_axis_rx.throwWhen(dropAll)
      val pktCount = Counter(REG_WIDTH bits, afterDrop.lastFire)

      val frameLenOverflow = Bool()
      val frameLen = afterDrop
        .frameLength
        .map(_.resized.toPacketLength)
        .toStream(frameLenOverflow)
        .throwWhen(rxFifo.io.s_status.overflow) // do not enqueue the length of a dropped packet
      assert(!frameLenOverflow, "frame length should never overflow")

      rxFifo.s_axis << afterDrop
    }

    // Xilinx CMAC does not allow (TKEEP != 0 && !TLAST), use aligner here
    val txAligner = AxiStreamAligner(axisConfig)
    val txFifo = AxiStreamAsyncFifo(axisConfig, frameFifo = true, depthBytes = ROUNDED_MTU)()(clockDomain, cmacTxClock)
    txFifo.s_axis <-/< txAligner.io.output
    txFifo.m_axis >> m_axis_tx

    def cross(c: Counter) = {
      val rxD = new ClockingArea(cmacRxClock) {
        val grayEncoded = RegNext(toGray(c.value)) init 0
      }
      fromGray(BufferCC.withTag(rxD.grayEncoded, 0))
    }
    val rxMacOverflowCount = cross(rxDomain.overflowCount)
    val rxMacIngressCount = cross(rxDomain.pktCount)
    val rxMacIngressAfterCdcCount = Counter(REG_WIDTH bits, rxFifo.m_axis.get.lastFire)

    // extract frame length and push into TUSER
    // EthernetDecoder relies on this being available before packet content
    val frameLenCdc = rxDomain.frameLen.clone

    // this FIFO needs to hold lengths of everything buffered in rxFifo
    val frameLenCdcFifo = SimpleAsyncFifo(rxDomain.frameLen, frameLenCdc,
      ROUNDED_MTU / 64, cmacRxClock, clockDomain)

    rxCmacClkTp.trace("RxCmacEntry") := PulseCCByToggle(rxDomain.afterDrop.lastFire, cmacRxClock, clockDomain)
    rxAppClkTp.trace("RxAfterCdcQueue") := rxFifo.m_axis.fire
    txAppClkTp.trace("TxBeforeCdcQueue") := txFifo.s_axis.fire
    txCmacClkTp.trace("TxCmacExit") := PulseCCByToggle(m_axis_tx.lastFire, cmacTxClock, clockDomain)
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    busCtrl.read(logic.rxMacOverflowCount, alloc(name = "stat", subName = "rxMacOverflowCount", attr = RO,
      desc = "Number of packets dropped at CDC FIFO push side"))
    busCtrl.read(logic.rxMacIngressCount, alloc(name = "stat", subName = "rxMacIngressCount", attr = RO,
      desc = "Number of packets delivered by CMAC"))
    busCtrl.read(logic.rxMacIngressAfterCdcCount.value,
      alloc(name = "stat", subName = "rxMacIngressAfterCdcCount", attr = RO,
      desc = "Number of packets sent to decoders"))

    busCtrl.readAndWrite(logic.rxDropAll,
      alloc(name = "ctrl", subName = "rxDropAll", attr = RW,
      desc = "Drop all inbound packets (default to true)"))
  }
}
