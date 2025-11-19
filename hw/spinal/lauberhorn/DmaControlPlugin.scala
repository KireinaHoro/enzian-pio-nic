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
  lazy val debug = host[DebugPlugin]

  val bypassSink = during setup host[BypassCmdSink].getSink()
  val logic = during build new Area {
    val dmaConfig = pktBuf.dmaConfig

    val allocReset = Bool()
    // one allocator for the entire RX buffer
    val rxAlloc = new ResetArea(allocReset, true) {
      // do not allocate space reserved for TX
      val inst = PacketAlloc(0, PKT_BUF_TX_OFFSET.get)
    }.inst

    /** Incoming packet descriptors from decoder pipeline */
    val incomingDesc = Stream(RxPacketDescWithSource())

    /** Outgoing packet descriptors to encoder pipeline */
    val outgoingDesc = Stream(PacketDesc())

    /** DMA descriptors interfaces */
    val readDesc = dmaConfig.readDescBus
    val readDescStatus = dmaConfig.readDescStatusBus
    val writeDesc = dmaConfig.writeDescBus
    val writeDescStatus = dmaConfig.writeDescStatusBus

    readDesc.setIdle()
    writeDesc.setIdle()

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

    debug.postDebug("alloc_free", rxAlloc.io.freeReq)
    debug.postDebug("alloc_req", rxAlloc.io.allocReq)
    debug.postDebug("alloc_resp", rxAlloc.io.allocResp)

    rxAlloc.io.freeReq <-/< StreamArbiterFactory(s"${getName()}_freeReqMux").roundRobin.on(dps.map(_.hostRxAck.pipelined(FULL)))

    rxAlloc.io.allocReq.setIdle()
    rxAlloc.io.allocResp.setBlocked()

    bypassSink.setIdle()
    sched.logic.rxMeta.setIdle()

    outgoingDesc.setIdle()
    incomingDesc.setBlocked()

    // details of packet to enqueue
    val pktToEnqueue = Reg(HostReq())

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          incomingDesc.ready := True
          when(incomingDesc.valid) {
            val len = incomingDesc.desc.getPayloadSize
            pktToEnqueue.len.bits := len

            when (incomingDesc.isBypass) {
              pktToEnqueue.ty := HostReqType.bypass
              pktToEnqueue.data.bypassMeta.ty := incomingDesc.desc.ty
              pktToEnqueue.data.bypassMeta.hdr := incomingDesc.desc.collectHeaders
            } otherwise {
              switch (incomingDesc.desc.ty) {
                is (PacketDescType.oncRpcCall) {
                  pktToEnqueue.ty := HostReqType.oncRpcCall

                  pktToEnqueue.data.oncRpcCallRx.funcPtr := incomingDesc.desc.metadata.oncRpcCall.funcPtr
                  pktToEnqueue.data.oncRpcCallRx.pid := incomingDesc.desc.metadata.oncRpcCall.pid
                  pktToEnqueue.data.oncRpcCallRx.xid := incomingDesc.desc.metadata.oncRpcCall.hdr.xid
                  pktToEnqueue.data.oncRpcCallRx.data := incomingDesc.desc.metadata.oncRpcCall.args
                }
                default {
                  pktToEnqueue.ty := HostReqType.error
                  report("unsupported protocol metadata type on non-bypass packet", FAILURE)
                }
              }
            }

            when (len === 0) {
              // no payload -- enqueue directly
              pktToEnqueue.buffer.clearAll()
              goto(enqueuePkt)
            } otherwise {
              goto(allocatePkt)
            }
          }
        }
      }
      val allocatePkt: State = new State {
        whenIsActive {
          rxAlloc.io.allocReq.payload := pktToEnqueue.len
          rxAlloc.io.allocReq.valid := True
          when (rxAlloc.io.allocReq.ready) {
            goto(waitAlloc)
          }
        }
      }
      val waitAlloc: State = new State {
        whenIsActive {
          rxAlloc.io.allocResp.ready := True
          when (rxAlloc.io.allocResp.valid) {
            pktToEnqueue.buffer := rxAlloc.io.allocResp.payload
            goto(sendDmaCmd)
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          writeDesc.addr := pktToEnqueue.buffer.addr.bits.resized
          writeDesc.len := pktToEnqueue.len.bits // use the actual size instead of length of buffer
          writeDesc.tag := 0
          writeDesc.valid := True
          when (writeDesc.ready) {
            goto(waitDma)
          }
        }
      }
      val waitDma: State = new State {
        whenIsActive {
          // status is a Flow
          when(writeDescStatus.fire) {
            when(writeDescStatus.payload.error === 0) {
              // fill host descriptor
              assert(writeDescStatus.len === pktToEnqueue.len.bits,
                "DMA didn't write all packet bytes!")
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
            hostRx.payload := pktToEnqueue

            assert(pktToEnqueue.buffer.size.bits >= pktToEnqueue.len.bits,
              "truncated packet during RX DMA")

            when (hostRx.ready) {
              inc(_.rxPacketCount)
              goto(idle)
            }
          }

          p.profile(p.RxEnqueueToHost -> True)
          when (pktToEnqueue.ty === HostReqType.bypass) {
            assign(bypassSink.get)
          } otherwise {
            assign(sched.logic.rxMeta)
          }
        }
      }
    }

    rxFsm.build()
    debug.postDebug("dma_rxFsm_state", rxFsm.stateReg)
    debug.postDebug("dma_write_desc", writeDesc)
    debug.postDebug("dma_write_desc_status", writeDescStatus)

    // drive TX buffer information for host modules
    // one MTU is reserved for each core for TX
    dps.foreach { dp =>
      dp.hostTx.addr.bits := PKT_BUF_TX_OFFSET + dp.coreID * ROUNDED_MTU
      dp.hostTx.size.bits := U(ROUNDED_MTU)
      dp.hostTx.valid := True
    }

    val txReqMuxed = StreamArbiterFactory(s"${getName()}_txReqMux").roundRobin.on(dps.map(_.hostTxAck)).setBlocked()
    val txReqBuffered = txReqMuxed.asFlow.toReg
    val txPacketDesc = Reg(PacketDesc())
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          txReqMuxed.ready := True
          when(txReqMuxed.valid) {
            // parse and save outgoing PacketDesc
            switch (txReqMuxed.ty) {
              is (HostReqType.bypass) {
                txPacketDesc.fromHeaders(txReqMuxed.data.bypassMeta)
              }
              is (HostReqType.oncRpcReply) {
                txPacketDesc.ty := PacketDescType.oncRpcReply
                txPacketDesc.metadata.assignDontCare()
                txPacketDesc.metadata.oncRpcReply.get := txReqMuxed.data.oncRpcReplyTx
              }
              default {
                report("unsupported host request type", FAILURE)
              }
            }

            goto(sendDmaCmd)
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          // check that the actual buffer is used
          assert(txReqBuffered.buffer.addr.bits >= PKT_BUF_TX_OFFSET.get,
            "packet buffer slot out of TX buffer range used")

          // store DMA command
          readDesc.payload.payload.addr := txReqBuffered.buffer.addr.bits.resized
          readDesc.payload.payload.len := txReqBuffered.buffer.size.bits
          readDesc.payload.payload.tag := 0
          readDesc.valid := True

          // send descriptor first to set stream mux to correct direction
          when (readDesc.ready) {
            goto(sendDesc)
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
            goto(waitDma)
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
      busCtrl.driveAndRead(allocReset, alloc("ctrl",
        "Reset packet buffer allocator",
        "allocReset")) init false
    }

    def stat(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc("stat", s"Stat $name", name, attr = RO))
          case v: Vec[_] => v zip rxAlloc.roundedMap.map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc("stat",
              s"Free slots left for packet size up to $slotSize",
              s"${name}_upTo$slotSize", attr = RO))
          }
        }
      }
    }
  }
}
