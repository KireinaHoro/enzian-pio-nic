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
    val hostTxAck = slave Stream PacketLength() // actual length of the packet

    // from CMAC Axis -- ingress packet
    val cmacRxAlloc = slave Stream PacketLength()

    // driver for DMA control
    val readDesc = master(dmaConfig.readDescBus)
    val readDescStatus = slave(dmaConfig.readDescStatusBus)
    val writeDesc = master(dmaConfig.writeDescBus)
    val writeDescStatus = slave(dmaConfig.writeDescStatusBus)

    // statistics
    val statistics = out(new Bundle {
      val rxRetiredPacketCount = Reg(UInt(config.regWidth bits)) init 0
      val txRetiredPacketCount = Reg(UInt(config.regWidth bits)) init 0
      val rxDmaErrorCount = Reg(UInt(config.regWidth bits)) init 0
      val txDmaErrorCount = Reg(UInt(config.regWidth bits)) init 0
    })
  }

  def inc(reg: UInt) = reg := reg + 1

  io.writeDesc.payload.setAsReg()
  io.writeDesc.valid.setAsReg() init False
  io.readDesc.payload.setAsReg()
  io.readDesc.valid.setAsReg() init False

  assert(dmaConfig.tagWidth >= config.pktBufAddrWidth, s"DMA tag (${dmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")

  val pktBufBase = coreID * config.pktBufSizePerCore
  val pktBufTxBase = pktBufBase + config.pktBufSizePerCore - config.mtu
  // we reserve one packet for TX
  io.hostTx.addr.bits := pktBufTxBase
  io.hostTx.size.bits := config.mtu

  val rxAlloc = PacketAlloc(pktBufBase, pktBufTxBase - pktBufBase)
  rxAlloc.io.allocReq << io.cmacRxAlloc
  rxAlloc.io.freeReq </< io.hostRxNextAck

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
          inc(io.statistics.rxRetiredPacketCount)
          goto(stateIdle)
        }
      }
    }
  }

  val txFsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        io.hostTxAck.ready := True
        when(io.hostTxAck.valid) {
          io.readDesc.payload.payload.addr := io.hostTx.addr.bits.resized
          io.readDesc.payload.payload.len := io.hostTxAck.payload.bits
          io.readDesc.payload.payload.tag := 0
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
            inc(io.statistics.txRetiredPacketCount)
          } otherwise {
            inc(io.statistics.txDmaErrorCount)
          }
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

    println(f"$baseAddress%#x\t: hostRxNext")
    busCtrl.readStreamBlockCycles(io.hostRxNext, baseAddress, globalCtrl.rxBlockCycles, config.maxRxBlockCycles)
    println(f"${baseAddress + regBytes}%#x\t: hostRxNextAck")
    busCtrl.driveStream(io.hostRxNextAck, baseAddress + regBytes)

    println(f"${baseAddress + regBytes * 2}%#x\t: hostTx")
    busCtrl.read(io.hostTx, baseAddress + regBytes * 2)
    println(f"${baseAddress + regBytes * 3}%#x\t: hostTxAck")
    busCtrl.driveStream(io.hostTxAck, baseAddress + regBytes * 3)

    io.statistics.elements.zipWithIndex.foreach { case ((name, data), idx) =>
      val addr = baseAddress + regBytes * (4 + idx)
      println(f"$addr%#x\t: $name")
      busCtrl.read(data, addr)
    }
  }
}
