package lauberhorn.net.tcp

import jsteward.blocks.axi.{AxiStreamAligner, AxiStreamExtractHeader}
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.net._
import lauberhorn.net.ip.{IpDecoder, IpEncoder, IpHeader, IpTxMeta}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._

case class NativeTcpTxMeta() extends Bundle with EncoderMetadata {
  val length = UInt(16 bits)
  val sport = Bits(16 bits) // trusted listen-table port, wire order
  def getType = PacketDescType.nativeTcp
}

/** Validate the complete host IPv4/TCP packet before emitting any wire bytes.
  * The endpoint owner/handle is checked by DmaControl; this stage checks the
  * packet's source tuple and fills the mandatory TCP checksum. IP/Ethernet
  * headers and neighbor lookup remain owned by the existing encoders.
  */
class NativeTcpEncoder extends Encoder[NativeTcpTxMeta] {
  def getMetadata = NativeTcpTxMeta()
  val logic = during setup new Area {
    val config = host[MacInterfaceService].axisConfig
    require(config.dataWidth >= 40, "TCP first beat must contain the minimum IPv4/TCP headers")
    val metadata = Stream(NativeTcpTxMeta())
    val ingress = Axi4Stream(config)
    val outMd = Stream(IpTxMeta())
    val outPld = Axi4Stream(config)
    to[IpTxMeta, IpEncoder](outMd, outPld)
    awaitBuild()
    collectInto(metadata, ingress, acceptHostPackets = true, payloadBytes = _.length)
    val aligner = AxiStreamAligner(config)
    aligner.io.input << ingress
    val input = aligner.io.output
    val saved = Reg(NativeTcpTxMeta())
    val ip = Reg(IpHeader())
    val received = Reg(UInt(17 bits)) init 0
    val malformed = RegInit(False)
    val sum = Reg(UInt(32 bits)) init 0
    val checksum = Reg(Bits(16 bits))
    val firstOutput = RegInit(True)
    val nextPacketId = Reg(UInt(PacketID.width bits)) init 0
    val fifo = StreamFifo(Axi4Stream.Axi4StreamBundle(config), (MTU.get + config.dataWidth - 1) / config.dataWidth)
    val strip = AxiStreamExtractHeader(config, 20)()
    strip.io.input.setIdle()
    strip.io.header.ready := True
    strip.io.outputAck := outPld.lastFire
    outPld << strip.io.output
    metadata.setBlocked(); input.setBlocked(); outMd.setIdle()
    fifo.io.push.setIdle(); fifo.io.pop.setBlocked(); fifo.io.flush := False
    val count = CountOne(input.keep).resize(17)
    val contiguous = input.keep === (B((BigInt(1) << config.dataWidth) - 1, config.dataWidth bits) |>> (config.dataWidth - count))
    val incomingIp = IpHeader()
    incomingIp.assignFromBits(input.data(159 downto 0))
    def wordSum(raw: Bits): UInt = raw.subdivideIn(16 bits)
      .map(word => EndiannessSwap(word).asUInt.resize(32)).reduce(_ + _)
    def folded(value: UInt): UInt = {
      val first = value(15 downto 0).resize(17) + value(31 downto 16).resize(17)
      val second = first(15 downto 0).resize(17) + first(16).asUInt.resize(17)
      (second(15 downto 0).resize(17) + second(16).asUInt.resize(17)).resize(16)
    }
    val terms = (0 until config.dataWidth).map { lane =>
      val offset = received + lane
      val wanted = input.keep(lane) && offset >= 20 && offset < saved.length && offset =/= 36 && offset =/= 37
      val byte = input.data(lane * 8 + 7 downto lane * 8).asUInt
      Mux(wanted, byte.resize(32) |<< (if (lane % 2 == 0) 8 else 0), U(0, 32 bits))
    }
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          metadata.ready := True
          when(metadata.fire) {
            saved := metadata.payload; received := 0; sum := 0
            malformed := metadata.length < 40 || metadata.length > 1500
            firstOutput := True
            goto(collect)
          }
        }
      }
      val collect: State = new State {
        whenIsActive {
          input.ready := True
          fifo.io.push.valid := input.valid && !malformed
          fifo.io.push.payload := input.payload
          when(input.fire) {
            sum := sum + terms.reduce(_ + _)
            when(received === 0) {
              ip := incomingIp
              sum := terms.reduce(_ + _) + wordSum(incomingIp.saddr ## incomingIp.daddr) +
                (saved.length - 20).resize(32) + 6
              val headerBytes = input.data(263 downto 260).asUInt.resize(16) |<< 2
              when(count < 40 || incomingIp.version =/= 4 || incomingIp.ihl =/= 5 ||
                incomingIp.proto =/= 6 || EndiannessSwap(incomingIp.len).asUInt =/= saved.length ||
                (EndiannessSwap(incomingIp.flags) & B(0xbfff, 16 bits)) =/= 0 ||
                incomingIp.saddr =/= host[IpDecoder].logic.ipAddress ||
                input.data(175 downto 160) =/= saved.sport ||
                headerBytes < 20 || headerBytes > saved.length - 20) { malformed := True }
            }
            received := (received + count).resized
            when(!contiguous || (!input.last && !input.keep.andR) ||
              (!malformed && !fifo.io.push.ready)) { malformed := True }
            when(input.last) { goto(validate) }
          }
        }
      }
      val validate: State = new State {
        whenIsActive {
          when(malformed || received =/= saved.length) {
            fifo.io.flush := True; goto(idle)
          } otherwise {
            checksum := EndiannessSwap(~folded(sum).asBits)
            goto(describe)
          }
        }
      }
      val describe: State = new State {
        whenIsActive {
          outMd.valid := True
          outMd.packetId := nextPacketId
          outMd.rpcId := invalidTraceId(RpcID.width)
          outMd.daddr := ip.daddr; outMd.proto := 6
          outMd.pldLen := saved.length - 20
          when(outMd.fire) { nextPacketId := nextPacketId + 1; goto(deliver) }
        }
      }
      val deliver: State = new State {
        whenIsActive {
          strip.io.input << fifo.io.pop
          when(firstOutput) { strip.io.input.data(303 downto 288) := checksum }
          when(strip.io.input.fire) { firstOutput := False }
          when(outPld.lastFire) { goto(idle) }
        }
      }
    }
  }
}
