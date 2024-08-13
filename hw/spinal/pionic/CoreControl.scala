package pionic

import jsteward.blocks.axi._
import jsteward.blocks.misc._
import pionic.host.HostService
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._
import spinal.lib.fsm._
import spinal.lib.misc.plugin._

import scala.language.postfixOps

// address of arbitrary data in the packet buffer
case class PacketAddr()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketAddr()

  val bits = UInt(config.pktBufAddrWidth bits)
}

case class PacketLength()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketLength()

  val bits = UInt(config.pktBufLenWidth bits)
}

// Description of a packet; content of packet is dependent on what protocol it carries.
// FIXME: this should be renamed to highlight difference between a DecodePipelineDesc and a CoreControlDesc
case class PacketDesc()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketDesc()

  val addr = PacketAddr()
  val size = PacketLength()
}

// Control module for PIO access from one single core
// Would manage one packet buffer
class CoreControlPlugin(val coreID: Int)(implicit config: PioNicConfig) extends FiberPlugin {
  withPrefix(s"core_$coreID")

  lazy val dma = host[AxiDmaPlugin]
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val hs = host[HostService]
  lazy val p = host[ProfilerPlugin]

  val logic = during setup new Area {
    val rg = retains(hs.retainer) //, dma.retainer)

    awaitBuild()

    val dmaConfig = dma.dmaConfig
    assert(dmaConfig.tagWidth >= config.pktBufAddrWidth, s"DMA tag (${dmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")

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
      // regs for host
      val hostRxNext = master Stream PacketDesc()
      val hostRxNextAck = slave Stream PacketDesc()
      val hostRxNextReq = in Bool()

      val hostTx = master Stream PacketDesc()
      val hostTxAck = slave Stream PacketLength() // actual length of the packet

      // from CMAC Axis -- ingress packet
      val cmacRxAlloc = slave Stream PacketLength()

      // driver for DMA control
      val readDesc = master(dmaConfig.readDescBus).setOutputAsReg()
      val readDescStatus = slave(dmaConfig.readDescStatusBus)
      val writeDesc = master(dmaConfig.writeDescBus).setOutputAsReg()
      val writeDescStatus = slave(dmaConfig.writeDescStatusBus)

      // reset for packet allocator
      val allocReset = in(Bool())

      // statistics
      val statistics = out(new Bundle {
        val rxPacketCount = Counter(config.regWidth bits)
        val txPacketCount = Counter(config.regWidth bits)
        val rxDmaErrorCount = Counter(config.regWidth bits)
        val txDmaErrorCount = Counter(config.regWidth bits)
        val rxAllocOccupancy = rxAlloc.io.slotOccupancy.clone
      })
    }.setAsDirectionLess()

    allocReset := io.allocReset

    io.writeDesc.valid init False
    io.readDesc.valid init False

    // we reserve one packet for TX
    io.hostTx.addr.bits := pktBufTxBase
    io.hostTx.size.bits := pktBufTxSize
    io.hostTx.valid := True

    rxAlloc.io.allocReq << io.cmacRxAlloc
    rxAlloc.io.freeReq </< io.hostRxNextAck
    io.statistics.rxAllocOccupancy := rxAlloc.io.slotOccupancy

    rxAlloc.io.allocResp.setBlocked()
    io.hostTxAck.setBlocked()
    val allocReq = io.cmacRxAlloc.toFlowFire.toReg

    val rxCaptured = Reg(Stream(PacketDesc())).setIdle
    // FIXME: how much buffering do we need?
    rxCaptured.queue(config.maxRxPktsInFlight) >> io.hostRxNext

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxAlloc.io.allocResp.freeRun()
          when(rxAlloc.io.allocResp.valid) {
            io.writeDesc.payload.payload.addr := rxAlloc.io.allocResp.addr.bits.resized
            io.writeDesc.payload.payload.len := allocReq.bits // use the actual size instead of length of buffer
            io.writeDesc.payload.payload.tag := rxAlloc.io.allocResp.addr.bits.resized
            io.writeDesc.valid := True
            goto(allocated)
          }
        }
      }
      val allocated: State = new State {
        whenIsActive {
          rxAlloc.io.allocResp.ready := False
          when(io.writeDesc.ready) {
            io.writeDesc.setIdle()
            goto(waitDma)
          }
        }
      }
      val waitDma: State = new State {
        whenIsActive {
          when(io.writeDescStatus.fire) {
            when(io.writeDescStatus.payload.error === 0) {
              rxCaptured.payload.addr.bits := io.writeDescStatus.tag.resized
              rxCaptured.payload.size.bits := io.writeDescStatus.len
              rxCaptured.valid := True

              p.profile(p.RxAfterDmaWrite -> True)
              goto(enqueuePkt)
            } otherwise {
              io.statistics.rxDmaErrorCount.increment()
              goto(idle)
            }
          }
        }
      }
      val enqueuePkt: State = new State {
        whenIsActive {
          when(rxCaptured.ready) {
            rxCaptured.setIdle()
            io.statistics.rxPacketCount.increment()
            goto(idle)
          }
        }
      }
    }

    p.profile(
      p.RxCoreReadStart -> io.hostRxNextReq.rise(False),
      p.RxCoreReadFinish -> io.hostRxNext.fire,
      p.RxCoreCommit -> io.hostRxNextAck.fire,
    )

    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          io.hostTxAck.freeRun()
          when(io.hostTxAck.valid) {
            io.readDesc.payload.payload.addr := io.hostTx.addr.bits.resized
            io.readDesc.payload.payload.len := io.hostTxAck.payload.bits
            io.readDesc.payload.payload.tag := 0
            io.readDesc.valid := True
            goto(prepared)
          }
        }
      }
      val prepared: State = new State {
        whenIsActive {
          io.hostTxAck.ready := False
          when(io.readDesc.ready) {
            io.readDesc.setIdle()
            goto(waitDma)
          }
        }
      }
      val waitDma: State = new State {
        whenIsActive {
          when(io.readDescStatus.fire) {
            when(io.readDescStatus.payload.error === 0) {
              io.statistics.txPacketCount.increment()

              p.profile(p.TxAfterDmaRead -> True)
            } otherwise {
              io.statistics.txDmaErrorCount.increment()
            }
            goto(idle)
          }
        }
      }
    }

    p.profile(
      // FIXME: this not reliable since hostTx sits in the same 512B word as other regs, so a read on other regs will also trigger this
      p.TxCoreAcquire -> io.hostTx.fire,
      p.TxCoreCommit -> io.hostTxAck.fire,
    )

    def connectControl(busCtrl: BusSlaveFactory, alloc: String => BigInt): Unit = {
      busCtrl.driveAndRead(io.allocReset, alloc("allocReset")) init false
    }

    def reportStatistics(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
      io.statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc(name, ""))
          case v: Vec[_] => v zip config.pktBufAllocSizeMap.map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc(name, s"upTo$slotSize"))
          }
          case _ =>
        }
      }
    }

    rg.release()
  }
}