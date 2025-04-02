package pionic.net

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegBlockAlloc
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType

import scala.language.postfixOps

case class OncRpcCallHeader() extends Bundle {
  val xid = Bits(32 bits)
  val msgType = Bits(32 bits)
  val rpcVer = Bits(32 bits)
  val progNum = Bits(32 bits)
  val progVer = Bits(32 bits)
  val proc = Bits(32 bits)
  val creds = Bits(64 bits)
  val verifier = Bits(64 bits)
}

case class OncRpcCallMetadata()(implicit c: ConfigDatabase) extends Bundle with ProtoPacketDesc {
  override def clone = OncRpcCallMetadata()

  val funcPtr = Bits(64 bits)
  val pid = PID()
  // first fields in the XDR payload
  val args = Bits(c[Int]("max onc rpc inline bytes") * 8 bits)
  val hdr = OncRpcCallHeader()
  val udpPayloadSize = UInt(Widths.lw bits)

  def getType = ProtoPacketDescType.oncRpcCall
  def getPayloadSize: UInt = {
    val inlineLen = c[Int]("max onc rpc inline bytes")
    val payloadLen = udpPayloadSize - hdr.getBitsWidth / 8
    (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
  }
  def collectHeaders: Bits = ??? // never collected
  def asUnion: ProtoPacketDescData = {
    val ret = ProtoPacketDescData() setCompositeName (this, "union")
    ret.oncRpcCall.get := this
    ret
  }
}

case class OncRpcCallServiceDef()(implicit c: ConfigDatabase) extends Bundle {
  val enabled = Bool()

  val progNum = Bits(32 bits)
  val progVer = Bits(32 bits)
  val proc = Bits(32 bits)
  val listenPort = UInt(16 bits)

  val funcPtr = Bits(64 bits)
  val pid = PID()

  def matchHeader(h: OncRpcCallHeader, port: Bits) = enabled &&
    progNum === EndiannessSwap(h.progNum) &&
    progVer === EndiannessSwap(h.progVer) &&
    proc === EndiannessSwap(h.proc) &&
    listenPort.asBits === port
}

class OncRpcCallDecoder() extends ProtoDecoder[OncRpcCallMetadata] {
  lazy val macIf = host[MacInterfaceService]
  
  lazy val numServiceSlots = c[Int]("num service slots")

  // FIXME: can we fit more?
  postConfig("max onc rpc inline bytes", 4 * 12, action = ConfigDatabase.Unique)

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("oncRpcStats", name, attr = AccessType.RO))
    }

    // one port for each field + index register to latch into table
    // XXX: interface is write-only.  SW needs to replicate this
    val servicePort = OncRpcCallServiceDef()
    servicePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("oncRpcCtrl", s"service_$name", attr = AccessType.WO))
    }

    val serviceIdx = UInt(log2Up(numServiceSlots) bits)
    serviceIdx := 0
    val serviceIdxAddr = alloc("oncRpcCtrl", "service_idx", attr = AccessType.WO)
    busCtrl.write(serviceIdx, serviceIdxAddr)
    busCtrl.onWrite(serviceIdxAddr) {
      // record service entry in table
      logic.listenPorts(serviceIdx).payload := servicePort.listenPort
      logic.listenPorts(serviceIdx).valid := servicePort.enabled
      
      logic.serviceSlots(serviceIdx) := servicePort
    }
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpMetadata())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    // we first check if the packet is from an UDP port that is listened on
    // otherwise it gets into the bypass interface (to host)
    // will also be read by [[Scheduler]]
    val listenPorts = Vec.fill(numServiceSlots)(Reg(Flow(UInt(16 bits))))
    listenPorts foreach { sl => sl.valid init False }

    // we then try to match against a registered service
    // if no (func, port) is found, packet is dropped
    // will also be read by [[Scheduler]]
    val serviceSlots = Vec.fill(numServiceSlots)(Reg(OncRpcCallServiceDef()))
    serviceSlots foreach { sl => sl.enabled init False }

    from[UdpMetadata, UdpDecoder]( { meta =>
        listenPorts.map { portSlot =>
          portSlot.valid && portSlot.payload === EndiannessSwap(meta.hdr.dport.asUInt)
        }.reduceBalancedTree(_ || _)
      },
      udpHeader, udpPayload
    )

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(OncRpcCallMetadata())

    // we do not invoke produce: there should be no downstream decoders
    produceFinal(metadata, payload)
    produceDone()

    awaitBuild()
    val minLen = OncRpcCallHeader().getBitsWidth / 8
    val maxLen = minLen + c[Int]("max onc rpc inline bytes")
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, maxLen)(minLen)
    // TODO: endianness swap
    // TODO: variable length field memory allocation (arena-style?)

    val currentUdpHeader = udpHeader.toFlowFire.toReg()
    udpHeader.ready.setAsReg().init(True)
      .clearWhen(udpHeader.fire)
      .setWhen(decoder.io.header.fire)

    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop
    udpPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = OncRpcCallMetadata()
      meta.hdr.assignFromBits(hdr(minLen*8-1 downto 0))
      meta.args.assignFromBits(hdr(maxLen*8-1 downto minLen*8))
      meta.udpPayloadSize := currentUdpHeader.getPayloadSize

      // FIXME: this will create deep comb paths
      val matches = serviceSlots.map(_.matchHeader(meta.hdr, currentUdpHeader.hdr.dport))
      drop := !matches.reduceBalancedTree(_ || _)
      // TODO: also drop malformed packets (e.g. payload too short)

      meta.funcPtr := PriorityMux(matches, serviceSlots.map(_.funcPtr))
      meta.pid := PriorityMux(matches, serviceSlots.map(_.pid))

      meta
    }

    // TODO: record (pid, funcPtr, xid) -> (saddr, sport) mapping to allow construction of response
    //       this is used by the host for now and the reply encoder module in the future
  }
}

// TODO: for nested RPC calls (outgoing)
class OncRpcCallEncoder extends ProtoEncoder[OncRpcCallMetadata] {

}