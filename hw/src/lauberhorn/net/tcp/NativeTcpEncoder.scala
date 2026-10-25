package lauberhorn.net.tcp

import jsteward.blocks.axi.AxiStreamExtractHeader
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.net._
import lauberhorn.net.ip.{IpEncoder, IpHeader, IpTxMeta}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._

case class NativeTcpTxMeta() extends Bundle with EncoderMetadata {
  val length = UInt(16 bits)
  def getType = PacketDescType.nativeTcp
}

/** Strip the host IPv4 header and forward software-supplied TCP bytes unchanged.
  * Endpoint ownership and buffer bounds are checked before DMA. The existing
  * IP/Ethernet encoders supply the wire headers and neighbor lookup.
  */
class NativeTcpEncoder extends Encoder[NativeTcpTxMeta] {
  def getMetadata = NativeTcpTxMeta()
  val logic = during setup new Area {
    val config = host[MacInterfaceService].axisConfig
    val metadata = Stream(NativeTcpTxMeta())
    val ingress = Axi4Stream(config)
    val outMd = Stream(IpTxMeta())
    val outPld = Axi4Stream(config)
    to[IpTxMeta, IpEncoder](outMd, outPld)
    awaitBuild()
    collectInto(metadata, ingress, acceptHostPackets = true, payloadBytes = _.length)
    val strip = AxiStreamExtractHeader(config, 20)()
    strip.io.input << ingress
    strip.io.header.setBlocked()
    strip.io.output.setBlocked()
    strip.io.outputAck := outPld.lastFire
    outPld.setIdle()
    val ip = IpHeader()
    ip.assignFromBits(strip.io.header.payload)
    val savedLength = Reg(UInt(16 bits))
    val nextPacketId = Reg(UInt(PacketID.width bits)) init 0
    metadata.setBlocked(); outMd.setIdle()
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          metadata.ready := True
          when(metadata.fire) {
            savedLength := metadata.length
            goto(describe)
          }
        }
      }
      val describe: State = new State {
        whenIsActive {
          outMd.valid := strip.io.header.valid
          outMd.packetId := nextPacketId
          outMd.rpcId := invalidTraceId(RpcID.width)
          outMd.daddr := ip.daddr; outMd.proto := 6
          outMd.pldLen := savedLength - 20
          strip.io.header.ready := outMd.ready
          when(outMd.fire) { nextPacketId := nextPacketId + 1; goto(deliver) }
        }
      }
      val deliver: State = new State {
        whenIsActive {
          outPld << strip.io.output
          when(outPld.lastFire) { goto(idle) }
        }
      }
    }
  }
}
