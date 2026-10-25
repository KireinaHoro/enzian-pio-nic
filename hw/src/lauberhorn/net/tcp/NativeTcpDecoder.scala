package lauberhorn.net.tcp

import jsteward.blocks.axi.{AxiStreamAligner, AxiStreamInjectHeader}
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.net._
import lauberhorn.net.ip.{IpDecoder, IpRxMeta}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.regif.AccessType.{RO, WO}
import spinal.lib.fsm._

case class TcpListenDef() extends Bundle {
  val enabled = Bool()
  val port = Bits(16 bits)
  val pid = PID()
  val endpoint = Bits(32 bits)
}
case class NativeTcpRxMeta() extends Bundle with DecoderMetadata {
  override def clone = NativeTcpRxMeta()
  val pid = PID()
  val endpoint = Bits(32 bits)
  val length = UInt(16 bits)
  val packetId = UInt(PacketID.width bits)
  def getType = PacketDescType.nativeTcp
  def getPayloadSize = length
  def collectHeaders = B(0, BYPASS_HDR_WIDTH bits)
  def asUnion: PacketDescData = {
    val result = PacketDescData().assignDontCare()
    result.nativeTcpRx.get := this
    result
  }
}

/** Listen-port steering only. The complete validated IPv4/TCP packet is host
  * payload; there is no connection table or sequence processing here.
  * Unmatched packets retain the ordinary IP bypass path without truncation.
  */
class NativeTcpDecoder extends Decoder[NativeTcpRxMeta] {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val ctrl = AxiLite4SlaveFactory(bus)
    val entry = TcpListenDef()
    entry.elements.foreach { case (name, field) =>
      ctrl.drive(field, alloc("ctrl", s"TCP listener $name", s"listen_$name", attr = WO))
    }
    val index = UInt(log2Up(NUM_LISTEN_PORTS) bits)
    index := 0
    val address = alloc("ctrl", "Commit TCP listen entry", "listen_idx", attr = WO)
    ctrl.write(index, address)
    logic.listenDb.update.setIdle()
    ctrl.onWrite(address) {
      logic.listenDb.update.valid := True
      logic.listenDb.update.idx := index
      logic.listenDb.update.value.enabled := entry.enabled
      logic.listenDb.update.value.pid := entry.pid
      logic.listenDb.update.value.endpoint := entry.endpoint
      logic.listenDb.update.value.port := EndiannessSwap(entry.port)
    }
    ctrl.read(logic.dropCount.value, alloc("stat", "Rejected bound TCP segments", "dropCount", attr = RO))
  }
  val logic = during setup new Area {
    val config = host[MacInterfaceService].axisConfig
    val header = Stream(IpRxMeta())
    val ingress = Axi4Stream(config)
    // Options/fragments remain intact on IP bypass, including noninitial fragments.
    from[IpRxMeta, IpDecoder](m => m.getPayloadSize >= 4 && m.hdr.proto === 6 && m.hdr.version === 4 && m.hdr.ihl === 5 &&
      (EndiannessSwap(m.hdr.flags) & B(0xbfff, 16 bits)) === 0, header, ingress)
    val listenDb = LookupTable(TcpListenDef(), NUM_LISTEN_PORTS)(_.enabled init False)
    val (lookup, result, _) = listenDb.makePort(Bits(16 bits), NoData(), "rx", singleMatch = true) {
      (entry, port, _) => entry.enabled && entry.port === port
    }
    val (txLookup, txResult, _) = listenDb.makePort(Bits(32 bits), PID(), "tx", singleMatch = true) {
      (entry, handle, _) => entry.enabled && entry.endpoint === handle
    }
    val admission = Flow(PID())
    awaitBuild()
    val localAddress = host[IpDecoder].logic.ipAddress
    val aligner = AxiStreamAligner(config)
    aligner.io.input << ingress
    // Buffer the first aligned beat while the listen lookup holds it. The
    // aligner needs downstream readiness to discard its initial partial beat.
    val input = aligner.io.output.m2sPipe()
    val saved = Reg(IpRxMeta())
    val endpoint = Reg(Bits(32 bits))
    val pid = Reg(PID())
    val length = Reg(UInt(16 bits))
    val received = Reg(UInt(17 bits)) init 0
    val malformed = RegInit(False)
    val checksumPipeline = new lauberhorn.net.PacketChecksum(config.dataWidth)
    checksumPipeline.io.seed.valid := False
    checksumPipeline.io.seed.payload := 0
    checksumPipeline.io.beat.valid := False
    val sum = checksumPipeline.io.sum
    val fifo = StreamFifo(Axi4Stream.Axi4StreamBundle(config), (MTU.get + config.dataWidth - 1) / config.dataWidth)
    val inject = AxiStreamInjectHeader(config, 20)
    inject.io.input << fifo.io.pop
    inject.io.header.setIdle()
    val metadata = Stream(NativeTcpRxMeta())
    val output = Axi4Stream(config)
    output << inject.io.output
    val ack, dropped = Bool()
    produceFinal(metadata, output, ack, dropped)
    val bypassHeader = Stream(IpRxMeta())
    val bypassPayload = Axi4Stream(config)
    val bypassAck = Bool()
    host[DecoderSinkService].consume(DecoderOutput(300, "NativeTcpBypass",
      bypassHeader.map(_.toRxTaggedDesc(true)), bypassPayload, bypassAck, False))
    produceDone()
    admission.valid := metadata.fire
    admission.payload := metadata.pid
    val dropCount = Counter(REG_WIDTH bits, dropped)
    def wordSum(raw: Bits): UInt = raw.subdivideIn(16 bits)
      .map(word => EndiannessSwap(word).asUInt.resize(32)).reduceBalancedTree(_ + _)
    def folded(value: UInt): UInt = {
      val first = value(15 downto 0).resize(17) + value(31 downto 16).resize(17)
      val second = first(15 downto 0).resize(17) + first(16).asUInt.resize(17)
      (second(15 downto 0).resize(17) + second(16).asUInt.resize(17)).resize(16)
    }
    val keep = Bits(config.dataWidth bits)
    val terms = (0 until config.dataWidth).map { i =>
      keep(i) := input.keep(i) && received + i < length.resize(17)
      val byte = input.data(i * 8 + 7 downto i * 8).asUInt
      Mux(keep(i), byte.resize(16) |<< (if (i % 2 == 0) 8 else 0), U(0, 16 bits))
    }
    val count = CountOne(input.keep).resize(17)
    val contiguous = input.keep === (B((BigInt(1) << config.dataWidth) - 1, config.dataWidth bits) |>> (config.dataWidth - count))
    header.setBlocked(); input.setBlocked(); lookup.setIdle(); result.setBlocked()
    fifo.io.push.setIdle(); fifo.io.flush := False
    metadata.setIdle(); bypassHeader.setIdle(); bypassPayload.setIdle(); dropped := False
    checksumPipeline.io.beat.payload := Vec(terms)
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          header.ready := True
          when(header.fire) { saved := header.payload; goto(query) }
        }
      }
      val query: State = new State {
        whenIsActive {
          lookup.valid := input.valid
          lookup.query := input.data(31 downto 16)
          when(lookup.fire) { goto(classify) }
        }
      }
      val classify: State = new State {
        whenIsActive {
          result.ready := True
          when(result.fire) {
            when(!result.matched || saved.hdr.daddr =/= localAddress) { goto(bypassDescribe) } otherwise {
              endpoint := result.value.endpoint; pid := result.value.pid
              val ipLength = EndiannessSwap(saved.hdr.len).asUInt
              val tcpLength = ipLength - 20
              length := tcpLength
              received := 0
              // Global MTU sizes Ethernet buffers; the IP transport limit is 1500.
              malformed := ipLength < 40 || ipLength > 1500 ||
                saved.ethMeta.getPayloadSize < ipLength || folded(wordSum(saved.hdr.asBits)) =/= 65535
              checksumPipeline.io.seed.valid := True
              checksumPipeline.io.seed.payload := wordSum(saved.hdr.saddr ## saved.hdr.daddr) + tcpLength.resize(32) + 6
              goto(collect)
            }
          }
        }
      }
      val bypassDescribe: State = new State {
        whenIsActive {
          bypassHeader.valid := True; bypassHeader.payload := saved
          when(bypassHeader.fire) { goto(bypassBody) }
        }
      }
      val bypassBody: State = new State {
        whenIsActive {
          bypassPayload << input
          when(input.lastFire) { goto(idle) }
        }
      }
      val collect: State = new State {
        whenIsActive {
          input.ready := True
          fifo.io.push.valid := input.valid && keep.orR && !malformed
          fifo.io.push.data := input.data
          fifo.io.push.keep := keep
          fifo.io.push.last := received + count >= length.resize(17)
          when(input.fire) {
            when(received === 0) {
              val headerBytes = (input.data(103 downto 100).asUInt.resize(16) |<< 2)
              when(count < 20 || headerBytes < 20 || headerBytes > length) { malformed := True }
            }
            when(received < 65536) { received := (received + count).resized }
            checksumPipeline.io.beat.valid := True
            when(!contiguous || (!input.last && !input.keep.andR) ||
              (keep.orR && !malformed && !fifo.io.push.ready)) { malformed := True }
            when(input.last) { goto(drainChecksum) }
          }
        }
      }
      val drainChecksum: State = new State {
        whenIsActive { when(!checksumPipeline.io.busy) { goto(validate) } }
      }
      val validate: State = new State {
        whenIsActive {
          when(malformed || received < length.resize(17) || folded(sum) =/= 65535) {
            fifo.io.flush := True; dropped := True; goto(idle)
          } otherwise { goto(describe) }
        }
      }
      val describe: State = new State {
        whenIsActive {
          metadata.valid := True
          metadata.pid := pid; metadata.endpoint := endpoint
          metadata.length := length + 20; metadata.packetId := saved.packetId
          when(metadata.fire) { goto(injectHeader) }
        }
      }
      val injectHeader: State = new State {
        whenIsActive {
          inject.io.header.valid := True; inject.io.header.payload := saved.hdr.asBits
          when(inject.io.header.fire) { goto(deliver) }
        }
      }
      val deliver: State = new State {
        whenIsActive { when(output.lastFire) { goto(idle) } }
      }
    }
  }
}
