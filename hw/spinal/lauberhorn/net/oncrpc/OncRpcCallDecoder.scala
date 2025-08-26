package lauberhorn.net.oncrpc

import jsteward.blocks.axi._
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global._
import lauberhorn._
import lauberhorn.net._
import lauberhorn.net.udp.{UdpDecoder, UdpNextProto, UdpRxMeta}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType

import scala.language.postfixOps

case class OncRpcCallLookupUserData() extends Bundle {
  val hdr = OncRpcCallHeader()
  val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)

  // for sending event to encoder
  val addr = Bits(32 bits)
  val port = Bits(16 bits)
}

class OncRpcCallDecoder extends Decoder[OncRpcCallRxMeta] {
  lazy val macIf = host[MacInterfaceService]

  // FIXME: can we fit more?
  ONCRPC_INLINE_BYTES.set(4 * 12)

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("stat", name, attr = AccessType.RO))
    }

    // one port for each field + index register to latch into table
    val servicePort = OncRpcCallServiceDef()
    servicePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"service_$name", attr = AccessType.WO))
    }

    logic.serviceDb.update.setIdle()

    val serviceIdx = UInt(log2Up(NUM_SERVICES) bits)
    serviceIdx := 0
    val serviceIdxAddr = alloc("ctrl", "service_idx", attr = AccessType.WO)
    busCtrl.write(serviceIdx, serviceIdxAddr)
    busCtrl.onWrite(serviceIdxAddr) {
      // record service entry in table
      // XXX: assumes host is LITTLE ENDIAN
      //      we swap endianness now already to shorten critical path
      logic.serviceDb.update.valid := True
      logic.serviceDb.update.idx := serviceIdx
      (logic.serviceDb.update.value.elements.toSeq ++ servicePort.elements.toSeq).groupBy(_._1).foreach {
        case (n, Seq((_, te), (_, po))) if Seq("funcPtr", "pid").contains(n) => te := po
        case (_, Seq((_, te), (_, po: BitVector)))                           => te := EndiannessSwap(po)
        case (_, Seq((_, te), (_, po)))                                      => te := po
      }
    }

    // TODO: interface is write-only.  Provide readback port as well (like scheduler)
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpRxMeta())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    // we then try to match against a registered service
    // if no (func, port) is found, packet is dropped
    // will also be read by [[Scheduler]]
    // XXX: contents are in BIG ENDIAN (network)
    val serviceDb = LookupTable(OncRpcCallServiceDef(), NUM_SERVICES) { v =>
      v.enabled init False
    }

    val (dbLookup, dbResult, dbLat) = serviceDb.makePort(OncRpcCallServiceQuery(), OncRpcCallLookupUserData(),
      singleMatch = true) { (v, q, _) =>
      v.matchQuery(q)
    }

    // matcher for decode attempts: is the incoming packet on a port we are listening to?
    from[UdpRxMeta, UdpDecoder](_.nextProto === UdpNextProto.oncRpcCall, udpHeader, udpPayload)

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(OncRpcCallRxMeta())

    // we do not invoke produce: there should be no downstream decoders
    produceFinal(metadata, payload)
    produceDone()

    awaitBuild()
    val minLen = OncRpcCallHeader().getBitsWidth / 8
    val maxLen = minLen + ONCRPC_INLINE_BYTES
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, maxLen)(minLen)
    // TODO: variable length field memory allocation (arena-style?)

    val currentUdpHeader = udpHeader.toFlowFire.toReg()
    udpHeader.ready.setAsReg().init(True)
      .clearWhen(udpHeader.fire)
      .setWhen(decoder.io.header.fire)

    udpPayload >> decoder.io.input

    // TODO: also drop malformed packets (e.g. payload too short)
    val drop = !dbResult.matched

    // we don't know if we need to drop the payload until we know the lookup result;
    // so delay the payload flow by the latency of a table lookup
    val dropFlow = decoder.io.header.asFlow.delay(dbLat) ~ drop
    payload << decoder.io.output.delay(dbLat).throwFrameWhen(dropFlow)

    val hdrParsed = OncRpcCallHeader()
    dbLookup.translateFrom(decoder.io.header) { case (lk, hdr) =>
      hdrParsed.assignFromBits(hdr(minLen * 8 - 1 downto 0))
      lk.query.hdr := hdrParsed
      lk.query.port := currentUdpHeader.hdr.dport

      lk.userData.hdr := hdrParsed
      // TODO: endianness swap for host: these are in BIG ENDIAN
      lk.userData.args.assignFromBits(hdr(maxLen * 8 - 1 downto minLen * 8))
      lk.userData.udpPayloadSize := currentUdpHeader.getPayloadSize

      lk.userData.addr := currentUdpHeader.ipMeta.hdr.saddr
      lk.userData.port := currentUdpHeader.hdr.sport
    }

    when (decoder.io.header.fire) {
      // FIXME: should we just drop the packets?
      assert(hdrParsed.msgType === 0, "msg_type must be 0 for CALL (see RFC 5331)")
      assert(EndiannessSwap(hdrParsed.rpcVer) === 2, "rpcvers must be 2 (see RFC 5331)")

      assert(currentUdpHeader.nextProto === UdpNextProto.oncRpcCall, "no valid UDP header stored, did a payload leak through?")
    }

    metadata.translateFrom(dbResult.throwWhen(drop)) { case (md, lr) =>
      md.hdr := lr.userData.hdr
      md.args := lr.userData.args
      md.udpPayloadSize := lr.userData.udpPayloadSize
      md.funcPtr := lr.value.funcPtr
      md.pid := lr.value.pid
    }

    // record (pid, funcPtr, xid) -> (saddr, sport) mapping to allow construction of response
    val encoderPort = host[OncRpcReplyEncoder].logic.newSessionEvent
    encoderPort.translateFrom(dbResult.asFlow.throwWhen(drop)) { case (ep, lr) =>
      ep.clientPort := lr.userData.port
      ep.clientAddr := lr.userData.addr
      ep.serverPort := lr.value.listenPort.asBits
      ep.funcPtr    := lr.value.funcPtr
      ep.xid        := lr.userData.hdr.xid
      ep.active     := True
    }
  }
}
