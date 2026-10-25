package lauberhorn.net.udp

import jsteward.blocks.axi._
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global._
import lauberhorn._
import lauberhorn.net.ip.{IpDecoder, IpRxMeta}
import lauberhorn.net.{Decoder, DecoderMetadata, PacketDescData, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

/** What service is on this port? */
object UdpNextProto extends SpinalEnum {
  val disabled, oncRpcCall, oncRpcReply, nativeUdp = newElement()

  def addMackerel() = {
    ALLOC.addMackerelEpilogue(
      s"""
         |constants udp_next_proto width(${UdpNextProto().getBitsWidth}) "UDP Listener Protocol" {
         |  listen_disabled      = 0b00 "Disabled";
         |  listen_onc_rpc_call  = 0b01 "ONC-RPC Call";
         |  listen_onc_rpc_reply = 0b10 "ONC-RPC Reply";
         |  listen_native_udp = 0b11 "Native UDP";
         |};""".stripMargin)
  }
}

case class UdpListenDef() extends Bundle {
  val port = Bits(16 bits)
  val nextProto = UdpNextProto()
  val pid = PID()
  val endpoint = Bits(32 bits)
}

case class UdpListenLookupUserData() extends Bundle {
  val hdr = UdpHeader()
  val ipMeta = IpRxMeta()
}

case class UdpRxMeta() extends Bundle with DecoderMetadata {
  override def clone = UdpRxMeta()

  val nextProto = UdpNextProto()
  val pid = PID()
  val endpoint = Bits(32 bits)
  val hdr = UdpHeader()
  val ipMeta = IpRxMeta()

  def packetId: UInt = ipMeta.packetId
  def getType = PacketDescType.udp
  def getPayloadSize: UInt = (nextProto === UdpNextProto.nativeUdp) ? (EndiannessSwap(hdr.len).asUInt - 8) | (ipMeta.getPayloadSize - hdr.getBitsWidth / 8)
  def collectHeaders: Bits = hdr.asBits ## ipMeta.collectHeaders
  def asUnion: PacketDescData = {
    val ret = PacketDescData().assignDontCare()
    ret.udpRx.get := this
    ret
  }
}

class UdpDecoder extends Decoder[UdpRxMeta] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", s"Stat $name", name, attr = RO))
    }

    val writePort = UdpListenDef()
    writePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"Listen table update $name", s"listen_$name", attr = AccessType.WO))
    }

    logic.listenDb.update.setIdle()

    val idx = UInt(log2Up(NUM_LISTEN_PORTS) bits)
    idx := 0
    val idxAddr = alloc("ctrl", "Index of listen entry to update", "listen_idx", attr = AccessType.WO)
    busCtrl.write(idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      // record listen port in table
      // XXX: assumes host is LITTLE ENDIAN
      //      we swap endianness now already to shorten critical path
      logic.listenDb.update.valid := True
      logic.listenDb.update.idx := idx
      logic.listenDb.update.value.port := EndiannessSwap(writePort.port)
      logic.listenDb.update.value.nextProto := writePort.nextProto
      logic.listenDb.update.value.pid := writePort.pid
      logic.listenDb.update.value.endpoint := writePort.endpoint
    }

    // TODO: interface is write-only.  Provide readback port as well (like scheduler)

    UdpNextProto.addMackerel()
  }

  val logic = during setup new Area {
    // check if the packet is from a UDP port that has a listener;
    // otherwise it gets into the bypass interface (to host).  No
    // packets get dropped here

    // XXX: contents are in BIG ENDIAN (network)
    val listenDb = LookupTable(UdpListenDef(), NUM_LISTEN_PORTS) { v =>
      v.nextProto init UdpNextProto.disabled
    }

    val (dbLookup, dbResult, dbLat) = listenDb.makePort(Bits(16 bits), UdpListenLookupUserData(),
      singleMatch = true) { (v, q, _) =>
      v.nextProto =/= UdpNextProto.disabled && v.port === q
    }
    val (txLookup, txResult, _) = listenDb.makePort(Bits(32 bits), PID(), "nativeTx", singleMatch = true) {
      (entry, handle, _) => entry.nextProto === UdpNextProto.nativeUdp && entry.endpoint === handle
    }
    val ipHeader = Stream(IpRxMeta())
    val ipPayload = Axi4Stream(macIf.axisConfig)

    from[IpRxMeta, IpDecoder](
      m => m.hdr.proto === B("8'x11") && m.hdr.ihl === 5 &&
        (EndiannessSwap(m.hdr.flags) & B(0x3fff, 16 bits)) === 0,
      ipHeader, ipPayload
    )

    val localCallMd = Stream(UdpTxMeta())
    val localCallPld = Axi4Stream(macIf.axisConfig)
    val localReplyMd = Stream(UdpTxMeta())
    val localReplyPld = Axi4Stream(macIf.axisConfig)

    awaitBuild()

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(UdpRxMeta())

    val decoder = AxiStreamExtractHeader(macIf.axisConfig, UdpHeader().getBitsWidth / 8)()
    ipPayload >> decoder.io.input

    val outputAck = Bool()
    produce(metadata, payload, outputAck, priority = 200)
    produceDone()

    val currentIpHeader = ipHeader.toFlowFire.toReg()
    ipHeader.ready.setAsReg().init(True)
      .clearWhen(ipHeader.fire)
      .setWhen(decoder.io.header.fire)

    // Select one ingress until its payload and dispatch acknowledgment finish.
    // Round-robin service prevents either physical RX or local calls starving.
    val selected = Reg(UInt(2 bits)) init 0
    val active = RegInit(False)
    val queried = RegInit(False)
    val bodyDone = RegInit(False)
    val acknowledged = RegInit(False)
    val localPacketId = Reg(UInt(PacketID.width bits)) init 0
    val nextIngress = Reg(UInt(2 bits)) init 0
    val available = Seq(decoder.io.header.valid, localCallMd.valid, localReplyMd.valid)
    when(!active) {
      for (start <- 0 until 3) {
        when(nextIngress === start) {
          // Later assignments have priority: first available in rotated order.
          for (offset <- (0 until 3).reverse) {
            val index = (start + offset) % 3
            when(available(index)) { selected := index; active := True }
          }
        }
      }
    }
    dbLookup.valid := False
    dbLookup.payload.assignFromBits(B(0, dbLookup.payload.getBitsWidth bits))
    decoder.io.header.ready := False
    decoder.io.output.ready := False
    decoder.io.outputAck := outputAck && active && selected === 0
    localCallMd.ready := False
    localReplyMd.ready := False
    localCallPld.ready := False
    localReplyPld.ready := False
    payload.setIdle()

    when(active && selected === 0) {
      dbLookup.valid := decoder.io.header.valid && !queried
      val hdr = UdpHeader()
      hdr.assignFromBits(decoder.io.header.payload)
      dbLookup.query := hdr.dport
      dbLookup.userData.hdr := hdr
      dbLookup.userData.ipMeta := currentIpHeader
      decoder.io.header.ready := dbLookup.ready && !queried
      payload.valid := decoder.io.output.valid && queried
      payload.payload := decoder.io.output.payload
      decoder.io.output.ready := payload.ready && queried
      when(dbLookup.fire && currentIpHeader.getPayloadSize === 8) { bodyDone := True }
    }
    for ((md, pld, index) <- Seq((localCallMd, localCallPld, 1), (localReplyMd, localReplyPld, 2))) {
      when(active && selected === index) {
        dbLookup.valid := md.valid && !queried
        md.ready := dbLookup.ready && !queried
        dbLookup.query := md.dport
        val data = dbLookup.userData
        data.hdr.sport := md.sport
        data.hdr.dport := md.dport
        data.hdr.len := EndiannessSwap(md.pldLen + 8).asBits
        data.ipMeta.hdr.version := 4
        data.ipMeta.hdr.ihl := 5
        data.ipMeta.hdr.ttl := 64
        data.ipMeta.hdr.proto := 17
        data.ipMeta.hdr.len := EndiannessSwap(md.pldLen + 28).asBits
        data.ipMeta.hdr.saddr := host[IpDecoder].logic.ipAddress
        data.ipMeta.hdr.daddr := md.daddr
        data.ipMeta.ethMeta.packetId := localPacketId
        data.ipMeta.ethMeta.frameLen.bits := (md.pldLen + 42).resized
        data.ipMeta.ethMeta.hdr.etherType := EndiannessSwap(B(0x0800, 16 bits))
        payload.valid := pld.valid && queried
        payload.payload := pld.payload
        pld.ready := payload.ready && queried
        when(dbLookup.fire) { localPacketId := localPacketId + 1 }
      }
    }
    when(dbLookup.fire) { queried := True }
    when(payload.fire && payload.last) { bodyDone := True }
    when(outputAck && active) { acknowledged := True }
    when(active && bodyDone && acknowledged) {
      active := False
      nextIngress := (selected === 2) ? U(0) | (selected + 1)
      queried := False
      bodyDone := False
      acknowledged := False
    }

    metadata.translateFrom(dbResult) { case (md, lr) =>
      md.pid := lr.value.pid
      md.endpoint := lr.value.endpoint
      md.hdr := lr.userData.hdr
      md.ipMeta := lr.userData.ipMeta

      when (lr.matched) {
        md.nextProto := lr.value.nextProto
      } otherwise {
        // pass to bypass interface
        md.nextProto := UdpNextProto.disabled
      }
    }
  }
}
