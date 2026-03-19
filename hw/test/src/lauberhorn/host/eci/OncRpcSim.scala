package lauberhorn.host.eci

import jsteward.blocks.eci.sim.{DcsAppMaster, IpiSlave}
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.{BigIntParser, IntRicherEndianAware, hexToBytesBE, isSorted}
import org.pcap4j.core.{PcapDumper, Pcaps}
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, IpV4Rfc1349Tos, Packet, UdpPacket}
import org.pcap4j.packet.namednumber.{DataLinkType, EtherType, IpNumber, IpVersion}
import org.scalatest.exceptions.TestFailedException
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.sim._
import lauberhorn.sim.PacketType._
import org.pcap4j.util.MacAddress
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _, _}
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}

import scala.collection.mutable
import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._
import org.scalatest.tagobjects.Slow

import java.net.{Inet4Address, InetAddress}

class OncRpcSim extends NicSim with OncRpcSuiteFactory {
  testWithDB("rx-oncrpc-allcores")(Rx) { implicit dut =>
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
      packetDumpWorkspace = Some("rx-oncrpc-allcores")
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

  testWithDB("roundtrip-oncrpc-timestamped")(Rx, Tx) { implicit dut =>
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
      packetDumpWorkspace = Some("roundtrip-oncrpc-timestamped")).head

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
}