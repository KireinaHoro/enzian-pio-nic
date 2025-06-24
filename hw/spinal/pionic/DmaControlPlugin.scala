package pionic

import jsteward.blocks.misc.RegBlockAlloc
import pionic.host.{DatapathService, HostReq, HostReqData, HostReqType}
import pionic.net.{PacketDesc, PacketDescType, RxDecoderSink}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._
import Global._
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
  * produces [[pionic.host.HostReq]], among which:
  *  - requests for the normal application cores go to [[Scheduler]]
  *  - requests for the bypass channel goes directly to the [[DatapathService]] for bypass (core 0).
  *
  * Also manages the buffer in [[PacketBuffer]] with a [[PacketAlloc]].
  *
  * For TX, sits between all [[DatapathService]] instances and the encoder pipeline.  Consumes [[pionic.host.HostReq]]
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
    *  - for bypass descriptors: [[bypassDp]]
    *  - for request descriptors: [[Scheduler]]
    *
    * Freed packet buffers are directly collected from all [[DatapathService]].
    *
    * For TX, [[HostReq]] comes from all [[DatapathService]] i.e. [[dps]].
    */
  lazy val dps = host.list[DatapathService]
  lazy val bypassDp = dps.head
  lazy val sched = host[Scheduler]

  val logic = during build new Area {
    val dmaConfig = pktBuf.dmaConfig

    val allocReset = Bool()
    // one allocator for the entire RX buffer
    val rxAlloc = new ResetArea(allocReset, true) {
      private val instance = PacketAlloc(0, PKT_BUF_SIZE)
      val io = instance.io
    }

    /** Decoded packet descriptors from [[RxDecoderSink]] */
    val requestDesc, bypassDesc = Stream(PacketDesc())

    val incomingDesc = Stream(RxPacketDescWithSource()).setBlocked()
    incomingDesc << StreamArbiterFactory().roundRobin.on(Seq(
      requestDesc.map(RxPacketDescWithSource.fromPacketDesc(_, isBypass = false)),
      bypassDesc.map(RxPacketDescWithSource.fromPacketDesc(_, isBypass = true))
    ))

    /** Outgoing packet descriptors to encoder pipeline */
    val outgoingDesc = Stream(PacketDesc())

    /** DMA descriptors interfaces */
    val readDesc = master(dmaConfig.readDescBus).setOutputAsReg().setAsDirectionLess()
    val readDescStatus = dmaConfig.readDescStatusBus
    val writeDesc = master(dmaConfig.writeDescBus).setOutputAsReg().setAsDirectionLess()
    val writeDescStatus = dmaConfig.writeDescStatusBus

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

    writeDesc.valid init False
    readDesc.valid init False

    rxAlloc.io.freeReq </< StreamArbiterFactory().roundRobin.on(dps.map(_.hostRxAck))
    rxAlloc.io.allocResp.setBlocked()

    val rxPacketDescTagged = incomingDesc.toFlowFire.toReg()

    val rxFsm = new StateMachine {
      def enqueuePkt(addr: PacketAddr, size: UInt, ty: HostReqType.C, data: HostReqData) = {
        def assign(hostRx: Stream[HostReq]) = {
          hostRx.valid := True
          hostRx.buffer.addr := addr
          hostRx.buffer.size.bits := size
          hostRx.ty := ty
          hostRx.data := data

          when (hostRx.ready) {
            inc(_.rxPacketCount)
            goto(idle)
          }
        }

        p.profile(p.RxEnqueueToHost -> True)
        when (ty === HostReqType.bypass) {
          assign(bypassDp.hostRx)
        } otherwise {
          assign(sched.logic.rxMeta)
        }
      }

      val idle: State = new State with EntryPoint {
        whenIsActive {
          rxAlloc.io.allocResp.freeRun()
          when(rxAlloc.io.allocResp.valid) {
            // new request allocated
            writeDesc.payload.payload.addr := rxAlloc.io.allocResp.addr.bits.resized
            writeDesc.payload.payload.len := rxPacketDescTagged.desc.getPayloadSize // use the actual size instead of length of buffer

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

                  tag.data.oncRpcCall.funcPtr := rxPacketDescTagged.desc.metadata.oncRpcCall.funcPtr
                  tag.data.oncRpcCall.pid := rxPacketDescTagged.desc.metadata.oncRpcCall.pid
                  tag.data.oncRpcCall.xid := rxPacketDescTagged.desc.metadata.oncRpcCall.hdr.xid
                  tag.data.oncRpcCall.args := rxPacketDescTagged.desc.metadata.oncRpcCall.args
                }
                default {
                  tag.ty := HostReqType.error
                  report("unsupported protocol metadata type on non-bypass packet")
                }
              }
            }

            when (rxPacketDescTagged.desc.getPayloadSize === 0) {
              // no payload to DMA -- directly enqueue packet
              enqueuePkt(
                addr = rxAlloc.io.allocResp.addr,
                size = 0,
                ty = tag.ty,
                data = tag.data,
              )
            } otherwise {
              // issue DMA cmd
              writeDesc.payload.payload.tag := tag.asBits
              writeDesc.valid := True
              goto(sendDmaCmd)
            }
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          rxAlloc.io.allocResp.ready := False
          when(writeDesc.ready) {
            writeDesc.setIdle()
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

              enqueuePkt(
                addr = tag.addr,
                size = writeDescStatus.len,
                ty = tag.ty,
                data = tag.data,
              )
            } otherwise {
              inc(_.rxDmaErrorCount)
              goto(idle)
            }
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

    val txReqMuxed = StreamArbiterFactory().roundRobin.on(dps.map(_.hostTxAck)).setBlocked()
    val txPacketDesc = Reg(PacketDesc())
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          txReqMuxed.freeRun()
          when(txReqMuxed.valid) {
            // check that the actual buffer is used
            assert(txReqMuxed.buffer.addr.bits >= PKT_BUF_TX_OFFSET.get,
              "packet buffer slot out of TX buffer range used")

            readDesc.payload.payload.addr := txReqMuxed.buffer.addr.bits.resized
            readDesc.payload.payload.len := txReqMuxed.buffer.size.bits
            readDesc.payload.payload.tag := 0
            readDesc.valid := True

            // parse and save outgoing PacketDesc
            switch (txReqMuxed.ty) {
              is (HostReqType.bypass) {
                txPacketDesc.fromHeaders(txReqMuxed.data.bypassMeta)
              }
              default {
                report("unsupported host request type: only bypass supported for now")
              }
            }

            goto(sendDmaCmd)
          }
        }
      }
      val sendDmaCmd: State = new State {
        whenIsActive {
          when(readDesc.ready) {
            readDesc.setIdle()
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
            goto(sendDesc)
          }
        }
      }
      val sendDesc: State = new State {
        whenIsActive {
          outgoingDesc.payload := txPacketDesc
          outgoingDesc.valid := True
          when (outgoingDesc.ready) {
            goto(idle)
          }
        }
      }
    }

    def connectControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      busCtrl.driveAndRead(allocReset, alloc("allocReset")) init false
    }

    def reportStatistics(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc(name, "", attr = RO))
          case v: Vec[_] => v zip PKT_BUF_ALLOC_SIZES.map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc(name, s"upTo$slotSize", attr = RO))
          }
        }
      }
    }
  }
}