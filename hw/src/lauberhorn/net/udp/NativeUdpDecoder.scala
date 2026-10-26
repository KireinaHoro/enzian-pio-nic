package lauberhorn.net.udp

import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.{Global, MacInterfaceService}
import lauberhorn.net.Decoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._
import spinal.lib.misc.pipeline._

/** Buffer before admission: invalid lengths never become host work.
  * The buffer holds one datagram, trims Ethernet padding, and drains malformed
  * input through its last beat. UDP checksums are not validated.
  * It has no peer or response-session state. */
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
    // Count/validate byte lanes before packet-state arithmetic. A local adjacent
    // transition check recognizes a low-lane contiguous mask without rebuilding
    // that mask through popcount, subtraction and a variable shift.
    val ingressPipe = new StagePipeline
    ingressPipe.node(0).arbitrateFrom(aligner.io.output)
    val capture = new ingressPipe.Area(0) {
      val BEAT = insert(aligner.io.output.payload)
      val COUNT = insert(CountOne(aligner.io.output.keep).resize(17))
      val CONTIGUOUS = insert(!Vec((1 until config.dataWidth).map { lane =>
        aligner.io.output.keep(lane) && !aligner.io.output.keep(lane - 1)
      }).asBits.orR)
    }
    val input = Axi4Stream(config)
    ingressPipe.node(1).driveTo(input) { case (payload, node) => payload := node(capture.BEAT) }
    val count = ingressPipe.node(1)(capture.COUNT)
    val contiguous = ingressPipe.node(1)(capture.CONTIGUOUS)
    ingressPipe.build()
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
    val remaining = Reg(UInt(17 bits)) init 0
    val malformed = RegInit(False)
    val headerErrors = Reg(Bits(5 bits)) init 0
    val lastReject = Reg(Bits(8 bits)) init 0
    val fifo = StreamFifo(Axi4Stream.Axi4StreamBundle(config), (1472 + config.dataWidth - 1) / config.dataWidth)
    fifo.io.push.setIdle()
    fifo.io.pop.setBlocked()
    fifo.io.flush := False
    header.setBlocked()
    input.setBlocked()
    metadata.setIdle()
    output.setIdle()
    dropped := False

    val keep = Bits(config.dataWidth bits)
    for (lane <- 0 until config.dataWidth) {
      keep(lane) := input.keep(lane) && remaining > lane
    }

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
            remaining := (udpLen - 8).resize(17)
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
            when(header.ipMeta.getPayloadSize === 8) { goto(validate) } otherwise { goto(collect) }
          }
        }
      }
      val collect: State = new State {
        whenIsActive {
          // Oversized/malformed packets must drain even once the bounded FIFO fills.
          input.ready := True
          fifo.io.push.valid := input.valid && keep.orR && !malformed
          fifo.io.push.data := input.data
          fifo.io.push.keep := keep
          fifo.io.push.last := count >= remaining
          when(input.fire) {
            remaining := Mux(count >= remaining, U(0, 17 bits), remaining - count)
            when(received < 65536) { received := (received + count).resized }
            when(!contiguous || (!input.last && !input.keep.andR) ||
                (keep.orR && !malformed && !fifo.io.push.ready)) { malformed := True }
            when(input.last) { goto(validate) }
          }
        }
      }
      val validate: State = new State {
        whenIsActive {
          lastReject := (False ##
            (received < length.resize(17)) ## malformed ## headerErrors)
          when(malformed || received < length.resize(17)) { goto(discard) } otherwise { goto(describe) }
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
