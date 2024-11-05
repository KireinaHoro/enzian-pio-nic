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
  // first fields in the XDR payload
  val args = Bits(c[Int]("max onc rpc inline bytes") * 8 bits)
  // TODO: protection domain information? aux data?
  val hdr = OncRpcCallHeader()
  val udpMeta = UdpMetadata()

  def getType = ProtoPacketDescType.oncRpcCall
  def getPayloadSize: UInt = {
    val inlineLen = c[Int]("max onc rpc inline bytes")
    val payloadLen = udpMeta.getPayloadSize - hdr.getBitsWidth / 8
    (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
  }
  def collectHeaders: Bits = hdr.asBits ## udpMeta.collectHeaders
  def asUnion: ProtoPacketDescData = {
    val ret = ProtoPacketDescData() setCompositeName (this, "union")
    ret.oncRpcCall.get := this
    ret
  }
}

case class OncRpcCallServiceDef() extends Bundle {
  val enabled = Bool()
  val progNum = Bits(32 bits)
  val progVer = Bits(32 bits)
  val proc = Bits(32 bits)
  val funcPtr = Bits(64 bits)
  // TODO: protection domain information? aux data?

  def matchHeader(h: OncRpcCallHeader) = enabled &&
    progNum === EndiannessSwap(h.progNum) &&
    progVer === EndiannessSwap(h.progVer) &&
    proc === EndiannessSwap(h.proc)
}

class OncRpcCallDecoder(numListenPorts: Int = 4, numServiceSlots: Int = 4) extends ProtoDecoder[OncRpcCallMetadata] {
  lazy val macIf = host[MacInterfaceService]

  // FIXME: can we fit more?
  postConfig("max onc rpc inline bytes", 4 * 12, action = ConfigDatabase.Unique)

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.decoder.io.statistics.elements.foreach { case (name, stat) =>
      busCtrl.read(stat, alloc("oncRpcStats", name, attr = AccessType.RO))
    }
    val listenEnableBlock = alloc.block("oncRpcCtrl", "listenPort_enabled", numListenPorts)
    val listenBlock = alloc.block("oncRpcCtrl", "listenPort", numListenPorts)
    logic.listenPorts.zipWithIndex foreach { case (portSlot, idx) =>
      busCtrl.driveAndRead(portSlot.valid, listenEnableBlock(idx)) init False
      busCtrl.driveAndRead(portSlot.payload, listenBlock(idx))
    }
    val serviceBlockMap = OncRpcCallServiceDef().elements.foldLeft(Map[String, Seq[BigInt]]()) { case (acc, (name, _)) =>
      acc + (name -> alloc.block("oncRpcCtrl", s"service_$name", numServiceSlots))
    }
    logic.serviceSlots.zipWithIndex foreach { case (serviceSlot, idx) =>
      serviceSlot.elements.foreach { case (name, item) =>
        busCtrl.driveAndRead(item, serviceBlockMap(name)(idx))
      }
    }

    // generate coreMask and changed signal for packet sink scheduler
    val coreMaskAddr = alloc("oncRpcCtrl", "coreMask", attr = AccessType.WO)
    busCtrl.driveFlow(logic.coreMask, coreMaskAddr)
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpMetadata())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    val listenPorts = Vec.fill(numListenPorts)(Flow(UInt(16 bits)))
    val serviceSlots = Vec.fill(numServiceSlots)(OncRpcCallServiceDef())

    val coreMask = Flow(Bits(numCores bits))

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
    produceFinal(metadata, payload, coreMask)
    produceDone()

    awaitBuild()
    val minLen = OncRpcCallHeader().getBitsWidth / 8
    val maxLen = minLen + c[Int]("max onc rpc inline bytes")
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, maxLen)(minLen)
    // TODO: endianness swap
    // TODO: variable length field memory allocation (arena-style?)

    val currentUdpHeader = udpHeader.toReg()

    val drop = Bool()
    val dropFlow = decoder.io.header.asFlow ~ drop
    udpPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(dropFlow)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = OncRpcCallMetadata()
      meta.hdr.assignFromBits(hdr(minLen*8-1 downto 0))
      meta.args.assignFromBits(hdr(maxLen*8-1 downto minLen*8))
      meta.udpMeta := currentUdpHeader

      val matches = serviceSlots.map(_.matchHeader(meta.hdr))
      drop := !matches.reduceBalancedTree(_ || _)
      // TODO: also drop malformed packets (e.g. payload too short)

      meta.funcPtr := PriorityMux(matches, serviceSlots.map(_.funcPtr))

      meta
    }
  }
}