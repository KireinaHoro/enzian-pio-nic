package lauberhorn

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.host.{BypassCmdSink, DatapathService, HostReq, HostReqData, HostReqType}
import lauberhorn.net.{DecoderSink, PacketDesc, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._
import Global._
import spinal.lib.StreamPipe.FULL
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.misc.database.Element.toValue
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

case class RxPacketDescWithSource() extends Bundle {
  val desc = PacketDesc()
  val isBypass = Bool()
}

object RxPacketDescWithSource {
  def fromPacketDesc(desc: PacketDesc, isBypass: Boolean) = {
    val ret = RxPacketDescWithSource()
    ret.desc := desc
    ret.isBypass := Bool(isBypass)
    ret
  }
}

/**
  * Global AXI DMA plugin.  Generates DMA commands for the AXI DMA engine in [[PacketBuffer]].
  *
  * For RX, sits between the decoder pipeline and scheduler.  Consumes [[PacketDesc]] from the decoder pipeline and
  * produces [[lauberhorn.host.HostReq]], among which:
  *  - requests for the normal application cores go to [[Scheduler]]
  *  - requests for the bypass channel goes directly to the [[DatapathService]] for bypass (core 0).
  *
  * Also manages the buffer in [[PacketBuffer]] with a [[PacketAlloc]].
  *
  * For TX, sits between all [[DatapathService]] instances and the encoder pipeline.  Consumes [[lauberhorn.host.HostReq]]
  * from [[DatapathService]] and emits [[PacketDesc]] to the encoder pipeline.
  *
  * The very first core (core ID 0) handles bypass traffic to/from the bypass channel on the host.  For RX, this
  * results in all traffic being packed into [[HostReqType.bypass]]; for TX, any packet that does not carry the
  * bypass type in the host descriptor is an error.
  */
class DmaControlPlugin extends FiberPlugin {
  lazy val pktBuf = host[PacketBuffer]
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val p = host[ProfilerPlugin]

  /** Access points for downstream/upstream [[HostReq]] instances.
    *
    * For RX, translated [[HostReq]] goes to:
    *  - for bypass descriptors: [[BypassCmdSink]]
    *  - for request descriptors: [[Scheduler]]
    *
    * Freed packet buffers are directly collected from all [[DatapathService]].
    *
    * For TX, [[HostReq]] comes from all [[DatapathService]] i.e. [[dps]].
    */
  lazy val dps = host.list[DatapathService]
  lazy val sched = host[Scheduler]

  val bypassSink = during setup host[BypassCmdSink].getSink()
  val logic = during build new Area {
    val dmaConfig = pktBuf.dmaConfig

    val allocReset = Bool()
    // one allocator for the entire RX buffer
    val rxAlloc = new ResetArea(allocReset, true) {
      // do not allocate space reserved for TX
      private val instance = PacketAlloc(0, PKT_BUF_TX_OFFSET.get)
      val io = instance.io
    }

    /** Decoded packet descriptors from [[DecoderSink]] */
    val requestDesc, bypassDesc = Stream(PacketDesc())

    val incomingDesc = Stream(RxPacketDescWithSource())
    incomingDesc << StreamArbiterFactory(s"${getName()}_descMux").roundRobin.on(Seq(
      requestDesc.map(RxPacketDescWithSource.fromPacketDesc(_, isBypass = false)).pipelined(FULL),
      bypassDesc.map(RxPacketDescWithSource.fromPacketDesc(_, isBypass = true)).pipelined(FULL),
    ))

    /** Outgoing packet descriptors to encoder pipeline */
    val outgoingDesc = Stream(PacketDesc())

    /** DMA descriptors interfaces */
    val readDesc = dmaConfig.readDescBus
    val readDescStatus = dmaConfig.readDescStatusBus
    val writeDesc = dmaConfig.writeDescBus
    val writeDescStatus = dmaConfig.writeDescStatusBus

    // allow storing command but valid stays combinational
    readDesc.payload.setAsReg()
    writeDesc.payload.setAsReg()
    writeDesc.valid := False
    readDesc.valid := False

    val statistics = new Bundle {
      val rxPacketCount = Reg(UInt(REG_WIDTH bits)) init 0
      val txPacketCount = Reg(UInt(REG_WIDTH bits)) init 0
      val rxDmaErrorCount = Reg(UInt(REG_WIDTH bits)) init 0
      val txDmaErrorCount = Reg(UInt(REG_WIDTH bits)) init 0
      val rxAllocOccupancy = rxAlloc.io.slotOccupancy.clone
    }
    statistics.rxAllocOccupancy := rxAlloc.io.slotOccupancy

    def inc(f: statistics.type => UInt) = {
      f(statistics) := f(statistics) + 1
    }

    rxAlloc.io.freeReq <-/< StreamArbiterFactory(s"${getName()}_freeReqMux").roundRobin.on(dps.map(_.hostRxAck.pipelined(FULL)))
    rxAlloc.io.allocResp.setBlocked()

    bypassSink.setIdle()
    sched.logic.rxMeta.setIdle()

    outgoingDesc.setIdle()

    val rxPacketDescTagged = incomingDesc.toFlowFire.toReg()

    // details of packet to enqueue
    val pktTagToEnqueue = Reg(RxDmaTag())
    val pktSizeToEnqueue = Reg(PacketLength())

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxAlloc.io.allocResp.freeRun()
          when(rxAlloc.io.allocResp.valid) {
            // new request allocated
            writeDesc.addr := rxAlloc.io.allocResp.addr.bits.resized
            writeDesc.len := rxPacketDescTagged.desc.getPayloadSize // use the actual size instead of length of buffer

            // encode proto metadata into DMA tag
            val tag = RxDmaTag()
            tag.data.raw.assignDontCare()
            tag.addr := rxAlloc.io.allocResp.addr
            when (rxPacketDescTagged.isBypass) {
              tag.ty := HostReqType.bypass
              tag.data.bypassMeta.ty := rxPacketDescTagged.desc.ty
              tag.data.bypassMeta.hdr := rxPacketDescTagged.desc.collectHeaders
            } otherwise {
              switch (rxPacketDescTagged.desc.ty) {
                is (PacketDescType.oncRpcCall) {
                  tag.ty := HostReqType.oncRpcCall

                  tag.data.oncRpcCallRx.funcPtr := rxPacketDescTagged.desc.metadata.oncRpcCall.funcPtr
                  tag.data.oncRpcCallRx.pid := rxPacketDescTagged.desc.metadata.oncRpcCall.pid
                  tag.data.oncRpcCallRx.xid := rxPacketDescTagged.desc.metadata.oncRpcCall.hdr.xid
                  tag.data.oncRpcCallRx.data := rxPacketDescTagged.desc.metadata.oncRpcCall.args
                }
                default {
                  tag.ty := HostReqType.error
                  report("unsupported protocol metadata type on non-bypass packet", FAILURE)
                }
              }
            }

            when (rxPacketDescTagged.desc.getPayloadSize === 0) {
              // no payload to DMA -- directly enqueue packet
              pktTagToEnqueue.addr := rxAlloc.io.allocResp.addr
              pktTagToEnqueue.ty   := tag.ty
              pktTagToEnqueue.data := tag.data
              pktSizeToEnqueue.bits := 0
              goto(enqueuePkt)
            } otherwise {
              // issue DMA cmd
              writeDesc.tag := tag.asBits
              goto(sendDmaCmd)
            }
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          rxAlloc.io.allocResp.ready := False
          // command already stored in register
          writeDesc.valid := True
          when(writeDesc.ready) {
            goto(waitDma)
          }
        }
      }
      val waitDma: State = new State {
        whenIsActive {
          when(writeDescStatus.fire) {
            when(writeDescStatus.payload.error === 0) {
              // fill host descriptor
              val tag = RxDmaTag()
              tag.assignFromBits(writeDescStatus.tag)

              pktTagToEnqueue := tag
              pktSizeToEnqueue.bits := writeDescStatus.len
              goto(enqueuePkt)
            } otherwise {
              inc(_.rxDmaErrorCount)
              goto(idle)
            }
          }
        }
      }
      val enqueuePkt: State = new State {
        whenIsActive {
          def assign(hostRx: Stream[HostReq]) = {
            hostRx.valid := True
            hostRx.buffer.addr := pktTagToEnqueue.addr
            hostRx.buffer.size := pktSizeToEnqueue
            hostRx.ty := pktTagToEnqueue.ty
            hostRx.data := pktTagToEnqueue.data

            when (hostRx.ready) {
              inc(_.rxPacketCount)
              goto(idle)
            }
          }

          p.profile(p.RxEnqueueToHost -> True)
          when (pktTagToEnqueue.ty === HostReqType.bypass) {
            assign(bypassSink.get)
          } otherwise {
            assign(sched.logic.rxMeta)
          }
        }
      }
    }

    // tell allocator how much we need for RX in the packet buffer
    // allocator handles zero-sized allocations and will return a zero-sized buffer
    rxAlloc.io.allocReq << incomingDesc.map { wrappedDesc =>
        val ret = PacketLength()
        ret.bits := wrappedDesc.desc.getPayloadSize
        ret
      }
      // do not accept new packets when one is being processed (not in idle)
      .haltWhen(!rxFsm.isActive(rxFsm.idle))

    // drive TX buffer information for host modules
    // one MTU is reserved for each core for TX
    dps.foreach { dp =>
      dp.hostTx.addr.bits := PKT_BUF_TX_OFFSET + dp.coreID * ROUNDED_MTU
      dp.hostTx.size.bits := U(ROUNDED_MTU)
      dp.hostTx.valid := True
    }

    val txReqMuxed = StreamArbiterFactory(s"${getName()}_txReqMux").roundRobin.on(dps.map(_.hostTxAck)).setBlocked()
    val txPacketDesc = Reg(PacketDesc())
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          txReqMuxed.freeRun()
          when(txReqMuxed.valid) {
            // check that the actual buffer is used
            assert(txReqMuxed.buffer.addr.bits >= PKT_BUF_TX_OFFSET.get,
              "packet buffer slot out of TX buffer range used")

            // store DMA command
            readDesc.payload.payload.addr := txReqMuxed.buffer.addr.bits.resized
            readDesc.payload.payload.len := txReqMuxed.buffer.size.bits
            readDesc.payload.payload.tag := 0

            // parse and save outgoing PacketDesc
            switch (txReqMuxed.ty) {
              is (HostReqType.bypass) {
                txPacketDesc.fromHeaders(txReqMuxed.data.bypassMeta)
              }
              default {
                report("unsupported host request type: only bypass supported for now", FAILURE)
              }
            }

            // send descriptor first to set stream mux to correct direction
            goto(sendDesc)
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          readDesc.valid := True
          when(readDesc.ready) {
            goto(waitDma)
          }
        }
      }
      val waitDma: State = new State {
        whenIsActive {
          when(readDescStatus.fire) {
            when(readDescStatus.payload.error === 0) {
              inc(_.txPacketCount)

              p.profile(p.TxAfterDmaRead -> True)
            } otherwise {
              inc(_.txDmaErrorCount)
            }
            goto(idle)
          }
        }
      }
      val sendDesc: State = new State {
        whenIsActive {
          outgoingDesc.payload := txPacketDesc
          outgoingDesc.valid := True
          when (outgoingDesc.ready) {
            goto(sendDmaCmd)
          }
        }
      }
    }

    def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
      val busCtrl = AxiLite4SlaveFactory(bus)
      ctrl(busCtrl, alloc)
      stat(busCtrl, alloc)
    }

    def ctrl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      busCtrl.driveAndRead(allocReset, alloc("ctrl", "allocReset")) init false
    }

    def stat(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc(name, attr = RO))
          case v: Vec[_] => v zip PKT_BUF_ALLOC_SIZES.map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc("stat", s"${name}_upTo$slotSize", attr = RO))
          }
        }
      }
    }
  }
}
