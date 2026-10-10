package lauberhorn.net.oncrpc

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.net._
import lauberhorn.Global.ONCRPC_NESTED_CALL_INLINE_BYTES
import lauberhorn.net.udp.{UdpDecoder, UdpEncoder, UdpTxMeta, RpcLocalRoute}
import lauberhorn.net.ip.IpDecoder
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

  /** Total length of the call argument payload, provided entirely by the payload stream. */
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
    val remoteMd = Stream(UdpTxMeta())
    val remotePld = Axi4Stream(axisConfig)
    to[UdpTxMeta, UdpEncoder](remoteMd, remotePld)

    awaitBuild()
    val localRoute = new RpcLocalRoute(outMd, outPld, remoteMd, remotePld,
      host[UdpDecoder].logic.localCallMd, host[UdpDecoder].logic.localCallPld,
      host[IpDecoder].logic.ipAddress)
    tp.trace("LocalDelivery", RpcID(outMd.rpcId)) := outMd.fire && localRoute.chooseLocal

    collectInto(md, pld, acceptHostPackets = true)

    val encoder = RpcHeaderInjector(axisConfig, OncRpcCallHeader().getBitsWidth / 8)
    encoder.io.output >> outPld


    val overflowPldPresent = Reg(Bool())

    val outHdr = Reg(OncRpcCallHeader())

    encoder.io.empty := !overflowPldPresent
    encoder.io.header.payload := outHdr.asBits
    encoder.io.header.valid := False

    outMd.payload.setAsReg()
    outMd.native := False
    outMd.coreId := 0
    outMd.hostMsgId := 0
    outMd.valid := False
    md.ready := False
    pld.ready := False
    encoder.io.input.setIdle()

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          md.ready := True
          when (md.fire) {
            nextRpcId := nextRpcId + 1

            overflowPldPresent := md.callLen.bits =/= 0

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

            // DmaControlPlugin reserved completion routing before admission.
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
            when(encoder.io.empty) { goto(idle) } otherwise { goto(passthrough) }
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
