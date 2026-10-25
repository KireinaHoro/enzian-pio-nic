package lauberhorn.net.ip

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global.REG_WIDTH
import lauberhorn._
import lauberhorn.net.ethernet.{EthernetDecoder, EthernetRxMeta}
import lauberhorn.net.Decoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

class IpDecoder extends Decoder[IpRxMeta] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", s"Stat $name", name, attr = RO))
    }
    busCtrl.read(logic.dropCount.value, alloc("stat", "Number of packets dropped", "dropCount", attr = RO))

    busCtrl.readAndWrite(logic.ipAddress, alloc("ctrl", "Our IP address", "ipAddress"))
    busCtrl.readAndWrite(logic.prefixLen, alloc("ctrl", "Our IP prefix length (0-32)", "prefixLen"))
  }

  val logic = during setup new Area {
    val ethernetHeader = Stream(EthernetRxMeta())
    val ethernetPayload = Axi4Stream(macIf.axisConfig)

    // 192.168.128.40; changed at runtime
    val ipAddress = Reg(Bits(32 bits)) init EndiannessSwap(B("32'xc0_a8_80_28"))
    val prefixLen = Reg(UInt(8 bits)) init 18 // 0 - 32
    val hostLen = 32 - prefixLen
    val hostMaskNext = ((U(1) << hostLen) - 1).resize(32).asBits
    val hostMask = RegNext(hostMaskNext)
    val netMask = RegNext(~hostMaskNext)

    def subnet(addr: Bits) = addr & netMask
    def host(addr: Bits) = addr & hostMask

    from[EthernetRxMeta, EthernetDecoder](
      _.hdr.etherType === EndiannessSwap(B("16'x0800")),
      ethernetHeader, ethernetPayload
    )

    awaitBuild()

    val metadata = Stream(IpRxMeta())

    val decoder = AxiStreamExtractHeader(macIf.axisConfig, IpHeader().getBitsWidth / 8)() // fixed base header; options stay on bypass

    val drop = Bool()
    produce(metadata, decoder.io.output, decoder.io.outputAck, priority = 100, drop)
    produceDone()

    val lastEthMeta = ethernetHeader.toFlowFire.toReg()
    ethernetHeader.ready.setAsReg().init(True)
      .clearWhen(ethernetHeader.fire)
      .setWhen(decoder.io.header.fire)

    // IPv4 headers are at most 60 bytes; the MAC already requires a beat at
    // least that wide. Align once so the entire header (including options) is
    // available without buffering a whole packet.
    require(macIf.axisConfig.dataWidth >= 60)
    val aligner = AxiStreamAligner(macIf.axisConfig)
    aligner.io.input << ethernetPayload
    decoder.io.input << aligner.io.output
    val firstBeat = RegInit(True)
    when(decoder.io.input.fire) { firstBeat := decoder.io.input.last }
    val incoming = IpHeader()
    incoming.assignFromBits(decoder.io.input.data(159 downto 0))
    val headerBytes = incoming.ihl.asUInt.resize(7) |<< 2
    val totalBytes = EndiannessSwap(incoming.len).asUInt
    val badShape = incoming.version =/= 4 || incoming.ihl.asUInt < 5 ||
      totalBytes < headerBytes || totalBytes > lastEthMeta.getPayloadSize ||
      CountOne(decoder.io.input.keep) < headerBytes
    val words = (0 until 30).map { i =>
      Mux(U(i * 2, 7 bits) < headerBytes,
        EndiannessSwap(decoder.io.input.data(i * 16 + 15 downto i * 16)).asUInt,
        U(0, 16 bits))
    }
    // Register each reduction level; do not recreate a long checksum cone.
    val sum = words.reduceBalancedTree((a, b) => {
      val wide = a +^ b
      (wide(15 downto 0) + wide.msb.asUInt).resize(16)
    }, (stage, level) => RegNext(stage))
    val checkLatency = LatencyAnalysis(incoming.ihl, sum)
    val checkReady = RegInit(False)
    val headerInvalid = RegInit(False)
    when(Delay(decoder.io.input.fire && firstBeat && CountOne(decoder.io.input.keep) >= 20, checkLatency, init = False)) {
      headerInvalid := Delay(badShape, checkLatency) || sum =/= 65535
      checkReady := True
    }
    when(decoder.io.header.fire) { checkReady := False }

    metadata << decoder.io.header.haltWhen(!checkReady).map { hdr =>
      new Composite(this, "remap") {
        val meta = IpRxMeta()
        meta.hdr.assignFromBits(hdr)
        meta.ethMeta := lastEthMeta

        // allow unicast, multicast, broadcast
        val isLocalBroadcast = meta.hdr.daddr.andR
        val isDirectedBroadcast = host(meta.hdr.daddr) === hostMask && subnet(meta.hdr.daddr) === subnet(ipAddress)
        val isBroadcast = isLocalBroadcast || isDirectedBroadcast
        val isMulticast = meta.hdr.daddr(7 downto 4) === 0xe // 224.0.0.0/4

        drop := headerInvalid ||
          (meta.hdr.daddr =/= ipAddress && !isBroadcast && !isMulticast && !isPromisc)
      }.meta
    }

    val dropCount = Counter(REG_WIDTH bits, drop && metadata.fire)
  }
}
