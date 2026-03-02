package lauberhorn.net.oncrpc

import jsteward.blocks.axi.AxiStreamInjectHeader
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global.{NUM_SESSIONS, ONCRPC_INLINE_BYTES, REG_WIDTH}
import lauberhorn.{MacInterfaceService, PacketLength}
import lauberhorn.net.udp.{UdpEncoder, UdpTxMeta}
import lauberhorn.net.{Encoder, EncoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

import scala.language.postfixOps

case class OncRpcReplyTxMeta() extends Bundle with EncoderMetadata {
  val funcPtr = Bits(64 bits)
  val xid = Bits(32 bits)
  val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)

  /** Total length of the entire reply message, including inlined bits */
  val replyLen = PacketLength()

  def getType = PacketDescType.oncRpcReply
}

class OncRpcReplyEncoder extends Encoder[OncRpcReplyTxMeta] {
  def getMetadata = OncRpcReplyTxMeta()

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    // allow readback/update of session table to implement session timeouts
    val updateIdxAddr = alloc("ctrl", "Index of session table entry to update",
      "sess_idx", attr = AccessType.WO)
    busCtrl.write(logic.sessionDb.update.idx, updateIdxAddr)
    busCtrl.onWrite(updateIdxAddr) {
      logic.sessionDb.update.valid := True
    }
    logic.sessionDb.update.value.elements.foreach { case (name, field) =>
      busCtrl.write(field, alloc("ctrl", s"Session table update $name",
        s"sess_$name", attr = AccessType.WO))
    }

    val readbackIdxAddr = alloc("stat", "Index of session table entry to read back",
      "sess_readback_idx", attr = AccessType.WO)
    busCtrl.drive(logic.sessionDb.readbackIdx, readbackIdxAddr)
    logic.sessionDb.readback.elements.foreach { case (name, field) =>
      busCtrl.read(field, alloc("stat", s"Session table readback $name",
        s"sess_readback_$name", attr = AccessType.RO))
    }

    busCtrl.read(logic.sessTblFull.value, alloc("stat", "Number of times session table became full and entry 0 was overridden",
      "sessTblFull", attr = AccessType.RO))
    busCtrl.read(logic.dropped.value, alloc("stat", "Number of dropped requests due to missing session",
      "dropped", attr = AccessType.RO))
  }

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(OncRpcReplyTxMeta())
    val pld = Axi4Stream(axisConfig)

    val outMd = Stream(UdpTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[UdpTxMeta, UdpEncoder](outMd, outPld)

    // accept events from RPC decoder to record in session DB
    val newSessionEvent = Flow(OncRpcSessionDef())

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true)

    // encoder only handles the actual header as header (unlike in the Call decoder);
    val encoder = AxiStreamInjectHeader(axisConfig, OncRpcReplyHeader().getBitsWidth / 8)
    encoder.io.output >> outPld

    val sessionDb = LookupTable(OncRpcSessionDef(), NUM_SESSIONS) { v =>
      v.active init False
    }
    sessionDb.update.setIdle()

    // find a slot to record the incoming session
    val (rxExistQ, rxExistR, _) = sessionDb.makePort(OncRpcSessionDef(), OncRpcSessionDef(), "rxLookupExisting") { (v, q, _) =>
      v.funcPtr === q.funcPtr && v.xid === q.xid && v.active
    }

    val sessTblFull = Counter(REG_WIDTH bits)
    val (rxFreeQ, rxFreeR, _) = sessionDb.makePort(NoData(), NoData(), "rxLookupFree") { (v, _, _) =>
      !v.active
    }
    rxFreeQ.valid := True
    rxFreeR.ready := True

    rxExistQ.translateFrom(newSessionEvent.toStream) { case (q, e) =>
      q.query := e
      q.userData := e
    }
    rxExistR.ready := True
    when (rxExistR.valid) {
      sessionDb.update.valid := True
      sessionDb.update.value := rxExistR.userData
      when (rxExistR.matched) {
        // an entry already exists, update that one
        sessionDb.update.idx := rxExistR.idx
      } otherwise {
        // no entry currently exists, place in free slot
        sessionDb.update.idx := rxFreeR.idx
        when (!rxFreeR.matched) {
          // no more free entries, this will override the 0th entry;
          // increment counter
          sessTblFull.increment()
        }
      }
    }

    // Convert data words to AXI Stream and concatenate with the actual payload
    // from the packet buffer.  We will only have payload data from the packet
    // buffer if we have more words to send than what fits in the inline portion.
    // This creates arbitrary gaps at the beginning of the data stream, but
    // the encoder handles this just fine.
    assert(ONCRPC_INLINE_BYTES <= axisConfig.dataWidth, "we assumed the inlined bytes fit inside one AXIS beat")

    val segmentLenWidth = log2Up(axisConfig.dataWidth) + 1
    val inlinedLen = U(0, segmentLenWidth bits)
    val inlinedMaskNext = ((U(1, axisConfig.dataWidth bits) |<< inlinedLen) - 1).asBits
    val inlinedShiftNext = axisConfig.dataWidth - inlinedLen

    val inlinedMask = Reg(Bits(axisConfig.dataWidth bits))     // not yet shifted
    val inlinedShift = Reg(UInt(segmentLenWidth bits))
    val inlinedData = Reg(Bits(axisConfig.dataWidth * 8 bits)) // already shifted!
    val overflowPldPresent = Reg(Bool())

    // find reply destination
    case class TxQuery() extends Bundle {
      val funcPtr = Bits(64 bits)
      val xid = Bits(32 bits)
    }

    val (txQ, txR, _) = sessionDb.makePort(TxQuery(), OncRpcReplyTxMeta(),
      "txLookup", singleMatch = true) { (v, q, _) =>
      v.funcPtr === q.funcPtr && v.xid === q.xid && v.active
    }
    txQ.translateFrom(md) { case (q, md) =>
      q.query.funcPtr := md.funcPtr
      q.query.xid     := md.xid
      q.userData      := md
    }
    txR.ready := False

    val outHdr = Reg(OncRpcReplyHeader())
    encoder.io.header.payload := outHdr.asBits
    encoder.io.header.valid := False

    outMd.payload.setAsReg()
    outMd.valid := False
    pld.ready := False
    encoder.io.input.setIdle()

    val dropped = Counter(REG_WIDTH bits)
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          txR.ready := True
          when (txR.valid) {
            // calculate shift for beat to inject into payload
            inlinedShift := inlinedShiftNext
            inlinedMask  := inlinedMaskNext
            when (txR.userData.replyLen.bits > ONCRPC_INLINE_BYTES.get) {
              inlinedLen         := ONCRPC_INLINE_BYTES.get
              overflowPldPresent := True
            } otherwise {
              inlinedLen         := txR.userData.replyLen.bits.resized
              overflowPldPresent := False
            }

            // store inlined bytes.  We shift it here already to shorten critical path
            inlinedData := (txR.userData.data.asBits << (inlinedShiftNext * 8)).resized

            when (txR.matched) {
              // send header to encoder
              outHdr.xid        := txR.userData.xid
              outHdr.msgType    := 1 // msg_type == REPLY
              outHdr.replyStat  := 0 // reply_stat == MSG_ACCEPTED
              outHdr.verifier   := 0
              outHdr.acceptStat := 0 // accept_stat == SUCCESS

              outMd.daddr       := txR.value.clientAddr
              outMd.dport       := txR.value.clientPort
              outMd.sport       := txR.value.serverPort
              outMd.pldLen      := txR.userData.replyLen.bits + outHdr.getBitsWidth / 8

              goto(sendDownstreamMd)
            } otherwise {
              // failed to find session: is the entry overridden?
              dropped.increment()
              when (txR.userData.replyLen.bits > ONCRPC_INLINE_BYTES.get) {
                // only need to consume payload, when there are overflow bytes
                goto(dropPld)
              }
            }
          }
        }
      }
      val dropPld: State = new State {
        whenIsActive {
          pld.ready := True
          when (pld.lastFire) {
            goto(idle)
          }
        }
      }
      val sendDownstreamMd: State = new State {
        whenIsActive {
          outMd.valid := True
          when (outMd.ready) {
            goto(sendHdrToEncoder)
          }
        }
      }
      val sendHdrToEncoder: State = new State {
        whenIsActive {
          encoder.io.header.valid := True
          when (encoder.io.header.ready) {
            // send first beats (inlined beats) of payload to encoder
            goto(sendInlinedWords)
          }
        }
      }
      val sendInlinedWords: State = new State {
        whenIsActive {
          encoder.io.input.valid := True
          encoder.io.input.keep := inlinedMask |<< inlinedShift
          encoder.io.input.data := inlinedData
          encoder.io.input.last := !overflowPldPresent
          when (encoder.io.input.ready) {
            when (overflowPldPresent) {
              goto(passthrough)
            } otherwise {
              goto(idle)
            }
          }
        }
      }
      val passthrough: State = new State {
        whenIsActive {
          encoder.io.input << pld
          when (pld.lastFire) {
            goto(idle)
          }
        }
      }
    }

  }
}
