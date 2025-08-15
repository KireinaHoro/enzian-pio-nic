package lauberhorn.net

import jsteward.blocks.axi._
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global._
import lauberhorn._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
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
         |constants udp_next_proto width(${UdpNextProto().getBitsWidth}) "UDP Listener Protocol" {
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

case class UdpListenLookupUserData() extends Bundle {
  val hdr = UdpHeader()
  val ipMeta = IpMetadata()
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

    logic.listenDb.io.update.setIdle()

    val idx = UInt(log2Up(NUM_LISTEN_PORTS) bits)
    idx := 0
    val idxAddr = alloc("ctrl", "listen_idx", attr = AccessType.WO)
    busCtrl.write(idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      // record listen port in table
      // XXX: assumes host is LITTLE ENDIAN
      //      we swap endianness now already to shorten critical path
      logic.listenDb.io.update.valid := True
      logic.listenDb.io.update.idx := idx
      logic.listenDb.io.update.value.port := EndiannessSwap(writePort.port)
      logic.listenDb.io.update.value.nextProto := writePort.nextProto
    }

    // TODO: interface is write-only.  Provide readback port as well (like scheduler)

    UdpNextProto.addMackerel()
  }

  val logic = during setup new Area {
    // check if the packet is from a UDP port that has a listener;
    // otherwise it gets into the bypass interface (to host)
    // XXX: contents are in BIG ENDIAN (network)
    val listenDb = LookupTable[
      UdpListenDef, Bits, UdpListenLookupUserData,
    ](UdpListenDef(), Bits(16 bits), UdpListenLookupUserData(),
      numElems = NUM_LISTEN_PORTS,
      valueInit = (v: UdpListenDef) => v.nextProto init UdpNextProto.disabled,
      matchFunc = (v: UdpListenDef, q: Bits) => v.nextProto =/= UdpNextProto.disabled && v.port === q,
    )

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
    ipPayload >> decoder.io.input

    val currentIpHeader = ipHeader.toFlowFire.toReg()
    ipHeader.ready.setAsReg().init(True)
      .clearWhen(ipHeader.fire)
      .setWhen(decoder.io.header.fire)

    // TODO: drop packet based on checksum?
    val drop = !listenDb.io.result.matched && !isPromisc

    // we don't know if we need to drop the payload until we know the lookup result;
    // so delay the payload flow by the latency of a table lookup
    val pldDelayCycles = listenDb.lookupLatency
    val dropFlow = decoder.io.header.asFlow.delay(pldDelayCycles) ~ drop
    payload << decoder.io.output.delay(pldDelayCycles).throwFrameWhen(dropFlow)

    val hdrParsed = UdpHeader()
    listenDb.io.lookup.translateFrom(decoder.io.header) { case (lk, hdr) =>
      hdrParsed.assignFromBits(hdr)

      lk.query := hdrParsed.dport
      lk.userData.hdr := hdrParsed
      lk.userData.ipMeta := currentIpHeader
    }

    metadata.translateFrom(listenDb.io.result.throwWhen(drop)) { case (md, lr) =>
      md.hdr := lr.userData.hdr
      md.ipMeta := lr.userData.ipMeta
      md.nextProto := lr.value.nextProto
    }
  }
}
