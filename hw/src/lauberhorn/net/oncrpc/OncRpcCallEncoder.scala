package lauberhorn.net.oncrpc

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.net._
import lauberhorn.Global.ONCRPC_NESTED_CALL_INLINE_BYTES
import lauberhorn.net.udp.{UdpEncoder, UdpTxMeta}
import lauberhorn.{MacInterfaceService, PID, PacketLength, RpcID, TraceData}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

import scala.language.postfixOps

case class OncRpcCallTxMeta() extends Bundle with EncoderMetadata {
  val rpcId = UInt(RpcID.width bits)
  val pid = PID()
  val cookie = Bits(32 bits)
  val xid = Bits(32 bits)
  val daddr = Bits(32 bits)
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val progNum = Bits(32 bits)
  val progVer = Bits(32 bits)
  val proc = Bits(32 bits)
  val data = Bits(ONCRPC_NESTED_CALL_INLINE_BYTES * 8 bits)

  /** Total length of the call argument payload, including inlined bytes. */
  val callLen = PacketLength()

  def getType: PacketDescType.E = PacketDescType.oncRpcCallTx
  override def traceData: Seq[TraceData] = Seq(RpcID(rpcId))
}

class OncRpcCallEncoder extends Encoder[OncRpcCallTxMeta] {
  def getMetadata = OncRpcCallTxMeta()

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(OncRpcCallTxMeta())
    val pld = Axi4Stream(axisConfig)
    val nextRpcId = Reg(UInt(RpcID.width bits)) init 0

    val outMd = Stream(UdpTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[UdpTxMeta, UdpEncoder](outMd, outPld)

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true)

    val encoder = AxiStreamInjectHeader(axisConfig, OncRpcCallHeader().getBitsWidth / 8)
    encoder.io.output >> outPld

    val newRequestEvent = host[OncRpcReplyDecoder].logic.newRequestEvent

    assert(ONCRPC_NESTED_CALL_INLINE_BYTES <= axisConfig.dataWidth,
      "we assumed the nested call inline bytes fit inside one AXIS beat")

    val segmentLenWidth = log2Up(axisConfig.dataWidth) + 1
    val inlinedLen = U(0, segmentLenWidth bits)
    val inlinedMaskNext = ((U(1, axisConfig.dataWidth bits) |<< inlinedLen) - 1).asBits
    val inlinedShiftNext = axisConfig.dataWidth - inlinedLen

    val inlinedMask = Reg(Bits(axisConfig.dataWidth bits))
    val inlinedShift = Reg(UInt(segmentLenWidth bits))
    val inlinedData = Reg(Bits(axisConfig.dataWidth * 8 bits))
    val overflowPldPresent = Reg(Bool())

    val outHdr = Reg(OncRpcCallHeader())

    encoder.io.header.payload := outHdr.asBits
    encoder.io.header.valid := False

    outMd.payload.setAsReg()
    outMd.valid := False
    md.ready := False
    pld.ready := False
    encoder.io.input.setIdle()

    newRequestEvent.valid := False
    newRequestEvent.payload.assignDontCare()

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          md.ready := True
          when (md.fire) {
            nextRpcId := nextRpcId + 1

            inlinedShift := inlinedShiftNext
            inlinedMask  := inlinedMaskNext
            when (md.callLen.bits > ONCRPC_NESTED_CALL_INLINE_BYTES.get) {
              inlinedLen         := ONCRPC_NESTED_CALL_INLINE_BYTES.get
              overflowPldPresent := True
            } otherwise {
              inlinedLen         := md.callLen.bits.resized
              overflowPldPresent := False
            }
            inlinedData := (md.data.asBits << (inlinedShiftNext * 8)).resized

            outHdr.xid      := md.xid
            outHdr.msgType  := 0
            outHdr.rpcVer   := EndiannessSwap(B("32'x00000002"))
            outHdr.progNum  := md.progNum
            outHdr.progVer  := md.progVer
            outHdr.proc     := md.proc
            outHdr.creds    := 0
            outHdr.verifier := 0

            outMd.daddr := md.daddr
            outMd.dport := md.dport
            outMd.sport := md.sport
            outMd.rpcId := nextRpcId
            outMd.pldLen := md.callLen.bits + OncRpcCallHeader().getBitsWidth / 8

            newRequestEvent.valid := True
            newRequestEvent.payload.xid := md.xid
            newRequestEvent.payload.remoteAddr := md.daddr
            newRequestEvent.payload.remotePort := md.dport
            newRequestEvent.payload.localPort := md.sport
            newRequestEvent.payload.pid := md.pid
            newRequestEvent.payload.cookie := md.cookie
            newRequestEvent.payload.active := True

            goto(sendDownstreamMd)
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
