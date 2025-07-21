package lauberhorn.net.oncrpc

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.Global._
import lauberhorn._
import lauberhorn.net._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType

import scala.language.postfixOps

class OncRpcCallDecoder extends ProtoDecoder[OncRpcCallMetadata] {
  lazy val macIf = host[MacInterfaceService]
  
  // FIXME: can we fit more?
  ONCRPC_INLINE_BYTES.set(4 * 12)

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("oncRpcStats", name, attr = AccessType.RO))
    }

    // one port for each field + index register to latch into table
    val servicePort = OncRpcCallServiceDef()
    servicePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("oncRpcCtrl", s"service_$name", attr = AccessType.WO))
    }

    val serviceIdx = UInt(log2Up(NUM_SERVICES) bits)
    serviceIdx := 0
    val serviceIdxAddr = alloc("oncRpcCtrl", "service_idx", attr = AccessType.WO)
    busCtrl.write(serviceIdx, serviceIdxAddr)
    busCtrl.onWrite(serviceIdxAddr) {
      // record service entry in table
      // XXX: assumes host is LITTLE ENDIAN
      //      we swap endianness now already to shorten critical path
      (logic.serviceSlots(serviceIdx).elements.toSeq ++ servicePort.elements.toSeq).groupBy(_._1).foreach {
        case (n, Seq((_, te), (_, po))) if Seq("funcPtr", "pid").contains(n) => te := po
        case (_, Seq((_, te), (_, po: BitVector)))                           => te := EndiannessSwap(po)
        case (_, Seq((_, te), (_, po)))                                      => te := po
      }
    }

    // TODO: interface is write-only.  Provide readback port as well (like scheduler)
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpMetadata())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    // we then try to match against a registered service
    // if no (func, port) is found, packet is dropped
    // will also be read by [[Scheduler]]
    // XXX: contents are in BIG ENDIAN (network)
    val serviceSlots = Vec.fill(NUM_SERVICES)(Reg(OncRpcCallServiceDef()))
    serviceSlots foreach { sl => sl.enabled init False }

    // matcher for decode attempts: is the incoming packet on a port we are listening to?
    from[UdpMetadata, UdpDecoder](_.nextProto === UdpNextProto.oncRpcCall, udpHeader, udpPayload)

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(OncRpcCallMetadata())

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

    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow.m2sPipe() ~ drop
    udpPayload >> decoder.io.input
    payload << decoder.io.output.m2sPipe().throwFrameWhen(dropFlow)

    val hdr = decoder.io.header.payload
    val hdrParsed = OncRpcCallHeader()
    hdrParsed.assignFromBits(hdr(minLen * 8 - 1 downto 0))

    metadata.hdr.setAsReg()
    metadata.args.setAsReg()
    metadata.udpPayloadSize.setAsReg()
    when (decoder.io.header.fire) {
      metadata.hdr := hdrParsed

      // FIXME: should we just drop the packets?
      assert(hdrParsed.msgType === 0, "msg_type must be 0 for CALL (see RFC 5331)")
      assert(EndiannessSwap(hdrParsed.rpcVer) === 2, "rpcvers must be 2 (see RFC 5331)")

      // TODO: endianness swap: these are in BIG ENDIAN
      metadata.args.assignFromBits(hdr(maxLen * 8 - 1 downto minLen * 8))
      metadata.udpPayloadSize := currentUdpHeader.getPayloadSize
    }

    // 1 cycle latency to select service:
    // cycle 0: match all services in parallel
    // cycle 1:
    // - drop packets that didn't match with any service
    // - read out funcPtr and PID of the selected service to assemble metadata
    val matches = serviceSlots.map { svc =>
      // break timing path
      RegNextWhen(
        svc.matchHeader(hdrParsed, currentUdpHeader.hdr.dport),
        decoder.io.header.fire)
    }.asBits()
    drop := !matches.orR
    // TODO: also drop malformed packets (e.g. payload too short)

    val selectedSvc = PriorityMux(matches, serviceSlots)
    metadata.funcPtr := selectedSvc.funcPtr
    metadata.pid := selectedSvc.pid

    metadata.arbitrationFrom(decoder.io.header.m2sPipe().throwWhen(drop))

    // TODO: record (pid, funcPtr, xid) -> (saddr, sport) mapping to allow construction of response
    //       this is used by the host for now and the reply encoder module in the future
  }
}
