package pionic.net

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.misc.BusSlaveFactory

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

case class OncRpcCallMetadata()(implicit config: PioNicConfig) extends Bundle with ProtoPacketDesc {
  override def clone = OncRpcCallMetadata()

  val funcPtr = Bits(64 bits)
  // first fields in the XDR payload
  val args = Bits(config.maxOncRpcInlineBytes * 8 bits)
  // TODO: protection domain information? aux data?
  val hdr = OncRpcCallHeader()
  val udpMeta = UdpMetadata()

  def getType = ProtoPacketDescType.oncRpcCall
  def getPayloadSize: UInt = udpMeta.getPayloadSize - hdr.getBitsWidth / 8
  def collectHeaders: Bits = udpMeta.collectHeaders ## hdr.asBits
  def asUnion: ProtoPacketDescData = {
    val ret = ProtoPacketDescData() setCompositeName (this, "union")
    ret.oncRpcCall.get := this
    ret
  }
}

class OncRpcCallDecoder(numListenPorts: Int = 4, numServiceSlots: Int = 4)(implicit config: PioNicConfig) extends ProtoDecoder[OncRpcCallMetadata] {
  lazy val macIf = host[MacInterfaceService]

  def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
    logic.decoder.io.statistics.flattenForeach { stat =>
      busCtrl.read(stat, alloc("oncRpcStats", stat.getName()))
    }
    logic.listenPorts.zipWithIndex foreach { case (portSlot, idx) =>
      busCtrl.driveAndRead(portSlot.valid, alloc("oncRpcCtrl", s"listenPort_${idx}_enabled")) init False
      busCtrl.driveAndRead(portSlot.payload, alloc("oncRpcCtrl", s"listenPort_${idx}"))
    }
    logic.serviceSlots.zipWithIndex foreach { case (serviceSlot, idx) =>
      serviceSlot.flattenForeach { item =>
        busCtrl.driveAndRead(item, alloc("oncRpcCtrl", s"service_${idx}_${item.getName()}"))
      }
    }

    // generate coreMask and changed signal for packet sink scheduler
    val coreMaskAddr = alloc("oncRpcCtrl", "coreMask")
    busCtrl.driveAndRead(logic.coreMask, coreMaskAddr)
    val changed = False
    busCtrl.onWrite(coreMaskAddr) {
      changed := True
    }
    logic.coreMaskChanged := changed
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpMetadata())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    val listenPorts = Vec.fill(numListenPorts)(Flow(UInt(16 bits)))
    val serviceSlots = Vec.fill(numServiceSlots)(new Bundle {
      val enabled = Bool()
      val progNum = Bits(32 bits)
      val progVer = Bits(32 bits)
      val proc = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      // TODO: protection domain information? aux data?

      def matchHeader(h: OncRpcCallHeader) = enabled && progNum === h.progNum && progVer === h.progVer && proc === h.proc
    })

    val coreMask = Bits(config.numCores bits)
    val coreMaskChanged = Bool()

    from[UdpMetadata, UdpDecoder]( { meta =>
        listenPorts.map { portSlot =>
          portSlot.valid && portSlot.payload === meta.hdr.dport.asUInt
        }.reduceBalancedTree(_ || _)
      },
      udpHeader, udpPayload
    )

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(OncRpcCallMetadata())

    // we do not invoke produce: there should be no downstream decoders
    produceFinal(metadata, payload, coreMask, coreMaskChanged)
    produceDone()

    awaitBuild()
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, OncRpcCallHeader().getBitsWidth / 8)
    // TODO: add extra decoder to decode first fields in the XDR payload
    // TODO: endianness swap
    // TODO: variable length field memory allocation (arena-style?)

    val currentUdpHeader = udpHeader.toReg()

    val drop = Bool()
    udpPayload >> decoder.io.input
    payload << decoder.io.output.throwFrameWhen(drop)
    metadata << decoder.io.header.throwWhen(drop).map { hdr =>
      val meta = OncRpcCallMetadata()
      meta.hdr.assignFromBits(hdr)
      meta.udpMeta := currentUdpHeader

      val matches = serviceSlots.map(_.matchHeader(meta.hdr))
      drop := !matches.reduceBalancedTree(_ || _)
      // TODO: also drop malformed packets (e.g. payload too short)

      meta.funcPtr := PriorityMux(matches, serviceSlots.map(_.funcPtr))

      meta
    }
  }
}