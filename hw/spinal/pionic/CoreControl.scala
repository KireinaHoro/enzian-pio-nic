package pionic

import jsteward.blocks.misc.{RegAllocatorFactory, RegBlockAlloc}
import pionic.host.HostService
import pionic.net.{ProtoPacketDescType, TaggedProtoPacketDesc}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._

import scala.language.postfixOps

object HostPacketDescType extends SpinalEnum {
  val error, bypass, oncRpcCall, oncRpcReply = newElement()

  def addMackerel(f: RegAllocatorFactory)(implicit c: ConfigDatabase) = {
    f.addMackerelEpilogue(getClass,
      s"""
        |constants host_packet_desc_type width(${Widths.bptw}) "Host Packet Descriptor Type" {
        |  error         = 0b00 "Error";
        |  bypass        = 0b01 "Bypass";
        |  onc_rpc_call  = 0b10 "ONC-RPC Call";
        |  onc_rpc_reply = 0b11 "ONC-RPC Reply";
        |};""".stripMargin)
  }
}

/** RX DMA tag used to construct [[HostPacketDesc]] after DMA.  Filled from [[TaggedProtoPacketDesc]] */
case class RxDmaTag()(implicit c: ConfigDatabase) extends Bundle {
  /** packet buffer address from allocator.  used to fill buffer in [[HostPacketDesc]] */
  val addr = PacketAddr()
  val ty = HostPacketDescType()
  val data = HostPacketDescData()
}

case class OncRpcCallData()(implicit c: ConfigDatabase) extends Bundle {
  val funcPtr = Bits(64 bits)
  val xid = Bits(32 bits)
  val args = Bits(Widths.oargw bits)
}

case class HostBypassHeaders()(implicit c: ConfigDatabase) extends Bundle {
  val ty = ProtoPacketDescType()
  val hdr = Bits(Widths.bphw bits)
}

case class HostPacketDescData()(implicit c: ConfigDatabase) extends Union {
  val bypassMeta = newElement(HostBypassHeaders())
  val oncRpcCall = newElement(OncRpcCallData())
}

/**
 * Packet descriptor that eventually gets transmitted to the host (e.g. stuffed in a CL or read over
 * PCIe regs).  Translated from [[pionic.net.TaggedProtoPacketDesc]] by dropping irrelevant fields ([[CoreControlPlugin]] knows
 * if it is a bypass core or not)
 */
case class HostPacketDesc()(implicit c: ConfigDatabase) extends Bundle {
  override def clone = HostPacketDesc()

  val buffer = PacketBufDesc()
  val ty = HostPacketDescType()
  val data = HostPacketDescData()
}

/**
 * Control module for PIO access from one single core.
 *
 * For RX, consumes [[TaggedProtoPacketDesc]] from the decoder pipeline and produces [[HostPacketDesc]] for consumption
 * by the host module (e.g. [[pionic.host.eci.EciInterfacePlugin]]) and AXI DMA descriptors for [[AxiDmaPlugin]].
 * Manages one packet buffer.
 *
 * TODO TX control path
 *
 * A [[coreID]] of 0 means that this core handles bypass traffic to/from the bypass TAP on the host.  For RX, this
 * results in all traffic being packed into [[HostPacketDescType.bypass]]; for TX, any packet that does not carry the
 * bypass type in the host descriptor is an error.
  */
class CoreControlPlugin(val coreID: Int) extends PioNicPlugin {
  withPrefix(s"core_$coreID")
  def isBypass = coreID == 0

  // FIXME: alignment on CPU?
  postConfig("host packet desc data width", HostPacketDescData().getBitsWidth)
  postConfig("host packet desc type width", HostPacketDescType().getBitsWidth)
  // FIXME: think of a way to automate this -- with reflection?
  // TODO: generic framework to dump Bundle/Union structure
  postConfig("host packet desc type error", 0)
  postConfig("host packet desc type bypass", 1)
  postConfig("host packet desc type onc rpc call", 2)
  postConfig("host packet desc type onc rpc reply", 3)

  postConfig("proto packet desc type width", ProtoPacketDescType().getBitsWidth)
  postConfig("proto packet desc type ethernet", 0)
  postConfig("proto packet desc type ip", 1)
  postConfig("proto packet desc type udp", 2)
  postConfig("proto packet desc type onc rpc call", 3)

  lazy val dma = host[AxiDmaPlugin]
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val hs = host[HostService]
  lazy val p = host[ProfilerPlugin]

  lazy val pktBufSizePerCore = c[Int]("pkt buf size per core")

  val logic = during setup new Area {
    val rg = retains(hs.retainer) //, dma.retainer)

    awaitBuild()

    val dmaConfig = dma.dmaConfig

    val pktBufBase = coreID * pktBufSizePerCore
    val pktBufTxSize = roundMtu
    val pktBufTxBase = pktBufBase + pktBufSizePerCore - pktBufTxSize

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
      val igMetadata = slave Stream TaggedProtoPacketDesc()

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
        val rxPacketCount = Reg(UInt(regWidth bits)) init 0
        val txPacketCount = Reg(UInt(regWidth bits)) init 0
        val rxDmaErrorCount = Reg(UInt(regWidth bits)) init 0
        val txDmaErrorCount = Reg(UInt(regWidth bits)) init 0
        val rxAllocOccupancy = rxAlloc.io.slotOccupancy.clone
      })
    }.setAsDirectionLess()

    def inc(f: io.statistics.type => UInt) = {
      f(io.statistics) := f(io.statistics) + 1
    }

    allocReset := io.allocReset

    io.writeDesc.valid init False
    io.readDesc.valid init False

    // we reserve one packet for TX
    io.hostTx.addr.bits := pktBufTxBase
    io.hostTx.size.bits := pktBufTxSize
    io.hostTx.valid := True

    // tell allocator how much we need to allocate in the packet buffer
    // FIXME: what if size is zero due to header only packet?
    rxAlloc.io.allocReq << io.igMetadata.map { meta =>
      val ret = PacketLength()
      ret.bits := meta.getPayloadSize
      ret
    }
    rxAlloc.io.freeReq </< io.hostRxAck
    io.statistics.rxAllocOccupancy := rxAlloc.io.slotOccupancy

    rxAlloc.io.allocResp.setBlocked()
    io.hostTxAck.setBlocked()
    val lastIgReq = io.igMetadata.toFlowFire.toReg()

    val rxCaptured = Reg(Stream(HostPacketDesc())).setIdle()
    // FIXME: how much buffering do we need?
    rxCaptured.queue(c[Int]("max rx pkts in flight")) >> io.hostRx

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxAlloc.io.allocResp.freeRun()
          when(rxAlloc.io.allocResp.valid) {
            io.writeDesc.payload.payload.addr := rxAlloc.io.allocResp.addr.bits.resized
            io.writeDesc.payload.payload.len := lastIgReq.getPayloadSize // use the actual size instead of length of buffer

            // encode proto metadata into DMA tag
            val tag = RxDmaTag()
            tag.data.raw.assignDontCare()
            tag.addr := rxAlloc.io.allocResp.addr
            if (isBypass) {
              tag.ty := HostPacketDescType.bypass
              tag.data.bypassMeta.ty := lastIgReq.ty
              tag.data.bypassMeta.hdr := lastIgReq.collectHeaders
            } else {
              switch (lastIgReq.ty) {
                is (ProtoPacketDescType.oncRpcCall) {
                  tag.ty := HostPacketDescType.oncRpcCall

                  tag.data.oncRpcCall.funcPtr := lastIgReq.metadata.oncRpcCall.funcPtr
                  tag.data.oncRpcCall.xid := lastIgReq.metadata.oncRpcCall.hdr.xid
                  tag.data.oncRpcCall.args := lastIgReq.metadata.oncRpcCall.args
                }
                default {
                  tag.ty := HostPacketDescType.error
                  report("unsupported protocol metadata type on non-bypass packet")
                }
              }
            }

            when (lastIgReq.getPayloadSize === 0) {
              // no payload to DMA -- directly enqueue packet
              rxCaptured.buffer.addr := rxAlloc.io.allocResp.addr
              rxCaptured.buffer.size.bits := 0
              rxCaptured.ty := tag.ty
              rxCaptured.data := tag.data
              rxCaptured.valid := True

              goto(enqueuePkt)
            } otherwise {
              // issue DMA cmd
              io.writeDesc.payload.payload.tag := tag.asBits
              io.writeDesc.valid := True
              goto(sendDmaCmd)
            }
          }
        }
      }
      val sendDmaCmd: State = new State {
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

              rxCaptured.buffer.addr := tag.addr
              rxCaptured.buffer.size.bits := io.writeDescStatus.len
              rxCaptured.ty := tag.ty
              rxCaptured.data := tag.data
              rxCaptured.valid := True

              goto(enqueuePkt)
            } otherwise {
              inc(_.rxDmaErrorCount)
              goto(idle)
            }
          }
        }
      }
      val enqueuePkt: State = new State {
        onEntry {
          p.profile(p.RxEnqueueToHost -> True)
        }
        whenIsActive {
          when(rxCaptured.ready) {
            rxCaptured.setIdle()
            inc(_.rxPacketCount)
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
            io.readDesc.payload.payload.addr := io.hostTxAck.buffer.addr.bits.resized
            io.readDesc.payload.payload.len := io.hostTxAck.buffer.size.bits
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
              inc(_.txPacketCount)

              p.profile(p.TxAfterDmaRead -> True)
            } otherwise {
              inc(_.txDmaErrorCount)
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

    def connectControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      busCtrl.driveAndRead(io.allocReset, alloc("allocReset")) init false
    }

    def reportStatistics(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      io.statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc(name, "", attr = RO))
          case v: Vec[_] => v zip c[Seq[(Int, Double)]]("pkt buf alloc size map").map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc(name, s"upTo$slotSize", attr = RO))
          }
        }
      }
    }

    rg.release()
  }
}