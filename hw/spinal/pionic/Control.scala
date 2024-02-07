package pionic

import jsteward.blocks.axi._
import jsteward.blocks.misc._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._
import spinal.lib.fsm._

import scala.language.postfixOps

case class GlobalControlBundle()(implicit config: PioNicConfig) extends Bundle {
  override def clone = GlobalControlBundle()

  val rxBlockCycles = UInt(config.rxBlockCyclesWidth bits)
}

case class GlobalStatusBundle()(implicit config: PioNicConfig) extends Bundle {
  override def clone = GlobalStatusBundle()

  val cyclesCount = CycleClock(config.regWidth bits)
}

case class PacketAddr()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketAddr()

  val bits = UInt(config.pktBufAddrWidth bits)
}

case class PacketLength()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketLength()

  val bits = UInt(config.pktBufLenWidth bits)
}

case class PacketDesc()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketDesc()

  val addr = PacketAddr()
  val size = PacketLength()
}

// Control module for PIO access from one single core
// Would manage one packet buffer
class PioCoreControl(rxDmaConfig: AxiDmaConfig, txDmaConfig: AxiDmaConfig, coreID: Int, rxProfilerParent: Profiler = null, txProfilerParent: Profiler = null)(implicit config: PioNicConfig) extends Component {
  val AfterDmaWrite = NamedType(Timestamp) // time in dma mux & writing
  val AfterRxCommit = NamedType(Timestamp) // time in core dispatch queuing
  val ReadStart = NamedType(Timestamp) // start time of read, to measure queuing / stalling time
  val AfterRead = NamedType(Timestamp) // after read finish & core start processing
  val rxProfiler = Profiler(ReadStart, AfterDmaWrite, AfterRead, AfterRxCommit)(config.collectTimestamps, rxProfilerParent)

  val Acquire = NamedType(Timestamp)
  val AfterTxCommit = NamedType(Timestamp)
  val AfterDmaRead = NamedType(Timestamp)
  val txProfiler = Profiler(Acquire, AfterTxCommit, AfterDmaRead)(config.collectTimestamps, txProfilerParent)

  val pktBufBase = coreID * config.pktBufSizePerCore
  val pktBufTxSize = config.roundMtu
  val pktBufTxBase = pktBufBase + config.pktBufSizePerCore - pktBufTxSize

  val allocReset = Bool()
  val rxAlloc = new ResetArea(allocReset, true) {
    private val instance = PacketAlloc(pktBufBase, pktBufTxBase - pktBufBase)
    val io = instance.io
  }
  println(f"Tx Size $pktBufTxSize @ $pktBufTxBase%#x")

  val io = new Bundle {
    // config from host, but driven only once at global control
    val globalCtrl = in(GlobalControlBundle())
    // global status (e.g. cycle count for tagging packet times)
    val globalStatus = in(GlobalStatusBundle())

    // regs for host
    val hostRxNext = master Stream PacketDesc()
    val hostRxNextAck = slave Stream PacketDesc()
    val hostRxLastProfile = out(rxProfiler.timestamps.clone).setAsReg()
    val hostRxNextReq = in Bool()

    val hostTx = master Stream PacketDesc()
    val hostTxAck = slave Stream PacketLength() // actual length of the packet
    val hostTxLastProfile = out(txProfiler.timestamps.clone).setAsReg()
    val hostTxExitTimestamps = slave(Flow(txProfilerParent.timestamps.clone))

    // from CMAC Axis -- ingress packet
    val cmacRxAlloc = slave Stream PacketLength()

    // driver for DMA control
    val readDesc = master(txDmaConfig.readDescBus).setOutputAsReg()
    val readDescStatus = slave(txDmaConfig.readDescStatusBus)
    val writeDesc = master(rxDmaConfig.writeDescBus).setOutputAsReg()
    val writeDescStatus = slave(rxDmaConfig.writeDescStatusBus)

    // reset for packet allocator
    val allocReset = in(Bool())

    // statistics
    val statistics = out(new Bundle {
      val rxPacketCount = Reg(UInt(config.regWidth bits)) init 0
      val txPacketCount = Reg(UInt(config.regWidth bits)) init 0
      val rxDmaErrorCount = Reg(UInt(config.regWidth bits)) init 0
      val txDmaErrorCount = Reg(UInt(config.regWidth bits)) init 0
      val rxAllocOccupancy = rxAlloc.io.slotOccupancy.clone
    })
  }
  rxProfiler.regInit(io.hostRxLastProfile)
  txProfiler.regInit(io.hostTxLastProfile)

  implicit val clock = io.globalStatus.cyclesCount

  allocReset := io.allocReset

  def inc(reg: UInt) = reg := reg + 1

  io.writeDesc.valid init False
  io.readDesc.valid init False

  assert(rxDmaConfig.tagWidth >= config.pktBufAddrWidth, s"Rx DMA tag (${rxDmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")
  assert(txDmaConfig.tagWidth >= config.pktBufAddrWidth, s"Tx DMA tag (${txDmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")

  // we reserve one packet for TX
  io.hostTx.addr.bits := pktBufTxBase
  io.hostTx.size.bits := pktBufTxSize
  io.hostTx.valid := True

  rxAlloc.io.allocReq << io.cmacRxAlloc
  rxAlloc.io.freeReq </< io.hostRxNextAck
  io.statistics.rxAllocOccupancy := rxAlloc.io.slotOccupancy

  rxAlloc.io.allocResp.setBlocked
  io.hostTxAck.setBlocked
  val allocReq = io.cmacRxAlloc.toFlowFire.toReg

  val rxCaptured = Reg(Stream(PacketDesc())).setIdle
  rxCaptured.queue(config.maxRxPktsInFlight) >> io.hostRxNext

  val rxFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        rxAlloc.io.allocResp.ready := True
        when(rxAlloc.io.allocResp.valid) {
          io.writeDesc.payload.payload.addr := rxAlloc.io.allocResp.addr.bits.resized
          io.writeDesc.payload.payload.len := allocReq.bits // use the actual size instead of length of buffer
          io.writeDesc.payload.payload.tag := rxAlloc.io.allocResp.addr.bits.resized
          io.writeDesc.valid := True
          goto(stateAllocated)
        }
      }
    }
    val stateAllocated: State = new State {
      whenIsActive {
        rxAlloc.io.allocResp.ready := False
        when(io.writeDesc.ready) {
          io.writeDesc.setIdle
          goto(stateWaitDma)
        }
      }
    }
    val stateWaitDma: State = new State {
      whenIsActive {
        when(io.writeDescStatus.fire) {
          when(io.writeDescStatus.payload.error === 0) {
            rxCaptured.payload.addr.bits := io.writeDescStatus.tag.resized
            rxCaptured.payload.size.bits := io.writeDescStatus.len
            rxCaptured.valid := True

            rxProfiler.collectInto(io.writeDescStatus.user.asBits, io.hostRxLastProfile)
            rxProfiler.fillSlot(io.hostRxLastProfile, AfterDmaWrite, True)
            goto(stateEnqueuePkt)
          } otherwise {
            inc(io.statistics.rxDmaErrorCount)
            goto(stateIdle)
          }
        }
      }
    }
    val stateEnqueuePkt: State = new State {
      whenIsActive {
        when(rxCaptured.ready) {
          rxCaptured.setIdle
          inc(io.statistics.rxPacketCount)
          goto(stateIdle)
        }
      }
    }
  }

  rxProfiler.fillSlots(io.hostRxLastProfile,
    ReadStart -> io.hostRxNextReq.rise(False),
    AfterRead -> io.hostRxNext.fire,
    AfterRxCommit -> io.hostRxNextAck.fire,
  )

  val txFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        io.hostTxAck.ready := True
        when(io.hostTxAck.valid) {
          io.readDesc.payload.payload.addr := io.hostTx.addr.bits.resized
          io.readDesc.payload.payload.len := io.hostTxAck.payload.bits
          io.readDesc.payload.payload.tag := 0
          io.readDesc.payload.user := coreID
          io.readDesc.valid := True
          goto(statePrepared)
        }
      }
    }
    val statePrepared: State = new State {
      whenIsActive {
        io.hostTxAck.ready := False
        when(io.readDesc.ready) {
          io.readDesc.setIdle
          goto(stateWaitDma)
        }
      }
    }
    val stateWaitDma: State = new State {
      whenIsActive {
        when(io.readDescStatus.fire) {
          when(io.readDescStatus.payload.error === 0) {
            inc(io.statistics.txPacketCount)

            txProfiler.fillSlot(io.hostTxLastProfile, AfterDmaRead, True)
          } otherwise {
            inc(io.statistics.txDmaErrorCount)
          }
          goto(stateIdle)
        }
      }
    }
  }

  when(io.hostTxExitTimestamps.fire) {
    txProfiler.collectInto(io.hostTxExitTimestamps.payload.asBits, io.hostTxLastProfile)
  }
  txProfiler.fillSlots(io.hostTxLastProfile,
    Acquire -> io.hostTx.fire,
    AfterTxCommit -> io.hostTxAck.fire,
  )

  def driveFrom(busCtrl: BusSlaveFactory, baseAddress: BigInt)(globalCtrl: GlobalControlBundle, rdMux: AxiDmaDescMux, wrMux: AxiDmaDescMux, cmacRx: Stream[PacketLength], txTimestamps: Flow[Timestamps])(implicit globalStatus: GlobalStatusBundle) = new Area {
    io.globalCtrl := globalCtrl
    io.globalStatus := globalStatus

    io.readDesc >> rdMux.s_axis_desc(coreID)
    io.readDescStatus <<? rdMux.m_axis_desc_status(coreID)

    // dma write desc port does not have id, dest, user
    wrMux.s_axis_desc(coreID).translateFrom(io.writeDesc)(_ <<? _)
    io.writeDescStatus << wrMux.m_axis_desc_status(coreID)

    io.cmacRxAlloc << cmacRx

    private val alloc = config.allocFactory("control", coreID)(baseAddress, 0x1000, config.regWidth / 8)

    val rxNextAddr = alloc("hostRxNext")
    busCtrl.readStreamBlockCycles(io.hostRxNext, rxNextAddr, globalCtrl.rxBlockCycles)
    busCtrl.driveStream(io.hostRxNextAck, alloc("hostRxNextAck"))

    // on read primitive (AR for AXI), set hostRxNextReq for timing ReadStart
    io.hostRxNextReq := False
    busCtrl.onReadPrimitive(SingleMapping(rxNextAddr), haltSensitive = false, "read request issued") {
      io.hostRxNextReq := True
    }

    // exit timestamp for tx, demux'ed for this core
    io.hostTxExitTimestamps << txTimestamps

    // should not block; only for profiling (to use ready signal)
    busCtrl.readStreamNonBlocking(io.hostTx, alloc("hostTx"))
    busCtrl.driveStream(io.hostTxAck, alloc("hostTxAck"))

    busCtrl.driveAndRead(io.allocReset, alloc("allocReset")) init false

    io.statistics.elements.foreach { case (name, data) =>
      data match {
        case d: UInt => busCtrl.read(d, alloc(name))
        case v: Vec[_] => v zip config.pktBufAllocSizeMap.map(_._1) foreach { case (elem, slotSize) =>
          busCtrl.read(elem, alloc(name, subName = s"upTo$slotSize"))
        }
        case _ =>
      }
    }

    // rx profile results
    if (config.collectTimestamps) {
      io.hostRxLastProfile.storage.foreach { case (namedType, data) =>
        busCtrl.read(data, alloc("hostRxLastProfile", subName = namedType.getName()))
      }
      io.hostTxLastProfile.storage.foreach { case (namedType, data) =>
        busCtrl.read(data, alloc("hostTxLastProfile", subName = namedType.getName()))
      }
    }
  }
}
