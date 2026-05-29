package lauberhorn.host.eci

import jsteward.blocks.misc.sim.{IntRicherEndianAware, isSorted}
import lauberhorn.Global._
import lauberhorn.sim._
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, UdpPacket}
import org.scalatest.tagobjects.Slow
import spinal.core.sim._
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _}
import spinal.lib._

import scala.collection.mutable
import scala.language.postfixOps

class OncRpcSim extends NicSim with OncRpcSuiteFactory {
  testWithDB("rx-allcores")(Rx) { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores,
    //   creating a thread for every worker core
    // - send 50 * numWorkerCores requests
    // - cores are preempted as requests come in, they start to read
    // - eventually all packets are received through all cores

    val totalToSend = 50 * NUM_WORKER_CORES

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)

    // test one service on one process on all cores
    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("rx-allcores")
    ).head
    val inflightPackets = mutable.Map[Int, (EthernetPacket, List[Byte])]()
    var packetsReceived = 0

    fork {
      // send all packets
      0 until totalToSend foreach { idx =>
        // flow control: make sure we don't have more than RX_PKTS_PER_PROC packets in flight
        while (inflightPackets.size >= RX_PKTS_PER_PROC) {
          println(s"Already ${RX_PKTS_PER_PROC.get} packets in flight, waiting...")
          // sleep a little bit longer to allow bursts
          sleepCycles(1000)
        }

        val (packet, payload, xid) = getPacket()
        println(f"Sending packet with XID $xid%#x")
        val toSend = packet.getRawData.toList
        // blocking send
        axisMaster.send(toSend)

        // record packet in map: xid is key
        // FIXME: this might collide..
        assert(!inflightPackets.contains(xid), "random packet generation collision")
        inflightPackets(xid) = (packet, payload)
      }
    }

    0 until NUM_WORKER_CORES foreach { wcid =>
      fork {
        val cs = workerCore(wcid)

        cs.log("Wait until a user thread is scheduled")
        cs.waitUser()

        val tid = cs.currThread.get.tid
        cs.log("returned to userspace")

        def tryReceiveAndCheckOne(): Unit = {
          val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result match {
            case Some(ret) => ret
            case None =>
              cs.log("ran out of tries, checking if we are still expecting packets")
              if (packetsReceived != totalToSend) {
                cs.log(s"packets still in flight: ${inflightPackets.mkString(", ")}")
                fail(s"worker $wcid ran out of tries!  only received $packetsReceived packets, expected $totalToSend")
              } else {
                cs.log("no more packets expected, exiting...")
                return
              }
          }

          val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
          cs.log(f"Received status register: $desc")

          // do not process packets too fast, or other cores will never get invoked
          randomSleep(100, 50)
          cs.log("Sleep finished, checking packet data...")

          // packet generator return little endian xid but sends in big endian
          // HW does not change (i.e. we get big endian back)
          val xid = Integer.reverseBytes(info.xid.toInt)
          cs.log(f"Received XID $xid%x")

          // find the payload that we sent
          val (pkt, pld) = inflightPackets(xid)
          cs.log(f"Expecting packet: $pkt")

          checkOncRpcCall(desc, desc.len, funcPtr, pld, readPayload(dcsMaster, pldDesc, desc.len))
          cs.log(f"Received packet #$packetsReceived (XID $xid%x)")
          packetsReceived += 1

          inflightPackets.remove(xid)

          exitCriticalSection(dcsMaster, tid)
        }

        while (packetsReceived != totalToSend) {
          // read and check packet against sent
          tryReceiveAndCheckOne()
        }
      }
    }

    waitUntil(packetsReceived == totalToSend)
  }

  // FIXME: temporarily disabled due to profiler update
  // TODO:  update to new tracing architecture
  /**
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
    val delayed = 1000

    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(100)

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
      assert(rpcHdr.take(4) == xid.toBytesBE, "XID mismatch")

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
      assert(rpcHdr2.take(4) == xid2.toBytesBE, "XID mismatch")
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
    val firstXid = {
      val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      // check if decoded packet is what we sent
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid, f"xid mismatch: expected $xid%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld, readPayload(dcsMaster, pldDesc, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = csrMaster.read(ALLOC.readBack("profiler")("cycles"), 8).bytesToBigInt

      // we don't use the commit timestamp since commit is tied to read next
      val ts = getRxTimestamps(csrMaster)

      // check timestamps for first packet
      import ts._

      println(s"Current timestamp after packet 1 done: $curr")

      assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, curr))
      assert(readStart - entry >= delayed)

      info.xid // host will see XID in big endian; should be sent back as is
    }

    // send first response
    {
      val (respInlineData, respTail) = respData.splitAt(ONCRPC_INLINE_BYTES)
      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData.length, funcPtr, firstXid, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    // we can now read second packet
    readingSecond = true

    val secondXid = {
      val (desc, pldDesc) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid2, f"xid2 mismatch: expected $xid2%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld2, readPayload(dcsMaster, pldDesc, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = csrMaster.read(ALLOC.readBack("profiler")("cycles"), 8).bytesToBigInt
      val ts = getRxTimestamps(csrMaster)
      import ts._
      println(s"Current timestamp after packet 2 done: $curr")
      assert(isSorted(readStart, entry, afterRxQueue, enqueueToHost, curr))
      assert(entry - readStart >= delayed)

      info.xid
    }

    // send second response
    {
      val (respInlineData, respTail) = respData2.splitAt(ONCRPC_INLINE_BYTES)
      assert(respTail.isEmpty, "should not have any tail data")

      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData2.length, funcPtr, secondXid, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    waitUntil(allDone)
  }
  */

  testWithDB("rx-hol-blocking-free")(Rx) { implicit dut =>
    // This test checks that no HOL-blocking happens between RX of different worker
    // cores.  This is important since if HOL-blocking happens, the 2F2F state machine
    // won't even see a read that's blocked by another stall(-to-NACK) read, triggering
    // timeout for the blocked read easily.

    // Same requirements for wait-freedom apply as in rx-tx-interleave.
    // TODO
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

    val _ = tryReadPacketDesc(dcsMaster, tid).result.get

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
      val desc = TxOncRpcReplySim(resp1.length, funcPtr, xid, resp1.bytesToBigInt)

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
  testWithDB("rx-sched-idle-scale-many")(Rx) { implicit dut =>
    // do not use every core for every service
    // three procs: A (2 thr); B (3 thr); C (3 thr)

    val srvDefs = Seq(
      mkRandomProc(2) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq.fill(2)(RpcSrvDef.mkRandom),
    )
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)

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
          if (queueFill >= RX_PKTS_PER_PROC.get - 8) {
            println(f"Trying to send for srvId $srvToSend: PID $pid%#x's queue is almost full, skipping sending and throttling")
            sleepCycles(200)
          } else {
            val (pkt, pld, xid) = getPacket()
            println(f"Sending packet for PID $pid%#x with XID $xid%#x")
            val toSend = pkt.getRawData.toList
            axisMaster.send(toSend)

            assert(!pktsToReceive.contains((pid, xid)), "random packet generation collision")
            pktsToReceive((pid, xid)) = (pkt, pld, funcPtr)
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

              val xid = Integer.reverseBytes(info.xid.toInt)
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

  /** After preemption, no CLs should be Shared -- otherwise we leak a descriptor from the previous
   * application on this core */
  testWithDB("rx-preempt-no-leaking")(Rx) { implicit dut =>
    // Spawn two services.  Send a few requests for the first one, drain all of them, then
    // send a request to the second service.  After the core finishes preemption, check if
    // no info is leaked (the new process only sees a NACK in the opposite CL)
    // TODO
  }

  /* Test killing a process that did not unset BUSY */
  testWithDB("rx-sched-crit-timeout")(Rx) { implicit dut =>
    // TODO
  }
}
