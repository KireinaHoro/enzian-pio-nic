package lauberhorn.host.eci

import jsteward.blocks.misc.sim.{IntRicherEndianAware, isSorted}
import lauberhorn.Global._
import lauberhorn.NicEngine
import lauberhorn.sim._
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, UdpPacket}
import org.scalatest.tagobjects.Slow
import spinal.core.sim._
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _}
import spinal.lib._

import scala.collection.mutable
import scala.language.postfixOps

class OncRpcSim extends NicSim with OncRpcSuiteFactory {
  private def fixedPeer(packet: EthernetPacket): EthernetPacket = {
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)
    val udp = packet.get(classOf[UdpPacket]).getBuilder
      .srcAddr(clientIp).dstAddr(serverIp).correctChecksumAtBuild(true)
    val ip = packet.get(classOf[IpV4Packet]).getBuilder
      .srcAddr(clientIp).dstAddr(serverIp).correctChecksumAtBuild(true).payloadBuilder(udp)
    packet.getBuilder.srcAddr(clientMac).dstAddr(serverMac).payloadBuilder(ip).build()
  }

  private def expectedOncRpcReplyHeader(xid: Int): List[Byte] =
    xid.toBytesBE ++
      1.toBytesBE ++              // msg_type == REPLY
      List.fill(16)(0.toByte)     // reply_stat, verifier flavor/length, accept_stat

  private def checkOncRpcReplyPacket(data: List[Byte],
                                     request: EthernetPacket,
                                     xid: Int,
                                     expectedPayload: List[Byte]): Unit = {
    val parsed = EthernetPacket.newPacket(data.toArray, 0, data.length)
    val clientIp = request.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac = request.getHeader.getSrcAddr
    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)

    assert(parsed.getHeader.getDstAddr == clientMac, "received packet has wrong destination MAC address")
    assert(parsed.getHeader.getSrcAddr == serverMac, "received packet has wrong source MAC address")
    assert(parsed.get(classOf[IpV4Packet]).getHeader.getDstAddr == clientIp, "received packet has wrong destination IP address")
    assert(parsed.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "received packet has wrong source IP address")
    assert(parsed.get(classOf[UdpPacket]).getHeader.getSrcPort == request.get(classOf[UdpPacket]).getHeader.getDstPort, "received packet has wrong source port")
    assert(parsed.get(classOf[UdpPacket]).getHeader.getDstPort == request.get(classOf[UdpPacket]).getHeader.getSrcPort, "received packet has wrong destination port")

    val udpPayload = parsed.get(classOf[UdpPacket]).getPayload.getRawData.toList
    val (rpcHdr, rpcPayload) = udpPayload.splitAt(24)
    assert(rpcHdr == expectedOncRpcReplyHeader(xid), "ONC-RPC reply header mismatch")
    check(expectedPayload, rpcPayload)
  }

  private def runSingleOncRpcTx(replyData: List[Byte], testName: String)(implicit dut: NicEngine) = {
    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(10000)
    val trace = traceConsumer

    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)

    val (funcPtr, getPacket, _) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(1) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some(testName)).head

    val (packet, pld, xid) = getPacket()
    val clientIp = packet.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac = packet.getHeader.getSrcAddr

    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)

    var replyReceived = false
    fork {
      val data = axisSlave.recv()
      checkOncRpcReplyPacket(data, packet, xid, replyData)
      replyReceived = true
    }

    axisMaster.send(packet.getRawData.toList)

    val cs = workerCore(0)
    cs.waitUser()
    val tid = cs.currThread.get.tid

    val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
    val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
    val receivedXid = requestXid(csrMaster, info.requestToken)
    assert(receivedXid == xid, f"xid mismatch: expected $xid%#x, got $receivedXid%x")
    checkOncRpcCall(desc, desc.len, funcPtr, pld, readPayload(dcsMaster, pldDesc, desc.len))
    exitCriticalSection(dcsMaster, tid)

    val traceStart = trace.cursor
    val (respInlineData, respTail) = replyData.splitAt(ONCRPC_INLINE_BYTES)
    val txDesc = TxOncRpcReplySim(replyData.length, info.requestToken, respInlineData.bytesToBigInt)
    txSendSingle(dcsMaster, txDesc, respTail, tid)

    waitUntil(replyReceived)

    (trace, traceStart)
  }

  testWithDB("reply-context-admission-retirement-and-client-collisions")(Rx, Tx) { implicit dut =>
    val (csr, input, output, dcs) = commonDutSetup(1000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val service = RpcSrvDef(23456, 123, 1, 2, 0x123456789L)
    val (funcPtr, _, _) = oncRpcCallPacketFactory(csr,
      Seq(mkRandomProc(1) -> Seq(service))).head
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)
    val wireXid = 0x1234abcd
    val replies = mutable.ArrayBuffer[List[Byte]]()
    fork { while (true) replies += output.recv() }
    val worker = workerCore(0)
    def packet(port: Int, payload: List[Byte]) = fixedPeer(oncRpcCallPacket(port, service.dport,
      service.prog, service.progVer, service.procNum, payload, wireXid))
    val tokens = (0 until NUM_SESSIONS.get).map { i =>
      val args = List.fill(if (i % 2 == 0) 80 else 4)(i.toByte)
      input.send(packet(30000 + i, args).getRawData.toList)
      worker.waitUser()
      val tid = worker.currThread.get.tid
      val (desc, pld) = tryReadPacketDesc(dcs, tid, exitCS = false).result.get
      val token = desc.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
      assert(requestXid(csr, token) == wireXid)
      checkOncRpcCall(desc, desc.len, funcPtr, args, readPayload(dcs, pld, desc.len))
      exitCriticalSection(dcs, tid)
      token
    }
    assert(tokens.distinct.size == NUM_SESSIONS.get)
    val tid = worker.currThread.get.tid
    // Rejections cover empty, inline and overflow bodies; none reaches software.
    for ((size, i) <- Seq(0, 4, 160).zipWithIndex) {
      input.send(packet(40000 + i, List.fill(size)(42.toByte)).getRawData.toList)
      waitUntil(replies.size == i + 1)
      val udp = EthernetPacket.newPacket(replies(i).toArray, 0, replies(i).length).get(classOf[UdpPacket])
      assert(udp.getHeader.getDstPort.valueAsInt == 40000 + i)
      assert(udp.getPayload.getRawData.toList ==
        wireXid.toBytesBE ++ 1.toBytesBE ++ List.fill(12)(0.toByte) ++ 5.toBytesBE)
    }
    assert(tryReadPacketDesc(dcs, tid, maxTries = 1).result.isEmpty,
      "rejected request reached host")
    // Retire one live request, then reuse its slot without changing another peer.
    txSendSingle(dcs, TxOncRpcReplySim(0, tokens.head, BigInt(0)), Nil, tid)
    waitUntil(replies.size == 4)
    checkOncRpcReplyPacket(replies(3), packet(30000, Nil), wireXid, Nil)
    input.send(packet(50000, List(9.toByte)).getRawData.toList)
    worker.waitUser()
    val (desc, pld) = tryReadPacketDesc(dcs, tid, exitCS = false).result.get
    val replacement = desc.asInstanceOf[OncRpcCallRxPacketDescSim].requestToken
    assert(replacement != tokens.head && (replacement & 15) == (tokens.head & 15))
    readPayload(dcs, pld, desc.len)
    exitCriticalSection(dcs, tid)
    // A stale reply with overflow must drain without consuming the replacement.
    val stale = List.fill(160)(77.toByte)
    txSendSingle(dcs, TxOncRpcReplySim(stale.length, tokens.head,
      stale.take(ONCRPC_INLINE_BYTES.get).bytesToBigInt), stale.drop(ONCRPC_INLINE_BYTES.get), tid)
    txSendSingle(dcs, TxOncRpcReplySim(0, replacement, BigInt(0)), Nil, tid)
    waitUntil(replies.size == 5)
    checkOncRpcReplyPacket(replies(4), packet(50000, Nil), wireXid, Nil)
    txSendSingle(dcs, TxOncRpcReplySim(0, tokens.last, BigInt(0)), Nil, tid)
    waitUntil(replies.size == 6)
    checkOncRpcReplyPacket(replies(5), packet(30000 + NUM_SESSIONS.get - 1, Nil), wireXid, Nil)
    val regs = ALLOC.readBack("OncRpcReplyEncoder")
    assert(csr.read(regs("stat", "sessTblFull"), 8).bytesToBigInt == 3)
    assert(csr.read(regs("stat", "dropped"), 8).bytesToBigInt == 1)
    sleepCycles(100)
    assert(replies.size == 6)
  }

  testWithDB("disabled-process-discards-free-reply-contexts")(Rx, Tx) { implicit dut =>
    val (csr, input, _, _) = commonDutSetup(100000)
    val occupancyRegs = PKT_BUF_ALLOC_SIZES.get.map { case (size, _) =>
      val aligned = ((size - 64 + 127) / 128) * 128 + 64
      ALLOC.readBack("dma")("stat", s"rxAllocOccupancy_upTo$aligned")
    }
    val initialFree = occupancyRegs.map(csr.read(_, 8).bytesToBigInt)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val service = RpcSrvDef.mkRandom
    val process = mkRandomProc(1)
    oncRpcCallPacketFactory(csr, Seq(process -> Seq(service)))
    val sched = ALLOC.readBack("sched")
    // Queue work without assigning a worker, then disable the process.
    // The queued allocations must be returned, not just forgotten.
    csr.write(sched("ctrl", "proc_maxThreads"), 0.toBytesLE)
    csr.write(sched("ctrl", "proc_idx"), 1.toBytesLE)
    for (i <- 1 to 8) {
      val packet = fixedPeer(oncRpcCallPacket(30000, service.dport, service.prog,
        service.progVer, service.procNum, List.fill(160)(i.toByte), i))
      input.send(packet.getRawData.toList)
    }
    var pushed = BigInt(0)
    while(pushed < 8) {
      sleepCycles(20)
      pushed = csr.read(sched("stat", "pushed"), 8).bytesToBigInt
    }
    csr.write(sched("ctrl", "proc_enabled"), 0.toBytesLE)
    csr.write(sched("ctrl", "proc_idx"), 1.toBytesLE)
    var reclaimed = BigInt(0)
    while(reclaimed < 8) {
      sleepCycles(20)
      reclaimed = csr.read(sched("stat", "dropped"), 8).bytesToBigInt
    }
    // Service lookup can race process deregistration. The service still admits
    // packets here, but the disabled process must never receive them.
    for (i <- 1 to NUM_SESSIONS.get * 3) {
      val payload = if (i % 2 == 0) List.fill(160)(i.toByte) else Nil
      val packet = fixedPeer(oncRpcCallPacket(30000, service.dport, service.prog,
        service.progVer, service.procNum, payload, i))
      input.send(packet.getRawData.toList)
      var dropped = BigInt(0)
      while (dropped < i + 8) {
        sleepCycles(20)
        dropped = csr.read(sched("stat", "dropped"), 8).bytesToBigInt
      }
      assert(dropped == i + 8)
    }
    val contexts = ALLOC.readBack("OncRpcReplyEncoder")
    for (i <- 0 until NUM_SESSIONS.get) {
      csr.write(contexts("stat", "context_read_idx"), i.toBytesLE)
      assert(csr.read(contexts("stat", "context_active"), 8).bytesToBigInt == 0)
    }
    assert(csr.read(contexts("stat", "sessTblFull"), 8).bytesToBigInt == 0)
    assert(csr.read(sched("stat", "pushed"), 8).bytesToBigInt == 8)
    sleepCycles(100)
    assert(occupancyRegs.map(csr.read(_, 8).bytesToBigInt) == initialFree,
      "disabled-process traffic leaked packet allocations")
  }

  testWithDB("tx-rpc-inline-only-skips-dma")(Rx, Tx) { implicit dut =>
    val replyData = simRandom.nextBytes(16).toList
    val (trace, traceStart) = runSingleOncRpcTx(replyData, "tx-rpc-inline-only-skips-dma")

    assert(trace.first("TxHostReqAccepted", Map(
      "HostReqTy" -> BigInt(4),
      "PacketDescTy" -> BigInt(4),
      "LogicalLength" -> BigInt(replyData.length),
      "DmaLength" -> BigInt(0),
    ), traceStart).nonEmpty, s"missing inline-only TxHostReqAccepted; saw ${trace.dump(traceStart)}")
    assert(trace.first("TxNoDmaRead", since = traceStart).nonEmpty,
      s"inline-only ONC-RPC reply did not skip DMA; saw ${trace.dump(traceStart)}")
    assert(trace.first("TxAfterDmaRead", since = traceStart).isEmpty,
      s"inline-only ONC-RPC reply unexpectedly issued DMA; saw ${trace.dump(traceStart)}")
    assert(trace.first("EciTxDataLciStart", since = traceStart).isEmpty,
      s"inline-only ONC-RPC reply unexpectedly invalidated packet data CLs; saw ${trace.dump(traceStart)}")
  }

  testWithDB("tx-rpc-tail-without-overflow-does-not-invalidate-overflow")(Rx, Tx) { implicit dut =>
    val replyData = simRandom.nextBytes(ONCRPC_INLINE_BYTES.get + 32).toList
    val (trace, traceStart) = runSingleOncRpcTx(replyData, "tx-rpc-tail-without-overflow-does-not-invalidate-overflow")

    assert(trace.first("TxHostReqAccepted", Map(
      "HostReqTy" -> BigInt(4),
      "PacketDescTy" -> BigInt(4),
      "LogicalLength" -> BigInt(replyData.length),
      "DmaLength" -> BigInt(32),
    ), traceStart).nonEmpty, s"missing tail TxHostReqAccepted; saw ${trace.dump(traceStart)}")
    assert(trace.first("TxAfterDmaRead", since = traceStart).nonEmpty,
      s"ONC-RPC reply tail did not complete DMA; saw ${trace.dump(traceStart)}")
    assert(trace.first("TxNoDmaRead", since = traceStart).isEmpty,
      s"ONC-RPC reply with packet-buffer tail skipped DMA; saw ${trace.dump(traceStart)}")
    assert(trace.first("EciTxDataLciStart", since = traceStart).isEmpty,
      s"ONC-RPC reply tail <= 64B unexpectedly invalidated overflow CLs; saw ${trace.dump(traceStart)}")
  }

  testWithDB("rx-allcores")(Rx, Tx) { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores,
    //   creating a thread for every worker core
    // - send 50 * numWorkerCores requests
    // - cores are preempted as requests come in, they start to read
    // - eventually all packets are received through all cores

    val totalToSend = 50 * NUM_WORKER_CORES

    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(1000)
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)
    fork { while (true) axisSlave.recv() }
    def macRxStat(name: String) = csrMaster.read(ALLOC.readBack("macIf")("stat", name), 8).bytesToBigInt
    val initialRxMacOverflow = macRxStat("rxMacOverflowCount")

    def waitMacRxCdcDrained(): Unit = {
      var ingress = macRxStat("rxMacIngressCount")
      var afterCdc = macRxStat("rxMacIngressAfterCdcCount")
      var overflow = macRxStat("rxMacOverflowCount")
      var tries = 0
      while (ingress != afterCdc && tries < 1000) {
        assert(overflow == initialRxMacOverflow,
          s"RX MAC CDC FIFO overflowed while throttling: got $overflow, expected $initialRxMacOverflow")
        sleepCycles(20)
        ingress = macRxStat("rxMacIngressCount")
        afterCdc = macRxStat("rxMacIngressAfterCdcCount")
        overflow = macRxStat("rxMacOverflowCount")
        tries += 1
      }
      assert(ingress == afterCdc,
        s"RX MAC CDC FIFO did not drain: ingress $ingress, after CDC $afterCdc, overflow $overflow")
      assert(overflow == initialRxMacOverflow,
        s"RX MAC CDC FIFO overflowed: got $overflow, expected $initialRxMacOverflow")
    }

    // test one service on one process on all cores
    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("rx-allcores")
    ).head
    val inflightPackets = mutable.Map[Int, (EthernetPacket, List[Byte])]()
    var packetsReceived = 0
    var doneSending = false
    var lastReceiveProgress = simTime()

    fork {
      // send all packets
      0 until totalToSend foreach { idx =>
        // flow control: make sure we don't have more than RX_PKTS_PER_PROC packets in flight
        while (inflightPackets.size >= scala.math.min(RX_PKTS_PER_PROC.get, NUM_SESSIONS.get / 2)) {
          println(s"Already ${RX_PKTS_PER_PROC.get} packets in flight, waiting...")
          // sleep a little bit longer to allow bursts
          sleepCycles(1000)
        }

        // The simulated CMAC input is intentionally non-backpressured, and the
        // hardware RX CDC FIFO drops whole frames when full.  This test is about
        // scheduler/ECI delivery, so keep the MAC CDC boundary lossless.
        waitMacRxCdcDrained()

        val (rawPacket, payload, xid) = getPacket()
        val packet = fixedPeer(rawPacket)
        println(f"Sending packet with XID $xid%#x")
        // record packet in map before driving the stream: the RX path can complete
        // quickly enough that workers observe the packet before send() returns.
        // FIXME: this might collide..
        assert(!inflightPackets.contains(xid), "random packet generation collision")
        inflightPackets(xid) = (packet, payload)

        val toSend = packet.getRawData.toList
        // blocking send
        axisMaster.send(toSend)
      }
      doneSending = true
    }

    0 until NUM_WORKER_CORES foreach { wcid =>
      fork {
        val cs = workerCore(wcid)

        cs.log("Wait until a user thread is scheduled")
        cs.waitUser()
        cs.log("returned to userspace")

        def tryReceiveAndCheckOne(): Unit = {
          cs.waitUser()
          val tid = cs.currThread.get.tid

          val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result match {
            case Some(ret) => ret
            case None =>
              if (packetsReceived == totalToSend) {
                cs.log("no more packets expected, exiting...")
              } else {
                cs.log("no packet available yet, retrying")
                if (doneSending && simTime() - lastReceiveProgress > 1000000000L) {
                  cs.log(s"packets still in flight: ${inflightPackets.mkString(", ")}")
                  fail(s"no receive progress after sender finished; only received $packetsReceived packets, expected $totalToSend")
                }
                sleepCycles(100)
              }
              return
          }

          val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
          cs.log(f"Received status register: $desc")

          // do not process packets too fast, or other cores will never get invoked
          randomSleep(100, 50)
          cs.log("Sleep finished, checking packet data...")

          // Debug readback checks the wire XID retained behind the token.
          val xid = requestXid(csrMaster, info.requestToken)
          cs.log(f"Received XID $xid%x")

          // find the payload that we sent
          val (pkt, pld) = inflightPackets(xid)
          cs.log(f"Expecting packet: $pkt")

          checkOncRpcCall(desc, desc.len, funcPtr, pld, readPayload(dcsMaster, pldDesc, desc.len))
          cs.log(f"Received packet #$packetsReceived (XID $xid%x)")
          packetsReceived += 1
          lastReceiveProgress = simTime()

          exitCriticalSection(dcsMaster, tid)
          txSendSingle(dcsMaster, TxOncRpcReplySim(0, info.requestToken, BigInt(0)), Nil, tid)
          inflightPackets.remove(xid)
        }

        while (packetsReceived != totalToSend) {
          // read and check packet against sent
          tryReceiveAndCheckOne()
        }
      }
    }

    waitUntil(packetsReceived == totalToSend)
  }

  testWithDB("rt-timestamped")(Rx, Tx) { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores
    // - install neighbor table entry for reply
    // - send first packet before core is scheduled
    // - core is preempted to run proc
    // - read first packet, check timestamps (queued)
    // - write first response, receive response and check
    // - read second packet (stalled)
    // - send second packet, check timestamps
    // - write second reponse, receive response and check
    var readingSecond = false

    // test timestamp collection with oncrpc call
    val delayed = 10000

    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(10000)
    val trace = traceConsumer
    val workerTraceCoreId = 1

    // enable rx
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("rt-timestamped")).head

    // first request packet
    val (packet, pld, xid) = getPacket()

    // first response data (longer than inline bytes)
    val respData = simRandom.nextBytes(512).toList
    val clientIp = packet.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac = packet.getHeader.getSrcAddr

    // second request packet
    val (packet2, pld2, xid2) = getPacket()

    // second response data (shorter than inline bytes)
    val respData2 = simRandom.nextBytes(16).toList
    val clientIp2 = packet2.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac2 = packet2.getHeader.getSrcAddr

    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)

    // set up neighbor table for replies
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)

    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp2.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac2.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 1.toBytesLE)

    val firstTraceStart = trace.cursor
    var allDone = false
    // network-side thread
    fork {
      // send first packet -- host not ready yet, packet will be queued
      axisMaster.send(packet.getRawData.toList)
      println("Sent first request packet")

      // receive first response: check headers and data body
      val data = axisSlave.recv()
      val parsed = EthernetPacket.newPacket(data.toArray, 0, data.length)
      assert(parsed.getHeader.getDstAddr == clientMac, "received packet has wrong destination MAC address")
      assert(parsed.getHeader.getSrcAddr == serverMac, "received packet has wrong source MAC address")
      assert(parsed.get(classOf[IpV4Packet]).getHeader.getDstAddr == clientIp, "received packet has wrong destination IP address")
      assert(parsed.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "received packet has wrong source IP address")
      assert(parsed.get(classOf[UdpPacket]).getHeader.getSrcPort == packet.get(classOf[UdpPacket]).getHeader.getDstPort, "received packet has wrong source port")
      assert(parsed.get(classOf[UdpPacket]).getHeader.getDstPort == packet.get(classOf[UdpPacket]).getHeader.getSrcPort, "received packet has wrong destination port")

      val udpPayload = parsed.get(classOf[UdpPacket]).getPayload.getRawData.toList
      val (rpcHdr, rpcPayload) = udpPayload.splitAt(24) // XID + msgType + replyStat + verifier + acceptStat
      assert(rpcHdr == expectedOncRpcReplyHeader(xid), "ONC-RPC reply header mismatch")

      check(respData, rpcPayload)
      println("Received and checked first response")

      // wait until host is ready and is actively reading,
      // to generate timestamps for a core that's waiting
      waitUntil(readingSecond)

      // ensure the host is actually reading
      sleepCycles(delayed)

      // send second packet -- host already reading and stalled
      axisMaster.send(packet2.getRawData.toList)
      println("Sent second request packet")

      val data2 = axisSlave.recv()
      val parsed2 = EthernetPacket.newPacket(data2.toArray, 0, data2.length)
      assert(parsed2.getHeader.getDstAddr == clientMac2, "received packet has wrong destination MAC address")
      assert(parsed2.getHeader.getSrcAddr == serverMac, "received packet has wrong source MAC address")
      assert(parsed2.get(classOf[IpV4Packet]).getHeader.getDstAddr == clientIp2, "received packet has wrong destination IP address")
      assert(parsed2.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "received packet has wrong source IP address")
      assert(parsed2.get(classOf[UdpPacket]).getHeader.getSrcPort == packet2.get(classOf[UdpPacket]).getHeader.getDstPort, "received packet has wrong source port")
      assert(parsed2.get(classOf[UdpPacket]).getHeader.getDstPort == packet2.get(classOf[UdpPacket]).getHeader.getSrcPort, "received packet has wrong destination port")

      val udpPayload2 = parsed2.get(classOf[UdpPacket]).getPayload.getRawData.toList
      val (rpcHdr2, rpcPayload2) = udpPayload2.splitAt(24)
      assert(rpcHdr2 == expectedOncRpcReplyHeader(xid2), "ONC-RPC reply header mismatch")
      check(respData2, rpcPayload2)
      println("Received and checked second response")

      allDone = true
    }

    // we should receive the request on worker core #0
    val cs = workerCore(0)

    cs.log("Wait until a user thread is scheduled")
    cs.waitUser()

    val tid = cs.currThread.get.tid
    cs.log("Entered user thread")

    // for checking timestamps
    sleepCycles(delayed)

    // read first request
    val firstToken = {
      val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      // check if decoded packet is what we sent
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = requestXid(csrMaster, info.requestToken)
      assert(receivedXid == xid, f"xid mismatch: expected $xid%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld, readPayload(dcsMaster, pldDesc, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = trace.currentCycle

      // we don't use the commit timestamp since commit is tied to read next
      val ts = getRxTimestamps(trace, Some(workerTraceCoreId), firstTraceStart)

      // check timestamps for first packet
      import ts._

      println(s"Current timestamp after packet 1 done: $curr")

      // we can't check afterRead since it's triggered by the opposite read
      assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, /*afterRead, */curr))
      assert(readStart - entry >= delayed)

      info.requestToken // the wire XID stays in hardware
    }

    // send first response
    {
      val (respInlineData, respTail) = respData.splitAt(ONCRPC_INLINE_BYTES)
      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData.length, firstToken, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    val secondTraceStart = trace.cursor

    // we can now read second packet
    readingSecond = true

    val secondToken = {
      val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = requestXid(csrMaster, info.requestToken)
      assert(receivedXid == xid2, f"xid2 mismatch: expected $xid2%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld2, readPayload(dcsMaster, pldDesc, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = trace.currentCycle
      val ts = getRxTimestamps(trace, Some(workerTraceCoreId), secondTraceStart)
      import ts._
      println(s"Current timestamp after packet 2 done: $curr")
      assert(isSorted(readPending, entry, afterRxQueue, enqueueToHost/*, afterRead*/, curr))
      assert(isSorted(readPending, readStart/*, afterRead*/))
      assert(entry - readPending >= delayed * 4 / 5)

      info.requestToken
    }

    // send second response
    {
      val (respInlineData, respTail) = respData2.splitAt(ONCRPC_INLINE_BYTES)
      assert(respTail.isEmpty, "should not have any tail data")

      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData2.length, secondToken, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    waitUntil(allDone)
  }

  ignore("rx-hol-blocking-free") { _ =>
    // This test checks that no HOL-blocking happens between RX of different worker
    // cores.  This is important since if HOL-blocking happens, the 2F2F state machine
    // won't even see a read that's blocked by another stall(-to-NACK) read, triggering
    // timeout for the blocked read easily.

    // Same requirements for wait-freedom apply as in rx-tx-interleave.
    pending // Unimplemented historical coverage, not a passing regression.
  }

  // FIXME: rework the address map (from Jasmin) and re-enable this for CI!
  testWithDB("rx-tx-interleaved")(Rx, Tx, Slow) { implicit dut =>
    // The bypass core can have RX and TX happening simultaneously, so it's
    // important that the interconnects can allow unrelated reads/writes to
    // interleave.  However, we can't directly check on bypass, since no
    // read blocking is in place for bypass, making it difficult to construct
    // an artificial case where two reads will go to the same 2F2F state machine
    // and potentially interleave.  In this case we test with one worker core
    // and skip the TX critical section to allow the following two reads to
    // interleave:
    // - RX read (blocked until NACK)
    // - TX read (CL refill for load exclusive -> modify)
    //
    // This test checks that no HOL-blocking happens between the RX and TX paths.

    // This requires three levels of wait-freedom:
    // - the crossbar not blocking requests to unrelated slaves (e.g.
    //   Axi4CrossbarFactory does not accept a transaction that goes to a
    //   different slave than the in-flight one)
    //   => currently fixed with axi_crossbar from verilog-axi
    // - the address map not placing the RX and TX control CLs on the same
    //   DCU, since each DCU can have one read and one write in-flight
    //   (DcsAppMaster emulates this behaviour)
    //   => TODO
    // - the downsize adapter allowing more in-flight requests than possible
    //   number of stalled requests (i.e. number of workers)
    //   (DcsAppMaster emulates this behaviour)
    //   => allowed via setting AXI_MAX_READS >= NUM_CORES in dcs_cdc.sv

    // use very high read timeout
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(20000)

    // set up a random process
    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom))).head

    // send a packet to get scheduled
    val (pkt1, pld, xid) = getPacket()
    fork {
      axisMaster.send(pkt1.getRawData.toList)
      println("Sent first request packet")
    }

    val cs = workerCore(0)
    cs.waitUser()
    val tid = cs.currThread.get.tid
    cs.log("Entered user thread")

    val firstRequest = tryReadPacketDesc(dcsMaster, tid).result.get._1.asInstanceOf[OncRpcCallRxPacketDescSim]

    // start reading second packet
    var done = false
    fork {
      println("Starting long read that will block...")
      assert(tryReadPacketDesc(dcsMaster, tid, maxTries = 1).result.isEmpty)
      println("Read done!")
      done = true
    }

    // try sending a packet and see if we can get through
    {
      val resp1 = simRandom.nextBytes(16).toList
      val desc = TxOncRpcReplySim(resp1.length, firstRequest.requestToken, resp1.bytesToBigInt)

      sleepCycles(1000)

      println("Starting read for TX...")
      // skip critical section!
      txSendSingle(dcsMaster, desc, List.empty, tid, skipCS = true)

      // wait a bit and check did we finish after RX did;
      // if we could properly interleave, this small wait won't make a difference;
      // if we would be blocked, the wait would allow RX to set done and thus fail
      // the assertion
      sleepCycles(5000)
      assert(!done, "TX delayed until RX is finished!")
      println("TX done")
    }

    waitUntil(done)
  }


  /* Test that Lauberhorn can scale up to multiple services */
  testWithDB("rx-sched-idle-scale-many")(Rx, Tx) { implicit dut =>
    // do not use every core for every service
    // three procs: A (2 thr); B (3 thr); C (3 thr)

    val srvDefs = Seq(
      mkRandomProc(2) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq.fill(2)(RpcSrvDef.mkRandom),
    )
    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(1000)
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)
    fork { while (true) axisSlave.recv() }

    val srvs = oncRpcCallPacketFactory(csrMaster, srvDefs,
      Some("rx-sched-idle-scale-many"))
    // sending packets is based on per service
    val pktsToSendStructured = srvDefs.map { case (_, ss) =>
      ss.map { srv =>
        val toSend = simRandom.between(50, 100)
        println(s"Sending $toSend requests for service $srv")
        toSend
      }
    }
    val pktsToSend = pktsToSendStructured.flatten
    val pktsSent = mutable.ArrayBuffer.fill(srvs.length)(0)

    // receiving packets is based on per proc
    val pktsExpecting = pktsToSendStructured.map(_.sum)
    val pktsReceived = mutable.ArrayBuffer.fill(srvDefs.length)(0)

    // (PID, XID) => (packet, payload)
    val pktsToReceive = mutable.Map[(Int, Int), (EthernetPacket, List[Byte], Long)]()

    def pidToTblIdx(pid: Int) = srvDefs.indexWhere { case (pdef, _) => pdef.pid == pid } + 1

    // each thread has a max number of retries
    val threadRetryMap = mutable.HashMap[Int, Int]()

    fork {
      while (pktsToSend.sum > pktsSent.sum) {
        val srvToSend = simRandom.nextInt(srvs.length)
        if (pktsToSend(srvToSend) > pktsSent(srvToSend)) {
          val (funcPtr, getPacket, pid) = srvs(srvToSend)

          // This test is designed to allow all cores to receive all packets sent; we need to wait if the queue for the
          // corresponding PID is about to overflow.  If the scheduler failed to preempt some core to handle a non-empty
          // queue, the core reads will eventually run out of retries.
          val pidIdx = pidToTblIdx(pid)
          println(f"Checking queue capacity for PID $pid%#x (index $pidIdx)")
          csrMaster.write(ALLOC.readBack("sched")("stat", "readback_idx"), pidIdx.toBytesLE)
          val queueFill = csrMaster.read(ALLOC.readBack("sched")("stat", "readback_queueFill"), 8).bytesToBigInt
          println(f"PID $pid%#x has $queueFill elements queued in scheduler")

          // XXX: heuristic!  more packets can be pending in decoder pipeline and not yet pushed to queue
          if (queueFill >= RX_PKTS_PER_PROC.get - 8 || pktsToReceive.size >= NUM_SESSIONS.get / 2) {
            println(f"Trying to send for srvId $srvToSend: PID $pid%#x's queue is almost full, skipping sending and throttling")
            sleepCycles(200)
          } else {
            val (rawPacket, pld, xid) = getPacket()
            val pkt = fixedPeer(rawPacket)
            println(f"Sending packet for PID $pid%#x with XID $xid%#x")
            val toSend = pkt.getRawData.toList
            assert(!pktsToReceive.contains((pid, xid)), "random packet generation collision")
            pktsToReceive((pid, xid)) = (pkt, pld, funcPtr)
            axisMaster.send(toSend)
            pktsSent(srvToSend) += 1
          }
        }
      }
    }

    0 until NUM_WORKER_CORES foreach { wcid =>
      fork {
        val cs = workerCore(wcid)

        cs.log("Wait until a user thread is scheduled")
        cs.waitUser()

        def tid = cs.currThread.get.tid
        def pid = cs.currThread.get.proc.pid

        def procLog(msg: String) = cs.log(f"<pid $pid%#x> $msg")
        def currIdx = pidToTblIdx(pid) - 1

        while (pktsExpecting.sum != pktsReceived.sum) {
          if (pktsExpecting(currIdx) > pktsReceived(currIdx)) {
            cs.waitUser()
            procLog("try receive one")

            // this read might be launched before the queue was empty
            val descOption = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result
            if (descOption.nonEmpty) {
              // reset retry count for this thread
              threadRetryMap(tid) = 0

              val (desc, pldDesc) = descOption.get
              val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
              procLog(s"received status $desc")
              val tail = readPayload(dcsMaster, pldDesc, desc.len)
              procLog(s"received trailing payload ${tail.bytesToHex} (len ${desc.len})")

              exitCriticalSection(dcsMaster, tid)
              procLog("finished receiving")
              randomSleep(100, 50)

              val xid = requestXid(csrMaster, info.requestToken)
              if (!pktsToReceive.contains((pid, xid))) {
                procLog(f"!!! XID $xid%#x not found!  Following XIDs have been sent for us:")
                pktsToReceive.view.filterKeys(_._1 == pid).foreach { case ((_, x), _) =>
                  println(f"XID $x%#x")
                }
                simFailure("XID not found")
              }
              val (pkt, pld, funcPtr) = pktsToReceive((pid, xid))
              procLog(f"received xid $xid%#x, expecting packet $pkt")
              checkOncRpcCall(desc, desc.len, funcPtr, pld, tail)

              txSendSingle(dcsMaster, TxOncRpcReplySim(0, info.requestToken, BigInt(0)), Nil, tid)
              pktsToReceive.remove((pid, xid))
              procLog(s"finished (simulated) processing packet #${pktsReceived.sum}")
              pktsReceived(currIdx) += 1
            } else {
              procLog(s"try receive timed out, checking if process is finished...")

              val retries = threadRetryMap.getOrElseUpdate(tid, 0)
              assert(retries <= 5, "ran out of retries for thread")
              threadRetryMap(tid) += 1
            }
          } else {
            procLog("process finished receiving, waiting for preemption...")
            waitUntil(cs.inISR)
          }
        }
      }
    }

    waitUntil(pktsExpecting.sum == pktsReceived.sum)
  }

  testWithDB("watchdog-suspend-resume-and-kill")(Rx) { implicit dut =>
    SimTimeout(5000000000L)
    val (csr, ingress, egress, dcs) = commonDutSetup(10000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    fork { egress.recv(); fail("execution control emitted a network packet") }
    val process = mkRandomProc(1)
    val (_, packet, _) = oncRpcCallPacketFactory(csr,
      procSrvMap = Seq(process -> Seq(RpcSrvDef.mkRandom))).head
    val sched = ALLOC.readBack("sched")
    csr.write(sched("ctrl", "watchdog_cycles"), 10000.toBytesLE)
    csr.write(sched("ctrl", "watchdog_idx"), 1.toBytesLE)
    val (request, _, _) = packet()
    ingress.send(request.getRawData.toList)
    val worker = workerCore(0)
    worker.allowKill = true
    worker.waitUser()
    val tid = worker.currThread.get.tid
    assert(tryReadPacketDesc(dcs, tid, exitCS = true).result.nonEmpty)
    // No network packet is emitted by either notification. The normal ECI TX
    // acknowledgement still proves that hardware applied the transition.
    txSendSingle(dcs, TxExecutionSim(1), Nil, tid)
    sleepCycles(20000)
    assert(worker.killCount == 0 && worker.currThread.nonEmpty)
    txSendSingle(dcs, TxExecutionSim(3), Nil, tid)
    waitUntil(worker.currThread.isEmpty && !worker.inISR)
    assert(worker.killCount == 1)
    assert(csr.read(sched("stat", "watchdogControls_core1"), 8).bytesToBigInt == 2)
    assert(csr.read(sched("stat", "watchdogExpirations_core1"), 8).bytesToBigInt == 1)
    // A concurrent maxThreads/enable publication must not resurrect a killed
    // lifetime before the kernel observes and completes whole-process recovery.
    process.enableAt(1, csr)
    csr.write(sched("ctrl", "wake_idx"), 1.toBytesLE)
    val (lateRequest, _, _) = packet()
    ingress.send(lateRequest.getRawData.toList)
    sleepCycles(20000)
    assert(worker.currThread.isEmpty && worker.killCount == 1)
    // Only explicit configuration for a new, drained lifetime clears the latch.
    csr.write(sched("ctrl", "watchdog_cycles"), 0.toBytesLE)
    csr.write(sched("ctrl", "watchdog_idx"), 1.toBytesLE)
    process.enableAt(1, csr)
    csr.write(sched("ctrl", "wake_idx"), 1.toBytesLE)
    worker.waitUser()
    assert(worker.currThread.get.proc.pid == process.pid)
  }

  testWithDB("deadline-wake-without-network-traffic")(Rx) { implicit dut =>
    SimTimeout(5000000000L)
    val (csr, _, _, _) = commonDutSetup(10000)
    val process = mkRandomProc(1)
    oncRpcCallPacketFactory(csr, procSrvMap = Seq(process -> Seq(RpcSrvDef.mkRandom)))
    csr.write(ALLOC.readBack("sched")("ctrl", "wake_idx"), 1.toBytesLE)
    val worker = workerCore(0)
    worker.waitUser()
    assert(worker.currThread.get.proc.pid == process.pid)
  }

  testWithDB("stop-active-process-and-reuse-worker")(Rx) { implicit dut =>
    SimTimeout(5000000000L)
    val (csr, ingress, egress, dcs) = commonDutSetup(10000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    fork { egress.recv(); fail("uncommitted TX emitted a network packet") }
    val process = mkRandomProc(1)
    val (_, packet, _) = oncRpcCallPacketFactory(csr,
      procSrvMap = Seq(process -> Seq(RpcSrvDef.mkRandom))).head
    val worker = workerCore(0)
    for(round <- 0 until 3) {
      if(round != 0) process.enableAt(1, csr)
      val (request, _, _) = packet()
      ingress.send(request.getRawData.toList)
      worker.waitUser()
      val tid = worker.currThread.get.tid
      worker.allowKill = round == 1
      assert(tryReadPacketDesc(dcs, tid, exitCS = round != 1).result.nonEmpty)
      // Round 1 also leaves BUSY high: independent critical-section timeout
      // must report killed and drain while execution monitoring is disabled.
      if(round == 2) {
        assert(enterCriticalSection(dcs, tid))
        val td = getEciThreadData(tid)
        val txBase = td.baseAddr + ECI_TX_BASE.get
        dcs.write(txBase + td.txNextCl * 0x80, List.fill(64)(0xff.toByte))
        dcs.write(txBase + ECI_OVERFLOW_OFFSET, List.fill(128)(0xa5.toByte))
        exitCriticalSection(dcs, tid)
      }
      // Handler has consumed RX but never replied and does not poll again.
      // Stop must run without new traffic or RX-ready and retain mapping until drain.
      csr.write(ALLOC.readBack("sched")("ctrl", "proc_enabled"), 0.toBytesLE)
      csr.write(ALLOC.readBack("sched")("ctrl", "proc_idx"), 1.toBytesLE)
      waitUntil(worker.currThread.isEmpty && !worker.inISR)
      assert(worker.killCount == (if(round == 0) 0 else 1))
      assert(csr.read(ALLOC.readBack("sched")("stat", "watchdogControls_core1"), 8).bytesToBigInt == 0)
      assert(csr.read(ALLOC.readBack("sched")("stat", "watchdogExpirations_core1"), 8).bytesToBigInt == 0)
      var draining = true
      while(draining) {
        csr.write(ALLOC.readBack("sched")("stat", "readback_idx"), 1.toBytesLE)
        draining = csr.read(ALLOC.readBack("sched")("stat", "readback_draining"), 8).bytesToBigInt != 0
      }
      csr.write(ALLOC.readBack("OncRpcReplyEncoder")("ctrl", "context_reclaim_pid"), process.pid.toBytesLE)
    }
  }

  /** After preemption, no CLs should be Shared -- otherwise we leak a descriptor from the previous
   * application on this core */
  ignore("rx-preempt-no-leaking") { _ =>
    // Spawn two services.  Send a few requests for the first one, drain all of them, then
    // send a request to the second service.  After the core finishes preemption, check if
    // no info is leaked (the new process only sees a NACK in the opposite CL)
    pending // Unimplemented historical coverage, not a passing regression.
  }


}
