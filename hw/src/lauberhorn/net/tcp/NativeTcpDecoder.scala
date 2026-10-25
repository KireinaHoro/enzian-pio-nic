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
import spinal.lib.bus.regif.AccessType.WO
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

/** Listen-port steering only. The complete received IPv4 packet is host
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
  }
  val logic = during setup new Area {
    val config = host[MacInterfaceService].axisConfig
    val header = Stream(IpRxMeta())
    val ingress = Axi4Stream(config)
    // The shared IP decoder validates headers. Options and fragments retain
    // IP bypass; ordinary TCP is selected only by destination port below.
    from[IpRxMeta, IpDecoder](m => m.getPayloadSize >= 4 && m.hdr.proto === 6 &&
      m.hdr.ihl === 5 && (EndiannessSwap(m.hdr.flags) & B(0x3fff, 16 bits)) === 0,
      header, ingress)
    val listenDb = LookupTable(TcpListenDef(), NUM_LISTEN_PORTS)(_.enabled init False)
    val (lookup, result, _) = listenDb.makePort(Bits(16 bits), NoData(), "rx", singleMatch = true) {
      (entry, port, _) => entry.enabled && entry.port === port
    }
    val (txLookup, txResult, _) = listenDb.makePort(Bits(32 bits), PID(), "tx", singleMatch = true) {
      (entry, handle, _) => entry.enabled && entry.endpoint === handle
    }
    val admission = Flow(PID())
    awaitBuild()
    val aligner = AxiStreamAligner(config)
    aligner.io.input << ingress
    // Buffer the first aligned beat while the listen lookup holds it. The
    // aligner needs downstream readiness to discard its initial partial beat.
    val input = aligner.io.output.m2sPipe()
    val saved = Reg(IpRxMeta())
    val endpoint = Reg(Bits(32 bits))
    val pid = Reg(PID())
    val inject = AxiStreamInjectHeader(config, 20)
    inject.io.input.setIdle()
    inject.io.header.setIdle()
    val metadata = Stream(NativeTcpRxMeta())
    val output = Axi4Stream(config)
    output << inject.io.output.pipelined(StreamPipe.FULL)
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
    header.setBlocked(); input.setBlocked(); lookup.setIdle(); result.setBlocked()
    metadata.setIdle(); bypassHeader.setIdle(); bypassPayload.setIdle(); dropped := False
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
            when(!result.matched) { goto(bypassDescribe) } otherwise {
              endpoint := result.value.endpoint; pid := result.value.pid
              goto(describe)
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
      val describe: State = new State {
        whenIsActive {
          metadata.valid := True
          metadata.pid := pid; metadata.endpoint := endpoint
          metadata.length := saved.ethMeta.getPayloadSize; metadata.packetId := saved.packetId
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
        whenIsActive {
          inject.io.input << input
          when(output.lastFire) { goto(idle) }
        }
      }
    }
  }
}
