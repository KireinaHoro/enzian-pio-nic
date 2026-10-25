package lauberhorn.net.udp

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.{Global, MacInterfaceService}
import lauberhorn.net.Decoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._

/** Buffer before admission: invalid checksums/lengths never become host work.
  * The buffer holds one datagram, trims Ethernet padding, and drains malformed
  * input through its last beat. It has no peer or response-session state. */
class NativeUdpDecoder extends Decoder[UdpRxMeta] {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val control = AxiLite4SlaveFactory(bus)
    control.read(logic.lastReject, alloc("stat", "Last rejection reason bitmask", "lastReject",
      attr = spinal.lib.bus.regif.AccessType.RO))
    control.read(logic.dropCount.value, alloc("stat", "Rejected native UDP datagrams", "dropCount",
      attr = spinal.lib.bus.regif.AccessType.RO))
  }

  val logic = during setup new Area {
    val config = host[MacInterfaceService].axisConfig
    val header = Stream(UdpRxMeta())
    val ingress = Axi4Stream(config)
    from[UdpRxMeta, UdpDecoder](_.nextProto === UdpNextProto.nativeUdp, header, ingress)
    val admission = Flow(lauberhorn.PID())
    awaitBuild()

    // Header extractors leave a gap at the low byte lanes of the first beat.
    val aligner = jsteward.blocks.axi.AxiStreamAligner(config)
    aligner.io.input << ingress
    val input = aligner.io.output
    val metadata = Stream(UdpRxMeta())
    val output = Axi4Stream(config)
    val ack = Bool()
    val dropped = Bool()
    produceFinal(metadata, output, ack, dropped)
    produceDone()
    admission.valid := metadata.fire
    admission.payload := metadata.pid
    val dropCount = Counter(Global.REG_WIDTH bits, dropped)

    val saved = Reg(UdpRxMeta())
    val length = Reg(UInt(16 bits))
    val received = Reg(UInt(17 bits)) init 0
    val malformed = RegInit(False)
    val headerErrors = Reg(Bits(5 bits)) init 0
    val lastReject = Reg(Bits(8 bits)) init 0
    val checksumPipeline = new lauberhorn.net.PacketChecksum(config.dataWidth)
    checksumPipeline.io.seed.valid := False
    checksumPipeline.io.seed.payload := 0
    checksumPipeline.io.beat.valid := False
    val sum = checksumPipeline.io.sum
    val fifo = StreamFifo(Axi4Stream.Axi4StreamBundle(config), (1472 + config.dataWidth - 1) / config.dataWidth)
    fifo.io.push.setIdle()
    fifo.io.pop.setBlocked()
    fifo.io.flush := False
    header.setBlocked()
    input.setBlocked()
    metadata.setIdle()
    output.setIdle()
    dropped := False

    def wordSum(raw: Bits): UInt = raw.subdivideIn(16 bits)
      .map(x => EndiannessSwap(x).asUInt.resize(32)).reduceBalancedTree(_ + _)
    def folded(value: UInt): UInt = {
      val first = value(15 downto 0).resize(17) + value(31 downto 16).resize(17)
      val second = first(15 downto 0).resize(17) + first(16).asUInt.resize(17)
      (second(15 downto 0).resize(17) + second(16).asUInt.resize(17)).resize(16)
    }

    // Capture pseudo-header terms alongside the accepted metadata. Seed the
    // accumulator on the following cycle, before admitting payload beats.
    val seedIp = RegNext(wordSum(header.ipMeta.hdr.saddr ## header.ipMeta.hdr.daddr))
    val seedUdp = RegNext(wordSum(header.hdr.asBits))
    val seedLength = RegNext(EndiannessSwap(header.hdr.len).asUInt.resize(32) + 17)

    val keep = Bits(config.dataWidth bits)
    val terms = (0 until config.dataWidth).map { i =>
      val lane = i
      val wanted = received + i < length.resize(17)
      keep(lane) := input.keep(lane) && wanted
      val byte = input.data(lane * 8 + 7 downto lane * 8).asUInt
      Mux(keep(lane), byte.resize(16) |<< (if (i % 2 == 0) 8 else 0), U(0, 16 bits))
    }
    val count = CountOne(input.keep).resize(17)
    val contiguous = input.keep === (B((BigInt(1) << config.dataWidth) - 1, config.dataWidth bits) |>> (config.dataWidth - count))

    checksumPipeline.io.beat.payload := Vec(terms)
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          header.ready := True
          when(header.fire) {
            saved := header.payload
            val ip = header.ipMeta.hdr
            val udpLen = EndiannessSwap(header.hdr.len).asUInt
            val ipLen = EndiannessSwap(ip.len).asUInt
            length := udpLen - 8
            received := 0
            val errors = Bits(5 bits)
            errors(0) := ip.version =/= 4 || ip.ihl =/= 5 ||
              (EndiannessSwap(ip.flags) & B(0xbfff, 16 bits)) =/= 0
            // IpDecoder validates the IPv4 checksum before UDP dispatch.
            // Retain this diagnostic bit position without duplicating its
            // full-header reduction on the native UDP admission path.
            errors(1) := False
            errors(2) := udpLen < 8 || udpLen > 1480 || ipLen =/= udpLen.resize(16) + 20
            errors(3) := ip.daddr =/= host[lauberhorn.net.ip.IpDecoder].logic.ipAddress
            errors(4) := header.ipMeta.ethMeta.getPayloadSize < ipLen
            headerErrors := errors
            malformed := errors.orR
            goto(seedChecksum)
          }
        }
      }
      val seedChecksum: State = new State {
        whenIsActive {
          checksumPipeline.io.seed.valid := True
          checksumPipeline.io.seed.payload := seedIp + seedUdp + seedLength
          when(saved.ipMeta.getPayloadSize === 8) { goto(drainChecksum) } otherwise { goto(collect) }
        }
      }
      val collect: State = new State {
        whenIsActive {
          // Oversized/malformed packets must drain even once the bounded FIFO fills.
          input.ready := True
          fifo.io.push.valid := input.valid && keep.orR && !malformed
          fifo.io.push.data := input.data
          fifo.io.push.keep := keep
          fifo.io.push.last := received + count >= length.resize(17)
          when(input.fire) {
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
          lastReject := ((saved.hdr.csum =/= 0 && folded(sum) =/= 65535) ##
            (received < length.resize(17)) ## malformed ## headerErrors)
          when(malformed || received < length.resize(17) ||
              (saved.hdr.csum =/= 0 && folded(sum) =/= 65535)) { goto(discard) } otherwise { goto(describe) }
        }
      }
      val discard: State = new State {
        whenIsActive { fifo.io.flush := True; dropped := True; goto(idle) }
      }
      val describe: State = new State {
        whenIsActive {
          metadata.valid := True
          metadata.payload := saved
          when(metadata.fire) {
            when(length === 0) { goto(idle) } otherwise { goto(deliver) }
          }
        }
      }
      val deliver: State = new State {
        whenIsActive {
          output << fifo.io.pop
          when(output.lastFire) { goto(idle) }
        }
      }
    }
  }
}
