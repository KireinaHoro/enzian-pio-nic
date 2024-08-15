package pionic

import pionic.host.HostService
import pionic.net.{TaggedProtoMetadata, ProtoMetadataType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.fsm._
import spinal.lib.misc.plugin._

import scala.language.postfixOps

object HostPacketDescType extends SpinalEnum {
  val bypass, oncRpcCall, oncRpcReply = newElement()
}

case class HostPacketDescData()(implicit config: PioNicConfig) extends Union {
  val bypassMeta = newElement(TaggedProtoMetadata())
  val oncRpcCall = newElement(new Bundle {
    val funcPtr = Bits(64 bits)
    val xid = Bits(32 bits)
    val args = Bits((config.maxHostDescSize - 8 - 4) * 8 bits)
  })
  val oncRpcReply = newElement(new Bundle {

  })
}

/**
 * Packet descriptor that eventually gets transmitted to the host (e.g. stuffed in a CL or read over
 * PCIe regs).  Translated from [[TaggedProtoMetadata]] by dropping irrelevant fields ([[CoreControlPlugin]] knows
 * if it is a bypass core or not)
 */
case class HostPacketDesc()(implicit config: PioNicConfig) extends Bundle {
  override def clone = HostPacketDesc()

  val buffer = PacketBufDesc()
  val ty = HostPacketDescType()
  val data = HostPacketDescData()

  assert(getBitsWidth <= config.maxHostDescSize * 8, s"host packet desc too big ($getBitsWidth)")
}

/**
 * Control module for PIO access from one single core.
 *
 * For RX, consumes [[TaggedProtoMetadata]] from the decoder pipeline and produces [[HostPacketDesc]] for consumption
 * by the host module (e.g. [[pionic.host.eci.EciInterfacePlugin]]) and AXI DMA descriptors for [[AxiDmaPlugin]].
 * Manages one packet buffer.
 *
 * TODO TX control path
 *
 * A [[coreID]] of 0 means that this core handles bypass traffic to/from the bypass TAP on the host.  For RX, this
 * results in all traffic being packed into [[HostPacketDescType.bypass]]; for TX, any packet that does not carry the
 * bypass type in the host descriptor is an error.
  */
class CoreControlPlugin(val coreID: Int)(implicit config: PioNicConfig) extends FiberPlugin {
  withPrefix(s"core_$coreID")
  def isBypass = coreID == 0

  lazy val dma = host[AxiDmaPlugin]
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val hs = host[HostService]
  lazy val p = host[ProfilerPlugin]

  val logic = during setup new Area {
    val rg = retains(hs.retainer) //, dma.retainer)

    awaitBuild()

    val dmaConfig = dma.dmaConfig

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
      // RX protocol:
      // - hostRxReq:      host started rx read; used for profiling
      // - hostRx.fire:    packet allocated and presented to core
      // - hostRxAck.fire: packet consumed by host; buffer entry can be freed
      val hostRxReq = in Bool()
      val hostRx = master Stream HostPacketDesc()
      val hostRxAck = slave Stream PacketBufDesc()

      // TX protocol:
      // - hostTx.fire:    host acquire tx packet buffer; maybe unused if host plugin aliases tx buffer directly (e.g. ECI)
      // - hostTxAck.fire: host prepared packet descriptor and packet can be sent
      val hostTx = master Stream PacketBufDesc()
      val hostTxAck = slave Stream HostPacketDesc()

      // from packet sink -- ingress packet metadata
      val igMetadata = slave Stream TaggedProtoMetadata()

      // TODO: to packet source -- egress packet metadata
      // val egMetadata = master Stream TaggedProtoMetadata()

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

    // tell allocator how much we need to allocate in the packet buffer
    // FIXME: what if size is zero due to header only packet?
    rxAlloc.io.allocReq << io.igMetadata.map(_.getPayloadSize)
    rxAlloc.io.freeReq </< io.hostRxAck
    io.statistics.rxAllocOccupancy := rxAlloc.io.slotOccupancy

    rxAlloc.io.allocResp.setBlocked()
    io.hostTxAck.setBlocked()
    val lastIgReq = io.igMetadata.toFlowFire.toReg()

    val rxCaptured = Reg(Stream(HostPacketDesc())).setIdle()
    // FIXME: how much buffering do we need?
    rxCaptured.queue(config.maxRxPktsInFlight) >> io.hostRx

    /** RX DMA tag used to construct [[HostPacketDesc]] after DMA.  Filled from [[TaggedProtoMetadata]] */
    case class RxDmaTag() extends Bundle {
      /** packet buffer address from allocator.  used to fill buffer in [[HostPacketDesc]] */
      val addr = PacketAddr()
      val ty = HostPacketDescType()
      val data = HostPacketDescData()

      assert(dmaConfig.tagWidth >= getBitsWidth, s"DMA tag (${dmaConfig.tagWidth} bits) too narrow to fit packet buffer address (${config.pktBufAddrWidth} bits)")
    }

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxAlloc.io.allocResp.freeRun()
          when(rxAlloc.io.allocResp.valid) {
            io.writeDesc.payload.payload.addr := rxAlloc.io.allocResp.addr.bits.resized
            io.writeDesc.payload.payload.len := lastIgReq.getPayloadSize // use the actual size instead of length of buffer

            // encode proto metadata into DMA tag
            val tag = RxDmaTag()
            tag.addr := rxAlloc.io.allocResp.addr
            if (isBypass) {
              tag.ty := HostPacketDescType.bypass
              tag.data.bypassMeta.get := lastIgReq
            } else {
              switch (lastIgReq.ty) {
                is (ProtoMetadataType.oncRpcCall) {
                  tag.ty := HostPacketDescType.oncRpcCall

                  tag.data.oncRpcCall.funcPtr := lastIgReq.metadata.oncRpcCall.funcPtr
                  tag.data.oncRpcCall.xid := lastIgReq.metadata.oncRpcCall.hdr.xid
                  tag.data.oncRpcCall.args := lastIgReq.metadata.oncRpcCall.args
                }
                default {
                  report("unsupported protocol metadata type on non-bypass packet")
                }
              }
            }

            io.writeDesc.payload.payload.tag := tag.asBits

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
              // fill host descriptor
              val tag = RxDmaTag()
              tag.assignFromBits(io.writeDescStatus.tag)

              rxCaptured.buffer.addr.bits := tag.addr
              rxCaptured.buffer.size.bits := io.writeDescStatus.len
              rxCaptured.ty := tag.ty
              rxCaptured.data := tag.data
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
      p.RxCoreReadStart -> io.hostRxReq.rise(False),
      p.RxCoreReadFinish -> io.hostRx.fire,
      p.RxCoreCommit -> io.hostRxAck.fire,
    )

    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          io.hostTxAck.freeRun()
          when(io.hostTxAck.valid) {
            io.readDesc.payload.payload.addr := io.hostTxAck.buffer.addr
            io.readDesc.payload.payload.len := io.hostTxAck.buffer.size
            // TODO: hand off io.hostTxAck.{ty,data} to egMetadata
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