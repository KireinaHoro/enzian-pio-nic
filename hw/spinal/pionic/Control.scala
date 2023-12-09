package pionic

import axi.{AxiDma, AxiDmaConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._
import spinal.lib.fsm._

case class GlobalControlBundle(implicit config: PioNicConfig) extends Bundle {
  val rxBlockCycles = UInt(config.regWidth bits)
}

case class PacketDesc(implicit config: PioNicConfig) extends Bundle {
  val addr = UInt(config.pktBufAddrWidth bits)
  val size = UInt(config.pktBufAddrWidth bits)
}

// Control module for PIO access from one single core
// Would manage one packet buffer
class PioCoreControl(dmaConfig: AxiDmaConfig, coreID: Int)(implicit config: PioNicConfig) extends Component {
  val io = new Bundle {
    // config from host, but driven only once at global control
    val globalCtrl = in(GlobalControlBundle())

    // regs for host
    val hostRxNext = master Stream PacketDesc()
    val hostRxNextAck = slave Stream PacketDesc()

    val hostTx = out(PacketDesc())
    val hostTxAck = slave Flow UInt(config.pktBufAddrWidth bits) // actual length of the packet

    // from CMAC Axis -- ingress packet
    val cmacRxAlloc = slave Stream UInt(config.pktBufAddrWidth bits)

    // driver for DMA control
    val readDesc = master(dmaConfig.readDescBus)
    val readDescStatus = slave(dmaConfig.readDescStatusBus)
    val writeDesc = master(dmaConfig.writeDescBus)
    val writeDescStatus = slave(dmaConfig.writeDescStatusBus)
  }

  assert(dmaConfig.tagWidth >= config.pktBufAddrWidth, s"DMA tag (${dmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")

  val pktBufBase = coreID * config.pktBufSizePerCore
  val pktBufTxBase = pktBufBase + config.pktBufSizePerCore - config.mtu
  // we reserve one packet for TX
  io.hostTx.addr := pktBufTxBase
  io.hostTx.size := config.mtu

  val rxAlloc = new PacketAlloc(pktBufBase, pktBufTxBase - pktBufBase)
  rxAlloc.io.allocReq << io.cmacRxAlloc
  rxAlloc.io.freeReq << io.hostRxNextAck

  val rxAllocated = rxAlloc.io.allocResp.toReg
  val rxDMAed = io.writeDescStatus.toReg

  val rxCaptured = Stream(PacketDesc()).setIdle
  rxCaptured.queue(config.maxRxPktsInFlight) >> io.hostRxNext

  io.readDesc.setIdle
  io.writeDesc.setIdle

  val readFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      onEntry {
        io.writeDesc.setIdle()
        rxCaptured.setIdle()
        rxAlloc.io.allocResp.ready := True
      }
      whenIsActive {
        when(rxAlloc.io.allocResp.fire) {
          goto(stateAllocated)
        }
      }
      onExit {
        rxAlloc.io.allocResp.ready := False
      }
    }
    val stateAllocated: State = new State {
      whenIsActive {
        io.writeDesc.payload.payload.addr := rxAllocated.addr.resized
        io.writeDesc.payload.payload.len := rxAllocated.size
        io.writeDesc.payload.payload.tag := rxAllocated.addr.resized
        io.writeDesc.valid := True
        when(io.writeDesc.fire) {
          goto(stateWaitDma)
        }
      }
      onExit {
        io.writeDesc.valid := False
      }
    }
    val stateWaitDma: State = new State {
      whenIsActive {
        when(io.writeDescStatus.fire) {
          when(io.writeDescStatus.payload.error === 0) {
            goto(stateEnqueuePkt)
          } otherwise {
            // FIXME: report error status
            goto(stateIdle)
          }
        }
      }
    }
    val stateEnqueuePkt: State = new State {
      whenIsActive {
        rxCaptured.payload.addr := rxDMAed.tag.resized
        rxCaptured.payload.size := rxDMAed.len
        rxCaptured.valid := True
        when(rxCaptured.fire) {
          goto(stateIdle)
        }
      }
    }
  }

  val txAckedLength = io.hostTxAck.toReg

  val writeFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      onEntry {
        io.readDesc.setIdle()
      }
      whenIsActive {
        when(io.hostTxAck.fire) {
          goto(statePrepared)
        }
      }
    }
    val statePrepared: State = new State {
      whenIsActive {
        io.readDesc.payload.payload.addr := io.hostTx.addr.resized
        io.readDesc.payload.payload.len := txAckedLength
        io.readDesc.payload.payload.tag := 0
        io.readDesc.valid := True
        when(io.readDesc.fire) {
          goto(stateWaitDma)
        }
      }
    }
    val stateWaitDma: State = new State {
      whenIsActive {
        when(io.readDescStatus.fire) {
          // FIXME: report error status
          goto(stateIdle)
        }
      }
    }
  }

  def driveFrom(busCtrl: BusSlaveFactory, baseAddress: BigInt)(globalCtrl: GlobalControlBundle, dma: AxiDma, cmacRx: Stream[UInt]) = new Area {
    io.globalCtrl := globalCtrl

    io.readDesc >> dma.io.s_axis_read_desc
    io.readDescStatus << dma.io.m_axis_read_desc_status
    io.writeDesc >> dma.io.s_axis_write_desc
    io.writeDescStatus << dma.io.m_axis_write_desc_status

    io.cmacRxAlloc << cmacRx

    private val regBytes: Int = config.regWidth / 8

    busCtrl.readStreamBlockCycles(io.hostRxNext, baseAddress, globalCtrl.rxBlockCycles, config.maxRxBlockCycles)
    busCtrl.driveStream(io.hostRxNextAck, baseAddress + regBytes)

    busCtrl.read(io.hostTx, baseAddress + regBytes * 2)
    busCtrl.driveFlow(io.hostTxAck, baseAddress + regBytes * 3)
  }
}
