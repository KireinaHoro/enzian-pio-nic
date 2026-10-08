package lauberhorn.net.oncrpc

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global.{NUM_SESSIONS, ONCRPC_INLINE_BYTES, REG_WIDTH}
import lauberhorn.{MacInterfaceService, PacketLength, RpcID, TraceData}
import lauberhorn.net.udp.{UdpDecoder, UdpEncoder, UdpTxMeta, RpcLocalRoute}
import lauberhorn.net.ip.IpDecoder
import lauberhorn.net.{Encoder, EncoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

import scala.language.postfixOps

case class OncRpcReplyTxMeta() extends Bundle with EncoderMetadata {
  val rpcId = UInt(RpcID.width bits)
  val requestToken = Bits(32 bits)
  val owner = UInt(lauberhorn.Global.PID_WIDTH.get bits) // trusted assignment, never user supplied
  val status = Bits(8 bits)
  val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)

  /** Total length of the entire reply message, including inlined bits */
  val replyLen = PacketLength()

  def getType = PacketDescType.oncRpcReplyTx
  override def traceData: Seq[TraceData] = Seq(RpcID(rpcId))
}

class OncRpcReplyEncoder extends Encoder[OncRpcReplyTxMeta] {
  def getMetadata = OncRpcReplyTxMeta()

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    val readIdx = alloc("stat", "Reply context readback index", "context_read_idx", attr = AccessType.WO)
    busCtrl.drive(logic.contexts.io.readIndex, readIdx)
    logic.contexts.io.readContext.elements.foreach { case (name, field) =>
      busCtrl.read(EndiannessSwap(field.asBits), alloc("stat", s"UDP reply context $name (host order)",
        s"context_$name", attr = AccessType.RO))
    }
    busCtrl.read(logic.contexts.io.readActive, alloc("stat", "Reply context active", "context_active", attr = AccessType.RO))
    busCtrl.read(logic.contexts.io.readToken, alloc("stat", "Reply context token", "context_token", attr = AccessType.RO))
    busCtrl.read(logic.contexts.io.readOwner, alloc("stat", "Reply context owner PID",
      "context_owner", attr = AccessType.RO))
    logic.contexts.io.reclaim.valid := False
    logic.contexts.io.reclaim.payload := 0
    val reclaimAddr = alloc("ctrl", "Reclaim owner PID after process drain", "context_reclaim_pid", attr = AccessType.WO)
    busCtrl.write(logic.contexts.io.reclaim.payload, reclaimAddr)
    busCtrl.onWrite(reclaimAddr) { logic.contexts.io.reclaim.valid := True }
    busCtrl.read(logic.sessTblFull.value, alloc("stat", "Requests rejected: reply context table full",
      "sessTblFull", attr = AccessType.RO))
    busCtrl.read(logic.rejectionDropped.value, alloc("stat", "Rejection queue full: error reply dropped",
      "rejectionDropped", attr = AccessType.RO))
    busCtrl.read(logic.dropped.value, alloc("stat", "Replies dropped: stale or invalid context token",
      "dropped", attr = AccessType.RO))
  }

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(OncRpcReplyTxMeta())
    val pld = Axi4Stream(axisConfig)
    val nextRpcId = Reg(UInt(RpcID.width bits)) init 0

    val outMd = Stream(UdpTxMeta())
    val outPld = Axi4Stream(axisConfig)
    val remoteMd = Stream(UdpTxMeta())
    val remotePld = Axi4Stream(axisConfig)
    to[UdpTxMeta, UdpEncoder](remoteMd, remotePld)

    // Admission is decided before host dispatch, with no allocation round trip.
    val contexts = new RpcReplyContexts(NUM_SESSIONS, lauberhorn.Global.PID_WIDTH.get)
    val reject = Flow(UdpReplyContext())
    val rejected = StreamFifo(UdpReplyContext(), NUM_SESSIONS.get)
    rejected.io.push.valid := reject.valid
    rejected.io.push.payload := reject.payload
    rejected.io.pop.ready := False
    val sessTblFull, rejectionDropped = Counter(REG_WIDTH bits)
    when(reject.valid) { sessTblFull.increment() }
    // Never block RX behind a congested rejection output: local replies needed
    // to release capacity may be behind that request. UDP errors are best effort.
    when(reject.valid && !rejected.io.push.ready) { rejectionDropped.increment() }

    awaitBuild()
    val localRoute = new RpcLocalRoute(outMd, outPld, remoteMd, remotePld,
      host[UdpDecoder].logic.localReplyMd, host[UdpDecoder].logic.localReplyPld,
      host[IpDecoder].logic.ipAddress)
    tp.trace("LocalDelivery", RpcID(outMd.rpcId)) := outMd.fire && localRoute.chooseLocal

    collectInto(md, pld, acceptHostPackets = true)

    // encoder only handles the actual header as header (unlike in the Call decoder);
    val encoder = RpcHeaderInjector(axisConfig, OncRpcReplyHeader().getBitsWidth / 8)
    encoder.io.output >> outPld

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

    contexts.io.replyToken := md.requestToken
    contexts.io.replyOwner := md.owner
    contexts.io.retire := False
    md.ready := False
    val lastWasReject = RegInit(False)

    val outHdr = Reg(OncRpcReplyHeader())
    encoder.io.empty := !overflowPldPresent && !inlinedMask.orR
    encoder.io.header.payload := outHdr.asBits
    encoder.io.header.valid := False

    outMd.payload.setAsReg()
    outMd.native := False
    outMd.coreId := 0
    outMd.hostMsgId := 0
    outMd.valid := False
    pld.ready := False
    encoder.io.input.setIdle()

    val dropped = Counter(REG_WIDTH bits)
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          // Alternate under contention, so normal replies can release contexts.
          when(rejected.io.pop.valid && (!md.valid || !lastWasReject)) {
            rejected.io.pop.ready := True
            val route = rejected.io.pop.payload
            outHdr.xid := route.xid
            outHdr.msgType := EndiannessSwap(B(1, 32 bits))
            outHdr.replyStat := 0
            outHdr.verifier := 0
            outHdr.acceptStat := EndiannessSwap(B(5, 32 bits))
            outMd.daddr := route.clientAddr
            outMd.dport := route.clientPort
            outMd.sport := route.serverPort
            outMd.rpcId := nextRpcId
            outMd.pldLen := outHdr.getBitsWidth / 8
            inlinedMask := 0
            overflowPldPresent := False
            nextRpcId := nextRpcId + 1
            lastWasReject := True
            goto(sendDownstreamMd)
          } elsewhen(md.valid) {
            md.ready := True
            lastWasReject := False
            nextRpcId := nextRpcId + 1
            inlinedShift := inlinedShiftNext
            inlinedMask := inlinedMaskNext
            when(md.replyLen.bits > ONCRPC_INLINE_BYTES.get) {
              inlinedLen := ONCRPC_INLINE_BYTES.get
              overflowPldPresent := True
            } otherwise {
              inlinedLen := md.replyLen.bits.resized
              overflowPldPresent := False
            }
            inlinedData := (md.data << (inlinedShiftNext * 8)).resized
            when(contexts.io.replyValid) {
              // Capture all routing state before releasing the slot. The output
              // registers own it through any subsequent transport backpressure.
              contexts.io.retire := True
              val route = contexts.io.replyContext
              tp.trace("OncRpcReplyEncode", RpcID(nextRpcId)) := True
              outHdr.xid := route.xid
              outHdr.msgType := EndiannessSwap(B(1, 32 bits))
              outHdr.replyStat := 0
              outHdr.verifier := 0
              outHdr.acceptStat := EndiannessSwap(md.status.resize(32))
              outMd.daddr := route.clientAddr
              outMd.rpcId := nextRpcId
              outMd.dport := route.clientPort
              outMd.sport := route.serverPort
              outMd.pldLen := md.replyLen.bits + outHdr.getBitsWidth / 8
              goto(sendDownstreamMd)
            } otherwise {
              dropped.increment()
              tp.trace("OncRpcReplyLookupMiss", RpcID(nextRpcId)) := True
              when(md.replyLen.bits > ONCRPC_INLINE_BYTES.get) { goto(dropPld) }
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
            when(encoder.io.empty) { goto(idle) } otherwise { goto(sendInlinedWords) }
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
