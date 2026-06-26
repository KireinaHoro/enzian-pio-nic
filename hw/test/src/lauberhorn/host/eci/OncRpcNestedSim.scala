package lauberhorn.host.eci

import jsteward.blocks.misc.sim.{IntRicherEndianAware, _}
import lauberhorn.Global._
import lauberhorn.NicEngine
import lauberhorn.sim._
import org.pcap4j.packet.namednumber.{EtherType, IpNumber, IpVersion, UdpPort}
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, IpV4Rfc1349Tos, UdpPacket}
import org.pcap4j.util.MacAddress
import spinal.core.sim._
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _}
import spinal.lib.BytesRicher

import java.net.{Inet4Address, InetAddress}
import scala.language.postfixOps

class OncRpcNestedSim extends NicSim with OncRpcSuiteFactory {
  private def u16BytesBE(v: Int): List[Byte] = List(((v >> 8) & 0xff).toByte, (v & 0xff).toByte)
  private def u16be(v: Int): BigInt = u16BytesBE(v).bytesToBigInt
  private def u32be(v: Int): BigInt = v.toBytesBE.bytesToBigInt

  private def expectedOncRpcCallHeader(xid: Int, prog: Int, progVer: Int, proc: Int): List[Byte] =
    xid.toBytesBE ++
      0.toBytesBE ++
      2.toBytesBE ++
      prog.toBytesBE ++
      progVer.toBytesBE ++
      proc.toBytesBE ++
      List.fill(16)(0.toByte)

  private def checkNestedCallTxPacket(data: List[Byte],
                                      expectedDstIp: Inet4Address,
                                      expectedDstMac: MacAddress,
                                      expectedSport: Int,
                                      expectedDport: Int,
                                      xid: Int,
                                      prog: Int,
                                      progVer: Int,
                                      proc: Int,
                                      expectedPayload: List[Byte]): Unit = {
    val parsed = EthernetPacket.newPacket(data.toArray, 0, data.length)
    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)

    assert(parsed.getHeader.getDstAddr == expectedDstMac, "nested call has wrong destination MAC address")
    assert(parsed.getHeader.getSrcAddr == serverMac, "nested call has wrong source MAC address")
    assert(parsed.get(classOf[IpV4Packet]).getHeader.getDstAddr == expectedDstIp, "nested call has wrong destination IP address")
    assert(parsed.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "nested call has wrong source IP address")
    assert(parsed.get(classOf[UdpPacket]).getHeader.getSrcPort.valueAsInt == expectedSport, "nested call has wrong source port")
    assert(parsed.get(classOf[UdpPacket]).getHeader.getDstPort.valueAsInt == expectedDport, "nested call has wrong destination port")

    val udpPayload = parsed.get(classOf[UdpPacket]).getPayload.getRawData.toList
    val (rpcHdr, rpcPayload) = udpPayload.splitAt(40)
    assert(rpcHdr == expectedOncRpcCallHeader(xid, prog, progVer, proc), "nested ONC-RPC call header mismatch")
    check(expectedPayload, rpcPayload)
  }

  private def rpcReplyPacket(srcIp: Inet4Address,
                             dstIp: Inet4Address,
                             srcMac: MacAddress,
                             dstMac: MacAddress,
                             sport: Int,
                             dport: Int,
                             xid: Int,
                             payload: List[Byte]): EthernetPacket = {
    val udpBuilder = (new UdpPacket.Builder)
      .srcPort(UdpPort.getInstance(sport.toShort))
      .dstPort(UdpPort.getInstance(dport.toShort))
      .srcAddr(srcIp)
      .dstAddr(dstIp)
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(rawPayloadBuilder(
        (xid.toBytesBE ++
          1.toBytesBE ++
          List.fill(16)(0.toByte) ++
          payload).toArray))

    val ipBuilder = (new IpV4Packet.Builder)
      .version(IpVersion.IPV4)
      .protocol(IpNumber.UDP)
      .tos(IpV4Rfc1349Tos.newInstance(0))
      .ttl(64.toByte)
      .srcAddr(srcIp)
      .dstAddr(dstIp)
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(udpBuilder)

    (new EthernetPacket.Builder)
      .srcAddr(srcMac)
      .dstAddr(dstMac)
      .`type`(EtherType.IPV4)
      .paddingAtBuild(true)
      .payloadBuilder(ipBuilder)
      .build()
  }

  private def checkNestedReplyRx(desc: EciHostCtrlInfoSim,
                                 pldDesc: RxPayloadDesc,
                                 expectedPid: Int,
                                 expectedCookie: BigInt,
                                 expectedXid: Int,
                                 expectedPayload: List[Byte],
                                 dcsMaster: jsteward.blocks.eci.sim.DcsAppMaster)
                                (implicit dut: NicEngine): Unit = {
    assert(desc.isInstanceOf[OncRpcReplyRxPacketDescSim], s"unexpected descriptor type received: $desc")
    val info = desc.asInstanceOf[OncRpcReplyRxPacketDescSim]
    assert(info.pid == expectedPid, s"pid mismatch: got ${info.pid}, expected $expectedPid")
    assert(info.cookie == expectedCookie, f"cookie mismatch: got ${info.cookie}%x, expected $expectedCookie%x")
    assert(Integer.reverseBytes(info.xid.toInt) == expectedXid,
      f"xid mismatch: got ${Integer.reverseBytes(info.xid.toInt)}%x, expected $expectedXid%x")

    val inlineLen = ONCRPC_INLINE_BYTES.get
    val overflowLen = Math.max(expectedPayload.length - inlineLen, 0)
    assert(desc.len == overflowLen, s"reply overflow length mismatch: got ${desc.len}, expected $overflowLen")

    val inlined = spinal.core.sim.SimBigIntPimper(info.args).toBytes().toList
    check(expectedPayload.take(inlined.length), inlined)
    check(expectedPayload.drop(inlineLen), readPayload(dcsMaster, pldDesc, desc.len))
  }

  testWithDB("nested-rpc-call-tx-descriptor")(Tx) { implicit dut =>
    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(10000)
    val trace = traceConsumer
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(1) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("nested-rpc-call-tx-descriptor")).head

    val (initialPacket, initialPayload, initialXid) = getPacket()
    axisMaster.send(initialPacket.getRawData.toList)

    val cs = workerCore(0)
    cs.waitUser()
    val tid = cs.currThread.get.tid

    val (initialDesc, initialPldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
    val initialInfo = initialDesc.asInstanceOf[OncRpcCallRxPacketDescSim]
    assert(Integer.reverseBytes(initialInfo.xid.toInt) == initialXid)
    checkOncRpcCall(initialDesc, initialDesc.len, funcPtr, initialPayload,
      readPayload(dcsMaster, initialPldDesc, initialDesc.len))
    exitCriticalSection(dcsMaster, tid)

    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)
    val remoteIp = InetAddress.getByAddress(Array[Byte](10, 23, 42, 9)).asInstanceOf[Inet4Address]
    val remoteMac = MacAddress.getByName("02:00:00:00:2a:09")
    val sport = 34001
    val dport = 34002
    val xid = simRandom.nextInt()
    val cookie = BigInt("55667788", 16)
    val prog = simRandom.nextInt()
    val progVer = simRandom.nextInt()
    val procNum = simRandom.nextInt()

    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_port"), sport.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_nextProto"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_idx"), 1.toBytesLE)

    val callPayload = simRandom.nextBytes(ONCRPC_NESTED_CALL_INLINE_BYTES.get + 32).toList
    val (callInline, callTail) = callPayload.splitAt(ONCRPC_NESTED_CALL_INLINE_BYTES.get)
    val txDesc = TxOncRpcCallSim(
      len = callPayload.length,
      pid = pid,
      cookie = cookie,
      xid = u32be(xid),
      daddr = remoteIp.getAddress.toList.bytesToBigInt,
      sport = u16be(sport),
      dport = u16be(dport),
      progNum = u32be(prog),
      progVer = u32be(progVer),
      proc = u32be(procNum),
      args = callInline.bytesToBigInt)
    assert(txDesc.toTxDesc.slice(14, 18).toArray.sameElements(remoteIp.getAddress),
      s"nested call descriptor daddr bytes are ${txDesc.toTxDesc.slice(14, 18).map(b => f"$b%02x").mkString(":")}")

    var reqServed = false
    setBypassCore(() => {
      val (info, pldDesc) = tryReadPacketDesc(dcsMaster, tid = -1, maxTries = 1).result.get
      val miss = info.asInstanceOf[TxNeighborMissSim]
      val addr = InetAddress.getByAddress(miss.ipAddr.toBytesLE.toArray).asInstanceOf[Inet4Address]
      assert(addr == remoteIp, s"nested call requested neighbor resolution for wrong IP: $addr")
      val expectedIpPayload =
        u16BytesBE(sport) ++
          u16BytesBE(dport) ++
          u16BytesBE(8 + 40 + callPayload.length) ++
          u16BytesBE(0) ++
          expectedOncRpcCallHeader(xid, prog, progVer, procNum) ++
          callPayload
      assert(readPayload(dcsMaster, pldDesc, miss.len) == expectedIpPayload,
        "nested call neighbor miss did not carry raw IP payload")

      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), remoteIp.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), remoteMac.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), miss.neighTblIdx.toBytesLE)
      reqServed = true
    })

    txSendSingle(dcsMaster, txDesc, callTail, tid)
    waitUntil(reqServed)

    var callReceived = false
    fork {
      val data = axisSlave.recv()
      checkNestedCallTxPacket(data, remoteIp, remoteMac, sport, dport, xid, prog, progVer, procNum, callPayload)
      callReceived = true
    }

    val traceStart = trace.cursor
    txSendSingle(dcsMaster, txDesc, callTail, tid)
    assert(trace.first("TxHostReqAccepted", Map(
      "HostReqTy" -> BigInt(5),
      "PacketDescTy" -> BigInt(5),
      "LogicalLength" -> BigInt(callPayload.length),
      "DmaLength" -> BigInt(callTail.length),
    ), traceStart).nonEmpty, s"missing nested call TX trace; saw ${trace.dump(traceStart)}")
    assert(trace.first("TxAfterDmaRead", since = traceStart).nonEmpty,
      s"nested call TX tail did not complete DMA; saw ${trace.dump(traceStart)}")
    waitUntil(callReceived)
  }

  testWithDB("nested-rpc-reply-rx")(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(10000)

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(1) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("nested-rpc-reply-rx")).head

    val (initialPacket, initialPayload, initialXid) = getPacket()
    axisMaster.send(initialPacket.getRawData.toList)

    val cs = workerCore(0)
    cs.waitUser()
    val tid = cs.currThread.get.tid

    val (initialDesc, initialPldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
    val initialInfo = initialDesc.asInstanceOf[OncRpcCallRxPacketDescSim]
    assert(Integer.reverseBytes(initialInfo.xid.toInt) == initialXid)
    checkOncRpcCall(initialDesc, initialDesc.len, funcPtr, initialPayload,
      readPayload(dcsMaster, initialPldDesc, initialDesc.len))
    exitCriticalSection(dcsMaster, tid)

    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)
    val remoteIp = InetAddress.getByAddress(Array[Byte](10, 23, 42, 9)).asInstanceOf[Inet4Address]
    val remoteMac = MacAddress.getByName("02:00:00:00:2a:09")
    val sport = 34001
    val dport = 34002
    val xid = simRandom.nextInt()
    val cookie = BigInt("55667788", 16)

    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_port"), sport.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_nextProto"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_idx"), 1.toBytesLE)

    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_xid"), xid.toBytesBE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_remoteAddr"), remoteIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_remotePort"), u16BytesBE(dport))
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_localPort"), u16BytesBE(sport))
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_pid"), pid.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_cookie"), 0x55667788.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_active"), 1.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_idx"), 0.toBytesLE)

    sleepCycles(100)
    val replyPayload = simRandom.nextBytes(ONCRPC_INLINE_BYTES.get + 32).toList
    val replyPacket = rpcReplyPacket(
      srcIp = remoteIp,
      dstIp = serverIp,
      srcMac = remoteMac,
      dstMac = serverMac,
      sport = dport,
      dport = sport,
      xid = xid,
      payload = replyPayload)

    axisMaster.send(replyPacket.getRawData.toList)
    cs.waitUser()
    val (replyDesc, replyPldDesc) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100, exitCS = false).result.get
    checkNestedReplyRx(replyDesc, replyPldDesc, pid, cookie, xid, replyPayload, dcsMaster)
    exitCriticalSection(dcsMaster, tid)
  }
}
