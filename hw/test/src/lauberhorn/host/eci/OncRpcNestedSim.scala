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
                             payload: List[Byte], status: Int = 0): EthernetPacket = {
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
          List.fill(12)(0.toByte) ++ status.toBytesBE ++
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
                                 dcsMaster: jsteward.blocks.eci.sim.DcsAppMaster, expectedStatus: Int = 0)
                                (implicit dut: NicEngine): Unit = {
    assert(desc.isInstanceOf[OncRpcReplyRxPacketDescSim], s"unexpected descriptor type received: $desc")
    val info = desc.asInstanceOf[OncRpcReplyRxPacketDescSim]
    assert(info.status == expectedStatus, s"reply status ${info.status}, expected $expectedStatus")
    assert(info.pid == expectedPid, s"pid mismatch: got ${info.pid}, expected $expectedPid")
    assert(info.cookie == expectedCookie, f"cookie mismatch: got ${info.cookie}%x, expected $expectedCookie%x")
    assert(info.xid.toInt == expectedXid,
      f"xid mismatch: got ${info.xid.toInt}%x, expected $expectedXid%x")

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

    val (initialDesc, initialPldDesc) = tryReadPacketDesc(dcsMaster, tid).result.get
    val initialInfo = initialDesc.asInstanceOf[OncRpcCallRxPacketDescSim]
    assert(requestXid(csrMaster, initialInfo.requestToken) == initialXid)
    checkOncRpcCall(initialDesc, initialDesc.len, funcPtr, initialPayload,
      readPayload(dcsMaster, initialPldDesc, initialDesc.len))

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
      xid = BigInt(xid & 0xffffffffL),
      daddr = BigInt(1, remoteIp.getAddress),
      sport = sport,
      dport = dport,
      progNum = BigInt(prog & 0xffffffffL),
      progVer = BigInt(progVer & 0xffffffffL),
      proc = BigInt(procNum & 0xffffffffL),
      args = callInline.bytesToBigInt)
    assert(txDesc.toTxDesc.slice(16, 20).toArray.sameElements(remoteIp.getAddress.reverse),
      s"nested call descriptor daddr bytes are ${txDesc.toTxDesc.slice(16, 20).map(b => f"$b%02x").mkString(":")}")

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

    val (initialDesc, initialPldDesc) = tryReadPacketDesc(dcsMaster, tid).result.get
    val initialInfo = initialDesc.asInstanceOf[OncRpcCallRxPacketDescSim]
    assert(requestXid(csrMaster, initialInfo.requestToken) == initialXid)
    checkOncRpcCall(initialDesc, initialDesc.len, funcPtr, initialPayload,
      readPayload(dcsMaster, initialPldDesc, initialDesc.len))

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

    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_xid"), xid.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_remoteAddr"), remoteIp.getAddress.reverse.toList)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_remotePort"), dport.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_localPort"), sport.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_pid"), pid.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_cookie"), 0x55667788.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_active"), 1.toBytesLE)
    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_idx"), 0.toBytesLE)

    csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("stat", "req_readback_idx"), 0.toBytesLE)
    sleepCycles(10)
    for ((field, expected) <- Seq(
      "xid" -> BigInt(xid & 0xffffffffL),
      "remoteAddr" -> BigInt(1, remoteIp.getAddress),
      "remotePort" -> BigInt(dport), "localPort" -> BigInt(sport),
      "pid" -> BigInt(pid), "cookie" -> cookie, "active" -> BigInt(1))) {
      val actual = csrMaster.read(ALLOC.readBack("OncRpcReplyDecoder")("stat", s"req_readback_$field"), 8).bytesToBigInt
      assert(actual == expected, s"in-flight CSR $field: $actual != $expected")
    }

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
    val (replyDesc, replyPldDesc) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    checkNestedReplyRx(replyDesc, replyPldDesc, pid, cookie, xid, replyPayload, dcsMaster)

    // The entry has retired. Late/duplicate replies must drain completely and
    // allow the valid completions below to progress, including empty bodies.
    for (length <- Seq(0, 4, 160)) {
      axisMaster.send(rpcReplyPacket(remoteIp, serverIp, remoteMac, serverMac,
        dport, sport, xid, List.fill(length)(42.toByte)).getRawData.toList)
    }
    // Accepted errors have no success-codec body. Unknown 32-bit statuses
    // must never truncate to success when crossing the 8-bit host field.
    for ((status, offset) <- Seq((5, 1), (256, 2))) {
      csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_xid"), (xid + offset).toBytesLE)
      csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_active"), 1.toBytesLE)
      csrMaster.write(ALLOC.readBack("OncRpcReplyDecoder")("ctrl", "req_idx"), 0.toBytesLE)
      axisMaster.send(rpcReplyPacket(remoteIp, serverIp, remoteMac, serverMac,
        dport, sport, xid + offset, Nil, status).getRawData.toList)
      cs.waitUser()
      val (errorDesc, errorPld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      checkNestedReplyRx(errorDesc, errorPld, pid, cookie, xid + offset, Nil, dcsMaster, 5)

    }
    assert(csrMaster.read(ALLOC.readBack("OncRpcReplyDecoder")("stat", "lookupMiss"), 8).bytesToBigInt == 3)
    val regs = ALLOC.readBack("OncRpcReplyDecoder")
    // Explicit retirement must be acknowledged before resources are reusable.
    csrMaster.write(regs("ctrl", "req_xid"), (xid + 3).toBytesLE)
    csrMaster.write(regs("ctrl", "req_active"), 1.toBytesLE)
    csrMaster.write(regs("ctrl", "req_idx"), 0.toBytesLE)
    csrMaster.write(regs("ctrl", "retire_pid"), pid.toBytesLE)
    csrMaster.write(regs("ctrl", "retire_cookie"), 0x55667788.toBytesLE)
    csrMaster.write(regs("ctrl", "retire"), 1.toBytesLE)
    while(csrMaster.read(regs("stat", "retire_busy"), 8).bytesToBigInt != 0) sleepCycles(5)
    csrMaster.write(regs("stat", "req_readback_idx"), 0.toBytesLE)
    assert(csrMaster.read(regs("stat", "req_readback_active"), 8).bytesToBigInt == 0)
    axisMaster.send(rpcReplyPacket(remoteIp, serverIp, remoteMac, serverMac,
      dport, sport, xid + 3, List.fill(160)(1.toByte)).getRawData.toList)
    // Owner-wide retirement leaves unrelated process state intact.
    csrMaster.write(regs("ctrl", "req_xid"), (xid + 5).toBytesLE)
    csrMaster.write(regs("ctrl", "req_pid"), (pid ^ 1).toBytesLE)
    csrMaster.write(regs("ctrl", "req_idx"), 1.toBytesLE)
    csrMaster.write(regs("ctrl", "req_pid"), pid.toBytesLE)
    csrMaster.write(regs("ctrl", "req_xid"), (xid + 4).toBytesLE)
    csrMaster.write(regs("ctrl", "req_idx"), 0.toBytesLE)
    csrMaster.write(regs("ctrl", "retire"), 2.toBytesLE)
    while(csrMaster.read(regs("stat", "retire_busy"), 8).bytesToBigInt != 0) sleepCycles(5)
    assert(csrMaster.read(regs("stat", "req_readback_active"), 8).bytesToBigInt == 0)
    csrMaster.write(regs("stat", "req_readback_idx"), 1.toBytesLE)
    assert(csrMaster.read(regs("stat", "req_readback_active"), 8).bytesToBigInt == 1)

  }
  testWithDB("nested-rpc-local-roundtrip")(Tx) { implicit dut =>
    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(30000)
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val service = RpcSrvDef(23456, 0x12345678, 0x23456789, 0x3456789a, 0x123456789abcdefL)
    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(1) -> Seq(service))).head
    val (initialPacket, _, _) = getPacket()
    axisMaster.send(initialPacket.getRawData.toList)
    val cs = workerCore(0)
    cs.waitUser()
    val tid = cs.currThread.get.tid
    val (initialDesc, initialPld) = tryReadPacketDesc(dcsMaster, tid).result.get
    readPayload(dcsMaster, initialPld, initialDesc.len)

    val sport = 54321
    val (localIp, _, _) = enzianIpMacAddrs(1)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_port"), sport.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_nextProto"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("UdpDecoder")("ctrl", "listen_idx"), 1.toBytesLE)
    var physicalTx = false
    fork { axisSlave.recv(); physicalTx = true }
    for ((callLen, replyLen, status) <- Seq((4, 8, 0), (80, 96, 0), (24, 48, 0), (4, 0, 5), (4, 0, 3))) {
      val xid = 0x12345600 + callLen + status * 100
      val cookie = BigInt(0x76543200L + replyLen)
      val call = simRandom.nextBytes(callLen).toList
      val reply = simRandom.nextBytes(replyLen).toList
      val tx = TxOncRpcCallSim(callLen, pid, cookie, BigInt(xid),
        BigInt(1, localIp.getAddress), sport, service.dport,
        BigInt(service.prog), BigInt(service.progVer), BigInt(service.procNum),
        call.take(ONCRPC_NESTED_CALL_INLINE_BYTES.get).bytesToBigInt)
      txSendSingle(dcsMaster, tx, call.drop(ONCRPC_NESTED_CALL_INLINE_BYTES.get), tid)
      cs.waitUser()
      val (desc, pld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      val token = desc.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
      assert(requestXid(csrMaster, token) == xid)
      checkOncRpcCall(desc, desc.len, funcPtr, call, readPayload(dcsMaster, pld, desc.len))

      // Reply immediately, using the session installed by normal call decoding.
      txSendSingle(dcsMaster, TxOncRpcReplySim(replyLen, token,
        (if (reply.isEmpty) BigInt(0) else reply.take(ONCRPC_INLINE_BYTES.get).bytesToBigInt), status), reply.drop(ONCRPC_INLINE_BYTES.get), tid)
      cs.waitUser()
      val (done, donePld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      checkNestedReplyRx(done, donePld, pid, cookie, xid, reply, dcsMaster, status)

    }
    // Queue independent calls before servicing either, mix physical ingress,
    // and return the local replies in reverse order under receiver backpressure.
    val outstanding = (0 until 2).map { index =>
      val xid = 0x45678910 + index
      val cookie = BigInt(0x56789a20L + index)
      val call = simRandom.nextBytes(64 + index).toList
      txSendSingle(dcsMaster, TxOncRpcCallSim(call.length, pid, cookie, BigInt(xid),
        BigInt(1, localIp.getAddress), sport, service.dport,
        BigInt(service.prog), BigInt(service.progVer), BigInt(service.procNum),
        call.take(ONCRPC_NESTED_CALL_INLINE_BYTES.get).bytesToBigInt),
        call.drop(ONCRPC_NESTED_CALL_INLINE_BYTES.get), tid)
      (xid, cookie, call)
    }
    val (mixedPacket, mixedPayload, mixedXid) = getPacket()
    axisMaster.send(mixedPacket.getRawData.toList)
    sleepCycles(200)
    val expectedCalls = outstanding.map(x => x._1 -> x._3).toMap + (mixedXid -> mixedPayload)
    val seen = scala.collection.mutable.Map[Int, BigInt]()
    for (_ <- 0 until 3) {
      cs.waitUser()
      val (desc, pld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      val token = desc.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
      val xid = requestXid(csrMaster, token)
      assert(!seen.contains(xid))
      seen(xid) = token
      checkOncRpcCall(desc, desc.len, funcPtr, expectedCalls(xid), readPayload(dcsMaster, pld, desc.len))

    }
    for ((xid, cookie, reply) <- outstanding.reverse) {
      txSendSingle(dcsMaster, TxOncRpcReplySim(reply.length, seen(xid),
        reply.take(ONCRPC_INLINE_BYTES.get).bytesToBigInt), reply.drop(ONCRPC_INLINE_BYTES.get), tid)
      sleepCycles(100)
      cs.waitUser()
      val (desc, pld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      checkNestedReplyRx(desc, pld, pid, cookie, xid, reply, dcsMaster)

    }
    // Exhaust hardware completion routing, independently of runtime fibers.
    // Rejection must return through the TX doorbell without injecting a packet.
    val reqCtrl = ALLOC.readBack("OncRpcReplyDecoder")
    csrMaster.write(reqCtrl("ctrl", "req_remoteAddr"), localIp.getAddress.reverse.toList)
    csrMaster.write(reqCtrl("ctrl", "req_remotePort"), service.dport.toBytesLE)
    csrMaster.write(reqCtrl("ctrl", "req_localPort"), sport.toBytesLE)
    csrMaster.write(reqCtrl("ctrl", "req_pid"), pid.toBytesLE)
    csrMaster.write(reqCtrl("ctrl", "req_cookie"), 123.toBytesLE)
    csrMaster.write(reqCtrl("ctrl", "req_active"), 1.toBytesLE)
    for (idx <- 0 until NUM_SESSIONS.get) {
      csrMaster.write(reqCtrl("ctrl", "req_xid"), (0x1000 + idx).toBytesLE)
      csrMaster.write(reqCtrl("ctrl", "req_idx"), idx.toBytesLE)
    }
    val retryXid = 0x71928364
    val retryCookie = BigInt(0x10293847)
    val retryCall = TxOncRpcCallSim(0, pid, retryCookie, BigInt(retryXid),
      BigInt(1, localIp.getAddress), sport, service.dport,
      BigInt(service.prog), BigInt(service.progVer), BigInt(service.procNum), BigInt(0))
    txSendSingle(dcsMaster, retryCall, Nil, tid, expectedStatus = 2)
    // Free one reservation and prove that the same worker can submit again.
    csrMaster.write(reqCtrl("ctrl", "req_active"), 0.toBytesLE)
    csrMaster.write(reqCtrl("ctrl", "req_idx"), 0.toBytesLE)
    txSendSingle(dcsMaster, retryCall, Nil, tid)
    cs.waitUser()
    val (retryDesc, retryPld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    val retryToken = retryDesc.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
    assert(requestXid(csrMaster, retryToken) == retryXid)
    readPayload(dcsMaster, retryPld, retryDesc.len)

    txSendSingle(dcsMaster, TxOncRpcReplySim(0, retryToken, BigInt(0), 5), Nil, tid)
    cs.waitUser()
    val (retryDone, retryDonePld) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    checkNestedReplyRx(retryDone, retryDonePld, pid, retryCookie, retryXid, Nil, dcsMaster, 5)

    // Clear the synthetic outgoing reservations from the preceding test, then
    // fill all remaining incoming contexts with real local calls.
    csrMaster.write(reqCtrl("ctrl", "req_active"), 0.toBytesLE)
    for (idx <- 0 until NUM_SESSIONS.get)
      csrMaster.write(reqCtrl("ctrl", "req_idx"), idx.toBytesLE)
    val contextRegs = ALLOC.readBack("OncRpcReplyEncoder")
    val freeContexts = (0 until NUM_SESSIONS.get).count { idx =>
      csrMaster.write(contextRegs("stat", "context_read_idx"), idx.toBytesLE)
      csrMaster.read(contextRegs("stat", "context_active"), 8).bytesToBigInt == 0
    }
    assert(freeContexts > 0 && freeContexts < NUM_SESSIONS.get)
    def localCall(xid: Int, cookie: Int) = TxOncRpcCallSim(0, pid, BigInt(cookie), BigInt(xid),
      BigInt(1, localIp.getAddress), sport, service.dport,
      BigInt(service.prog), BigInt(service.progVer), BigInt(service.procNum), BigInt(0))
    val held = (0 until freeContexts).map { idx =>
      val xid = 0x65000000 + idx
      val cookie = 0x45000000 + idx
      txSendSingle(dcsMaster, localCall(xid, cookie), Nil, tid)
      cs.waitUser()
      val (call, tail) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
      val token = call.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
      assert(requestXid(csrMaster, token) == xid)
      readPayload(dcsMaster, tail, call.len)

      (xid, cookie, token)
    }
    // Full incoming capacity produces a local error completion, not a host call.
    txSendSingle(dcsMaster, localCall(0x66000000, 0x46000000), Nil, tid)
    cs.waitUser()
    val (rejected, rejectedTail) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    checkNestedReplyRx(rejected, rejectedTail, pid, BigInt(0x46000000), 0x66000000, Nil, dcsMaster, 5)

    val (heldXid, heldCookie, heldToken) = held.head
    txSendSingle(dcsMaster, TxOncRpcReplySim(0, heldToken, BigInt(0)), Nil, tid)
    cs.waitUser()
    val (released, releasedTail) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    checkNestedReplyRx(released, releasedTail, pid, BigInt(heldCookie), heldXid, Nil, dcsMaster)

    txSendSingle(dcsMaster, localCall(0x67000000, 0x47000000), Nil, tid)
    cs.waitUser()
    val (afterFull, afterFullTail) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    val afterFullToken = afterFull.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
    assert(afterFullToken != heldToken)
    assert(requestXid(csrMaster, afterFullToken) == 0x67000000)
    readPayload(dcsMaster, afterFullTail, afterFull.len)

    txSendSingle(dcsMaster, TxOncRpcReplySim(0, afterFullToken, BigInt(0)), Nil, tid)
    cs.waitUser()
    val (afterReply, afterReplyTail) = tryReadPacketDesc(dcsMaster, tid, maxTries = 100).result.get
    checkNestedReplyRx(afterReply, afterReplyTail, pid, BigInt(0x47000000), 0x67000000, Nil, dcsMaster)

    sleepCycles(100)
    assert(!physicalTx, "local nested call or reply escaped to CMAC")
  }

}
