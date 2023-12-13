package pionic

import axi._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._
import spinal.lib.fsm._

case class GlobalControlBundle(implicit config: PioNicConfig) extends Bundle {
  val rxBlockCycles = UInt(config.regWidth bits)
}

case class PacketAddr(implicit config: PioNicConfig) extends Bundle {
  val bits = UInt(config.pktBufAddrWidth bits)
}

case class PacketLength(implicit config: PioNicConfig) extends Bundle {
  val bits = UInt(config.pktBufAddrWidth bits)
}

case class PacketDesc(implicit config: PioNicConfig) extends Bundle {
  val addr = PacketAddr()
  val size = PacketLength()
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
    val hostTxAck = slave Flow PacketLength() // actual length of the packet

    // from CMAC Axis -- ingress packet
    val cmacRxAlloc = slave Stream PacketLength()

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
  io.hostTx.addr.bits := pktBufTxBase
  io.hostTx.size.bits := config.mtu

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
        rxAlloc.io.allocResp.ready := True
      }
      whenIsActive {
        when(rxAlloc.io.allocResp.valid) {
          goto(stateAllocated)
        }
      }
      onExit {
        rxAlloc.io.allocResp.ready := False
      }
    }
    val stateAllocated: State = new State {
      whenIsActive {
        io.writeDesc.payload.payload.addr := rxAllocated.addr.bits.resized
        io.writeDesc.payload.payload.len := rxAllocated.size.bits
        io.writeDesc.payload.payload.tag := rxAllocated.addr.bits.resized
        io.writeDesc.valid := True
        when(io.writeDesc.ready) {
          goto(stateWaitDma)
        }
      }
      onExit {
        io.writeDesc.setIdle
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
        rxCaptured.payload.addr.bits := rxDMAed.tag.resized
        rxCaptured.payload.size.bits := rxDMAed.len
        rxCaptured.valid := True
        when(rxCaptured.ready) {
          goto(stateIdle)
        }
      }
      onExit {
        rxCaptured.setIdle
      }
    }
  }

  val txAckedLength = io.hostTxAck.toReg

  val writeFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        when(io.hostTxAck.fire) {
          goto(statePrepared)
        }
      }
    }
    val statePrepared: State = new State {
      whenIsActive {
        io.readDesc.payload.payload.addr := io.hostTx.addr.bits.resized
        io.readDesc.payload.payload.len := txAckedLength.bits
        io.readDesc.payload.payload.tag := 0
        io.readDesc.valid := True
        when(io.readDesc.ready) {
          goto(stateWaitDma)
        }
      }
      onExit {
        io.readDesc.setIdle
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

  def driveFrom(busCtrl: BusSlaveFactory, baseAddress: BigInt)(globalCtrl: GlobalControlBundle, rdMux: AxiDmaDescMux, wrMux: AxiDmaDescMux, cmacRx: Stream[PacketLength]) = new Area {
    io.globalCtrl := globalCtrl

    io.readDesc >> rdMux.s_axis_desc(coreID)
    io.readDescStatus <<? rdMux.m_axis_desc_status(coreID)

    // dma write desc port does not have id, dest, user
    wrMux.s_axis_desc(coreID).translateFrom(io.writeDesc)(_ <<? _)
    io.writeDescStatus << wrMux.m_axis_desc_status(coreID)

    io.cmacRxAlloc << cmacRx

    private val regBytes: Int = config.regWidth / 8

    busCtrl.readStreamBlockCycles(io.hostRxNext, baseAddress, globalCtrl.rxBlockCycles, config.maxRxBlockCycles)
    busCtrl.driveStream(io.hostRxNextAck, baseAddress + regBytes)

    busCtrl.read(io.hostTx, baseAddress + regBytes * 2)
    busCtrl.driveFlow(io.hostTxAck, baseAddress + regBytes * 3)
  }
}
