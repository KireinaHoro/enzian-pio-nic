package lauberhorn.net.oncrpc

import jsteward.blocks.axi._
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global.{NUM_SESSIONS, ONCRPC_INLINE_BYTES, PKT_BUF_LEN_WIDTH, REG_WIDTH}
import lauberhorn.{PID, PacketID, RpcID, TraceData}
import lauberhorn.net.udp.{UdpDecoder, UdpNextProto, UdpRxMeta}
import lauberhorn.net.{PacketDescData, Decoder, DecoderMetadata, PacketDescType}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.amba4.axilite.AxiLite4SlaveFactory
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

import scala.language.postfixOps

case class OncRpcReplyRxMeta() extends Bundle with DecoderMetadata {
  override def clone = OncRpcReplyRxMeta()

  val packetId = UInt(PacketID.width bits)
  val rpcId = UInt(RpcID.width bits)
  val pid = PID()
  val cookie = Bits(32 bits)
  val xid = Bits(32 bits)
  val saddr = Bits(32 bits)
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
  val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)

  def getType: PacketDescType.E = PacketDescType.oncRpcReplyRx
  override def traceData: Seq[TraceData] = Seq(PacketID(packetId), RpcID(rpcId))

  def getPayloadSize: UInt = {
    val inlineLen = ONCRPC_INLINE_BYTES.get
    val payloadLen = udpPayloadSize - OncRpcReplyHeader().getBitsWidth / 8
    (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
  }

  def collectHeaders: Bits = ??? // never collected

  def asUnion: PacketDescData = {
    val ret = PacketDescData().assignDontCare()
    ret.oncRpcReplyRx.get := this
    ret
  }
}

case class OncRpcReplyLookupUserData() extends Bundle {
  val packetId = UInt(PacketID.width bits)
  val hdr = OncRpcReplyHeader()
  val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)
  val saddr = Bits(32 bits)
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
}

class OncRpcReplyDecoder extends Decoder[OncRpcReplyRxMeta] {
  lazy val macIf = host[lauberhorn.MacInterfaceService]

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    val writePort = OncRpcInFlightReqDef()
    writePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"In-flight request update $name",
        s"req_$name", attr = AccessType.WO))
    }

    val updateIdx = UInt(log2Up(NUM_SESSIONS) bits)
    updateIdx := 0
    val updateIdxAddr = alloc("ctrl", "Index of in-flight request entry to update",
      "req_idx", attr = AccessType.WO)
    busCtrl.write(updateIdx, updateIdxAddr)
    busCtrl.onWrite(updateIdxAddr) {
      logic.requestDb.update.valid := True
      logic.requestDb.update.idx := updateIdx
      logic.requestDb.update.value := writePort
    }

    val readbackIdxAddr = alloc("stat", "Index of in-flight request entry to read back",
      "req_readback_idx", attr = AccessType.WO)
    busCtrl.drive(logic.requestDb.readbackIdx, readbackIdxAddr)
    logic.requestDb.readback.elements.foreach { case (name, field) =>
      busCtrl.read(field, alloc("stat", s"In-flight request readback $name",
        s"req_readback_$name", attr = AccessType.RO))
    }

    busCtrl.read(logic.reqTblFull.value, alloc("stat", "Number of times in-flight request table became full",
      "reqTblFull", attr = AccessType.RO))
    busCtrl.read(logic.lookupMiss.value, alloc("stat", "Number of replies without an in-flight request match",
      "lookupMiss", attr = AccessType.RO))
    busCtrl.read(logic.dropped.value, alloc("stat", "Number of malformed or dropped replies",
      "dropped", attr = AccessType.RO))
  }

  val logic = during setup new Area {
    val udpHeader = Stream(UdpRxMeta())
    val udpPayload = Axi4Stream(macIf.axisConfig)

    from[UdpRxMeta, UdpDecoder](_.nextProto === UdpNextProto.oncRpcReply, udpHeader, udpPayload)

    val requestDb = LookupTable(OncRpcInFlightReqDef(), NUM_SESSIONS) { v =>
      v.active init False
    }
    requestDb.update.setIdle()

    val newRequestEvent = Flow(OncRpcInFlightReqDef())

    val (rxExistQ, rxExistR, _) = requestDb.makePort(OncRpcInFlightReqDef(), OncRpcInFlightReqDef(),
      "rxLookupExisting") { (v, q, _) =>
      v.xid === q.xid &&
        v.remoteAddr === q.remoteAddr &&
        v.remotePort === q.remotePort &&
        v.localPort === q.localPort &&
        v.active
    }

    val reqTblFull = Counter(REG_WIDTH bits)
    val (rxFreeQ, rxFreeR, _) = requestDb.makePort(NoData(), NoData(), "rxLookupFree") { (v, _, _) =>
      !v.active
    }
    rxFreeQ.valid := True
    rxFreeR.ready := True

    rxExistQ.translateFrom(newRequestEvent.toStream) { case (q, e) =>
      q.query := e
      q.userData := e
    }
    rxExistR.ready := True
    when (rxExistR.valid) {
      requestDb.update.valid := True
      requestDb.update.value := rxExistR.userData
      when (rxExistR.matched) {
        requestDb.update.idx := rxExistR.idx
      } otherwise {
        requestDb.update.idx := rxFreeR.idx
        when (!rxFreeR.matched) {
          reqTblFull.increment()
        }
      }
    }

    awaitBuild()

    val minLen = OncRpcReplyHeader().getBitsWidth / 8
    val maxLen = minLen + ONCRPC_INLINE_BYTES
    val decoder = AxiStreamExtractHeader(macIf.axisConfig, maxLen)(minLen)
    val outputAck = Bool()
    decoder.io.outputAck := outputAck

    val currentUdpHeader = udpHeader.toFlowFire.toReg()
    udpHeader.ready.setAsReg().init(True)
      .clearWhen(udpHeader.fire)
      .setWhen(decoder.io.header.fire)

    udpPayload >> decoder.io.input

    val payload = Axi4Stream(macIf.axisConfig)
    val metadata = Stream(OncRpcReplyRxMeta())

    produceFinal(metadata, payload, outputAck)
    produceDone()

    case class ReplyQuery() extends Bundle {
      val xid = Bits(32 bits)
      val remoteAddr = Bits(32 bits)
      val remotePort = Bits(16 bits)
      val localPort = Bits(16 bits)
    }

    val (dbLookup, dbResult, _) = requestDb.makePort(ReplyQuery(), OncRpcReplyLookupUserData(),
      singleMatch = true) { (v, q, _) =>
      v.xid === q.xid &&
        v.remoteAddr === q.remoteAddr &&
        v.remotePort === q.remotePort &&
        v.localPort === q.localPort &&
        v.active
    }

    val hdrParsed = OncRpcReplyHeader()
    dbLookup.translateFrom(decoder.io.header) { case (lk, hdr) =>
      hdrParsed.assignFromBits(hdr(minLen * 8 - 1 downto 0))
      lk.query.xid := hdrParsed.xid
      lk.query.remoteAddr := currentUdpHeader.ipMeta.hdr.saddr
      lk.query.remotePort := currentUdpHeader.hdr.sport
      lk.query.localPort := currentUdpHeader.hdr.dport

      lk.userData.hdr := hdrParsed
      lk.userData.packetId := currentUdpHeader.packetId
      lk.userData.data.assignFromBits(hdr(maxLen * 8 - 1 downto minLen * 8))
      lk.userData.udpPayloadSize := currentUdpHeader.getPayloadSize
      lk.userData.saddr := currentUdpHeader.ipMeta.hdr.saddr
      lk.userData.sport := currentUdpHeader.hdr.sport
      lk.userData.dport := currentUdpHeader.hdr.dport
    }

    when (decoder.io.header.fire) {
      assert(hdrParsed.msgType === EndiannessSwap(B("32'x00000001")), "msg_type must be 1 for REPLY")
      assert(hdrParsed.replyStat === 0, "only MSG_ACCEPTED replies are supported")
      assert(hdrParsed.acceptStat === 0, "only SUCCESS replies are supported")
      assert(currentUdpHeader.nextProto === UdpNextProto.oncRpcReply, "no valid UDP reply header stored")
    }

    val lookupMiss = Counter(REG_WIDTH bits)
    val dropped = Counter(REG_WIDTH bits)
    val drop = !dbResult.matched

    val pldFilter = AxiStreamFilter(macIf.axisConfig)
    pldFilter.io.input << decoder.io.output
    pldFilter.io.output >> payload
    pldFilter.io.action.valid := dbResult.fire && dbResult.userData.udpPayloadSize > maxLen
    pldFilter.io.action.payload := drop ? FilterAction.drop | FilterAction.pass

    metadata.translateFrom(dbResult.throwWhen(drop)) { case (md, lr) =>
      md.packetId := lr.userData.packetId
      md.rpcId := 0
      md.pid := lr.value.pid
      md.cookie := lr.value.cookie
      md.xid := lr.userData.hdr.xid
      md.saddr := lr.userData.saddr
      md.sport := lr.userData.sport
      md.dport := lr.userData.dport
      md.data := lr.userData.data
      md.udpPayloadSize := lr.userData.udpPayloadSize
    }

    when (dbResult.fire) {
      when (dbResult.matched) {
        requestDb.update.valid := True
        requestDb.update.idx := dbResult.idx
        requestDb.update.value.xid := dbResult.value.xid
        requestDb.update.value.remoteAddr := dbResult.value.remoteAddr
        requestDb.update.value.remotePort := dbResult.value.remotePort
        requestDb.update.value.localPort := dbResult.value.localPort
        requestDb.update.value.pid := dbResult.value.pid
        requestDb.update.value.cookie := dbResult.value.cookie
        requestDb.update.value.active := False
      } otherwise {
        lookupMiss.increment()
        when (dbResult.userData.udpPayloadSize > maxLen) {
          dropped.increment()
        }
      }
    }
  }
}
