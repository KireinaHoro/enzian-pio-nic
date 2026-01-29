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

    val decoder = AxiStreamExtractHeader(macIf.axisConfig, IpHeader().getBitsWidth / 8)() // IPv4 without options
    // TODO: chain output with secondary decoder to decode IP options

    val drop = Bool()
    produce(metadata, decoder.io.output, decoder.io.outputAck, priority = 100, drop)
    produceDone()

    val lastEthMeta = ethernetHeader.toFlowFire.toReg()
    ethernetHeader.ready.setAsReg().init(True)
      .clearWhen(ethernetHeader.fire)
      .setWhen(decoder.io.header.fire)

    ethernetPayload >> decoder.io.input
    metadata << decoder.io.header.map { hdr =>
      new Composite(this, "remap") {
        val meta = IpRxMeta()
        meta.hdr.assignFromBits(hdr)
        meta.ethMeta := lastEthMeta

        // allow unicast, multicast, broadcast
        val isLocalBroadcast = meta.hdr.daddr.andR
        val isDirectedBroadcast = host(meta.hdr.daddr) === hostMask && subnet(meta.hdr.daddr) === subnet(ipAddress)
        val isBroadcast = isLocalBroadcast || isDirectedBroadcast
        val isMulticast = meta.hdr.daddr(7 downto 4) === 0xe // 224.0.0.0/4

        // TODO: verify header checksum, version, etc.
        drop := meta.hdr.daddr =/= ipAddress && !isBroadcast && !isMulticast && !isPromisc
      }.meta
    }

    val dropCount = Counter(REG_WIDTH bits, drop && metadata.fire)
  }
}
