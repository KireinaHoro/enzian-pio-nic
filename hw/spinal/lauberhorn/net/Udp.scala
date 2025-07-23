package lauberhorn.net

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global._
import lauberhorn._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

case class UdpHeader() extends Bundle {
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val len = Bits(16 bits)
  val csum = Bits(16 bits)
}

/** What service is on this port? */
object UdpNextProto extends SpinalEnum {
  val disabled, oncRpcCall, oncRpcReply = newElement()

  def addMackerel() = {
    ALLOC.addMackerelEpilogue(getClass,
      s"""
         |constants udp_next_proto width(${UdpListenDef().nextProto.getBitsWidth}) "UDP Listener Protocol" {
         |  listen_disabled      = 0b00 "Disabled";
         |  listen_onc_rpc_call  = 0b01 "ONC-RPC Call";
         |  listen_onc_rpc_reply = 0b10 "ONC-RPC Reply";
         |};""".stripMargin)
  }
}

case class UdpListenDef() extends Bundle {
  val port = Bits(16 bits)
  val nextProto = UdpNextProto()
}

case class UdpMetadata() extends Bundle with ProtoMetadata {
  override def clone = UdpMetadata()

  val nextProto = UdpNextProto()
  val hdr = UdpHeader()
  val ipMeta = IpMetadata()

  def getType = PacketDescType.udp
  def getPayloadSize: UInt = ipMeta.getPayloadSize - hdr.getBitsWidth / 8
  def collectHeaders: Bits = hdr.asBits ## ipMeta.collectHeaders
  def assignFromHdrBits(b: Bits): Unit = {
    ipMeta.assignFromHdrBits(b)
    hdr.assignFromBits(
      b(hdr.getBitsWidth-1 + ipMeta.hdr.getBitsWidth downto ipMeta.hdr.getBitsWidth))
  }
  def asUnion: PacketDescData = {
    val ret = PacketDescData().assignDontCare()
    ret.udp.get := this
    ret
  }
}

class UdpDecoder extends ProtoDecoder[UdpMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", name, attr = RO))
    }

    val writePort = UdpListenDef()
    writePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"listen_$name", attr = AccessType.WO))
    }

    val idx = UInt(log2Up(NUM_LISTEN_PORTS) bits)
    idx := 0
    val idxAddr = alloc("ctrl", "listen_idx", attr = AccessType.WO)
    busCtrl.write(idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      // record listen port in table
      // XXX: assumes host is LITTLE ENDIAN
      //      we swap endianness now already to shorten critical path
      logic.listenPorts(idx).port := EndiannessSwap(writePort.port)
      logic.listenPorts(idx).nextProto := writePort.nextProto
    }

    // TODO: interface is write-only.  Provide readback port as well (like scheduler)

    UdpNextProto.addMackerel()
  }

  val logic = during setup new Area {
    // check if the packet is from a UDP port that has a listener;
    // otherwise it gets into the bypass interface (to host)
    // XXX: contents are in BIG ENDIAN (network)
    val listenPorts = Vec.fill(NUM_LISTEN_PORTS)(Reg(UdpListenDef()))
    listenPorts foreach { sl => sl.nextProto init UdpNextProto.disabled }

    val ipHeader = Stream(IpMetadata())
    val ipPayload = Axi4Stream(macIf.axisConfig)

    from[IpMetadata, IpDecoder](
      _.hdr.proto === B("8'x11"), // 17 for UDP
      ipHeader, ipPayload
    )

    awaitBuild()

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(UdpMetadata())
    produce(metadata, payload)
    produceDone()

    val decoder = AxiStreamExtractHeader(macIf.axisConfig, UdpHeader().getBitsWidth / 8)()

    val currentIpHeader = ipHeader.toFlowFire.toReg()
    ipHeader.ready.setAsReg().init(True)
      .clearWhen(ipHeader.fire)
      .setWhen(decoder.io.header.fire)

    // XXX: NUM_LISTEN_PORTS is 16, no need to pipeline matching
    // TODO: automatically determine if (and how many stages) we need to pipeline
    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop

    ipPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr => new Composite(hdr, "findListener") {
      val meta = UdpMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.ipMeta := currentIpHeader

      val matches = listenPorts.map { lp =>
        lp.nextProto =/= UdpNextProto.disabled && lp.port === meta.hdr.dport
      }.asBits()

      drop := !matches.orR && !isPromisc

      val selectedListener = PriorityMux(matches, listenPorts)
      meta.nextProto := selectedListener.nextProto

      // TODO: drop packet based on checksum?
    }.meta }
  }
}
