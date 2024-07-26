package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import spinal.core.fiber.Retainer
import spinal.lib.bus.amba4.axi._

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
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]
  lazy val hs = host[HostService]
  val retainer = Retainer()

  val pk = new Area {
    val Entry = NamedType(Timestamp) // packet data from CMAC, without ANY queuing (async)
    val AfterRxQueue = NamedType(Timestamp) // time in rx queuing for frame length and global buffer
    val Exit = NamedType(Timestamp)
  } setName ""

  val rxProfiler = Profiler(pk.Entry, pk.AfterRxQueue)(config.collectTimestamps)
  val txProfiler = Profiler(pk.Exit)(config.collectTimestamps)

  // config for packetBufDmaMaster
  // we fix here and downstream should adapt
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
  )

  val txAxisConfig = config.axisConfig.copy(userWidth = config.coreIDWidth, useUser = true)
  val txDmaConfig = AxiDmaConfig(axiConfig, txAxisConfig, tagWidth = 32, lenWidth = config.pktBufLenWidth)
  val rxDmaConfig = txDmaConfig.copy(axisConfig = rxProfiler augment config.axisConfig)

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    val cmacRxClock = ClockDomain.external("cmacRxClock")
    val cmacTxClock = ClockDomain.external("cmacTxClock")

    val hostLock = hs.retainer()

    val m_axis_tx = master(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacTxClock)
    val s_axis_rx = slave(Axi4Stream(config.axisConfig)) addTag ClockDomainTag(cmacRxClock)

    implicit val clock = csr.status.cycles

    // capture cmac rx lastFire
    val rxTimestamps = rxProfiler.timestamps.clone
    val rxLastFireCdc = PulseCCByToggle(s_axis_rx.lastFire, cmacRxClock, clockDomain)
    rxProfiler.fillSlot(rxTimestamps, pk.Entry, rxLastFireCdc)

    // CDC for tx
    val txFifo = AxiStreamAsyncFifo(txAxisConfig, frameFifo = true, depthBytes = config.roundMtu)()(clockDomain, cmacTxClock)
    txFifo.masterPort.translateInto(m_axis_tx)(_ <<? _) // ignore TUSER

    val txTimestamps = txProfiler.timestamps.clone

    val txClkArea = new ClockingArea(cmacTxClock) {
      val lastCoreIDFlow = ValidFlow(RegNext(txFifo.masterPort.user)).takeWhen(txFifo.masterPort.lastFire)
    }

    val lastCoreIDFlowCdc = txClkArea.lastCoreIDFlow.ccToggle(cmacTxClock, clockDomain)
    txProfiler.fillSlot(txTimestamps, pk.Exit, lastCoreIDFlowCdc.fire)

    // demux timestamps: generate valid for Flow[Timestamps] back into Control
    val txTimestampsFlows = Seq.tabulate(cores.length) { coreID =>
      val delayedCoreIDFlow = RegNext(lastCoreIDFlowCdc)
      ValidFlow(txTimestamps).takeWhen(delayedCoreIDFlow.valid && delayedCoreIDFlow.payload === coreID)
    }

    // CDC for rx
    // buffer incoming packet for packet length
    // FIXME: how much buffering do we need?
    val rxFifo = AxiStreamAsyncFifo(config.axisConfig, frameFifo = true, depthBytes = config.roundMtu)()(cmacRxClock, clockDomain)
    rxFifo.slavePort << s_axis_rx
    // derive cmac incoming packet length

    // report overflow
    val rxOverflow = Bool()
    val rxOverflowCdc = PulseCCByToggle(rxOverflow, cmacRxClock, clockDomain)
    csr.status.rxOverflowCount := Counter(config.regWidth bits, rxOverflowCdc)

    // extract frame length
    // TODO: attach point for (IP/UDP/RPC prognum) decoder/main pipeline.  Produce necessary info for scheduler
    //       pipeline should emit:
    //       - a control struct (length of payload stream + scheduler command + additional decoded data, e.g. RPC args)
    //       - a payload stream to DMA
    // FIXME: do we actually need more than one proc pipelines on the NIC?
    val cmacReq = s_axis_rx.frameLength.map(_.resized.toPacketLength).toStream(rxOverflow)
    val cmacReqCdc = cmacReq.clone
    // FIXME: how much buffering do we need?
    val cmacReqCdcFifo = SimpleAsyncFifo(cmacReq, cmacReqCdc, config.maxRxPktsInFlight, cmacRxClock, clockDomain)

    // round-robin dispatch to enabled CPU cores
    // TODO: replace with more complicated scheduler, based on info from the decoder
    val dispatchedCmacRx = StreamDispatcherWithEnable(
      input = cmacReqCdc,
      outputCount = cores.length,
      enableMask = csr.ctrl.dispatchMask,
      maskChanged = csr.status.dispatchMaskChanged,
    ).setName("packetLenDemux")

    // TX DMA USER: core ID to demux timestamp
    val axiDmaReadMux = new AxiDmaDescMux(txDmaConfig, numPorts = cores.length, arbRoundRobin = false)
    val axiDmaWriteMux = new AxiDmaDescMux(rxDmaConfig, numPorts = cores.length, arbRoundRobin = false)

    val axiDma = new AxiDma(axiDmaWriteMux.masterDmaConfig)
    axiDma.readDataMaster.translateInto(txFifo.slavePort) { case (fifo, dma) =>
      fifo.user := dma.user.resized
      fifo.assignUnassignedByName(dma)
    }

    rxProfiler.fillSlot(rxTimestamps, pk.AfterRxQueue, rxFifo.masterPort.lastFire)
    axiDma.writeDataSlave << rxFifo.masterPort

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    hostLock.release()

    // mux descriptors
    axiDmaReadMux.connectRead(axiDma)
    axiDmaWriteMux.connectWrite(axiDma)

    retainer.await()

    // drive mac interface of core control modules
    cores.foreach { c =>
      val cio = c.logic.ctrl.io
      cio.readDesc >> axiDmaReadMux.s_axis_desc(c.coreID)
      cio.readDescStatus <<? axiDmaReadMux.m_axis_desc_status(c.coreID)

      // dma write desc port does not have id, dest, user
      axiDmaWriteMux.s_axis_desc(c.coreID).translateFrom(cio.writeDesc)(_ <<? _)
      cio.writeDescStatus << axiDmaWriteMux.m_axis_desc_status(c.coreID)

      // TODO: we should pass the decoded control structure (e.g. RPC call num + first args)
      //       instead of just length of packet
      cio.cmacRxAlloc << dispatchedCmacRx(c.coreID)

      // exit timestamp for tx, demux'ed for this core
      cio.hostTxExitTimestamps << txTimestampsFlows(c.coreID)
    }
  }

  def packetBufDmaMaster: Axi4 = logic.axiDma.io.m_axi
}