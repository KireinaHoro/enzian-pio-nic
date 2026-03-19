package lauberhorn.host.eci

import jsteward.blocks.eci.sim.DcsAppMaster
import jsteward.blocks.misc.sim.{IntRicherEndianAware, hexToBytesBE}
import lauberhorn.Global._
import lauberhorn.NicEngine
import lauberhorn.sim.PacketType.{Ethernet, Ip}
import lauberhorn.sim.{Tx, enzianIpMacAddrs, getIpPacketFromEnzian, pcap4jPacketToType, rawPayloadBuilder}
import org.pcap4j.core.{PcapDumper, Pcaps}
import org.pcap4j.packet.{EthernetPacket, IpV4Packet}
import org.pcap4j.packet.namednumber.{DataLinkType, EtherType}
import org.pcap4j.util.MacAddress
import org.scalatest.tagobjects.Slow
import spinal.core.sim.{fork, simRandom, waitUntil}
import spinal.lib.BytesRicher
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.Axi4StreamSlave

import java.net.InetAddress

class TxBypassSim extends NicSim {
  /** Test sending one single packet as bypass on a specific thread.  Also checks if the expected packet appears on the
   * outgoing AXI-Stream interface.
   *
   * Sends packet through the bypass interface, which only takes destination addresses.  Checks the output against
   * the full packet.
   *
   * This function pre-programs the neighbor table entry!  Test "neighbor-resolve-request" checks if a bypass
   * request for neighbor resolving is correctly sent to the host, when a neighbor entry is missing.
   *
   * This function assumes the thread routing has been set up correctly!  This is the case for the bypass thread (done
   * in [[commonDutSetup]]) but not for worker cores.
   */
  def txTestSingle(dcsMaster: DcsAppMaster, csrMaster: AxiLite4Master, axisSlave: Axi4StreamSlave, packet: EthernetPacket, tid: Int)
                  (implicit dut: NicEngine): Unit = {
    var received = false
    val etd = getEciThreadData(tid)
    val ty = pcap4jPacketToType(packet)
    val (pld, desc) = ty match {
      case Ethernet =>
        val pld = packet.getPayload.getRawData.toList
        val hdr = packet.getHeader
        val desc = TxEthernetCmdSim(pld.length, hdr.getDstAddr, hdr.getType.value)
        (pld, desc)

      case Ip =>
        val ipPkt = packet.get(classOf[IpV4Packet])
        val pld = ipPkt.getPayload.getRawData.toList
        val hdr = ipPkt.getHeader
        val ipDst = hdr.getDstAddr
        val desc = TxIpCmdSim(pld.length, ipDst, hdr.getProtocol.value.toInt)

        // program the correct neighbor entry
        // XXX: this always use the first neighbor entry
        val ethDst = packet.getHeader.getDstAddr
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), ipDst.getAddress.toList)
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), ethDst.getAddress.toList)
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)

        (pld, desc)
    }

    fork {
      val data = axisSlave.recv()
      val expected = packet.getRawData.toList

      check(expected, data)
      etd.log("packet received from TX interface and validated")
      received = true
    }

    txSendSingle(dcsMaster, desc, pld, tid)

    etd.log("waiting for packet")
    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  /** Tests sending a range of sizes of packets over the bypass channel of a specific thread. */
  def txTestRange(axisSlave: Axi4StreamSlave, dcsMaster: DcsAppMaster, csrMaster: AxiLite4Master, startSize: Int, endSize: Int, step: Int, tid: Int)
                 (implicit d: PcapDumper, dut: NicEngine) = {
    // Sweep at given range and step, send IP packets over bypass
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until simRandom.between(25, 50) foreach { _ =>
        txTestSingle(dcsMaster, csrMaster, axisSlave, getIpPacketFromEnzian(1, size), tid)
      }
    }
  }

  def txScanOnCore(cid: Int) = {
    val testName = s"scan-sizes-core$cid"
    testWithDB(testName)(Slow, Tx) { implicit dut =>
      implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets-expecting.pcap").toString)

      val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

      val tid = if (cid == 0) -1 else {
        // create one process with one thread, enable that thread on the core under test
        val proc = mkRandomProc(1)
        val thr = proc.threads.head
        workerCore(cid - 1).switchToThread(thr, csrMaster)

        thr.tid
      }

      txTestRange(axisSlave, dcsMaster, csrMaster, 64, 9618, 64, tid)
    }
  }

  0 until numCores foreach txScanOnCore

  testWithDB("simple")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("simple") / "packets-expecting.pcap").toString)

    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

    txTestRange(axisSlave, dcsMaster, csrMaster, 64, 256, 64, -1)
  }

  // Test sending IPv6 packets (produced by Linux kernel)
  testWithDB("icmp6")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("icmp6") / "packets-expecting.pcap").toString)

    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

    val (_, _, ourMac) = enzianIpMacAddrs(14)
    csrMaster.write(ALLOC.readBack("EthernetDecoder")("ctrl", "macAddress"), ourMac.getAddress.toList)

    def testIcmp6(ethPld: String, dstMac: String) = {
      val ip6Builder = rawPayloadBuilder(hexToBytesBE(ethPld).toArray)
      val ethBuilder = (new EthernetPacket.Builder)
        .srcAddr(ourMac)
        .dstAddr(MacAddress.getByName(dstMac))
        .`type`(EtherType.IPV6)
        .paddingAtBuild(true)
        .payloadBuilder(ip6Builder)

      val pkt = ethBuilder.build()
      dumper.dump(pkt)
      dumper.flush()

      txTestSingle(dcsMaster, csrMaster, axisSlave, pkt, -1)
    }

    // ICMPv6 Neighbor Solicitation
    def ns() = testIcmp6(
      "6000000000203aff00000000000000000000000000000000ff0200000000000000000001ff0301c8" +
        "8700c21c00000000fe800000000000000e5331fffe0301c80e01c7a5cf7ad2f7",
      "33:33:ff:03:01:c8"
    )

    // ICMPv6 Neighbor Advertisement
    def na() = testIcmp6(
      "6000000000203afffe800000000000000e5331fffe0301c8ff020000000000000000000000000001" +
        "88009a4320000000fe800000000000000e5331fffe0301c802010c53310301c8",
      "33:33:00:00:00:01"
    )

    // ICMPv6 Router Solicitation
    def rs() = testIcmp6(
      "6000000000103afffe800000000000000e5331fffe0301c8ff020000000000000000000000000002" +
        "8500fcf10000000001010c53310301c8",
      "33:33:00:00:00:02"
    )

    // ICMPv6 Multicast Listener Report Message v2
    def mlrm1() = testIcmp6(
      "600000000024000100000000000000000000000000000000ff0200000000000000000000000000163a00050200000100" +
        "8f006dbf0000000104000000ff0200000000000000000001ff0301c8",
      "33:33:00:00:00:16"
    )

    def mlrm2() = testIcmp6(
      "6000000000240001fe800000000000000e5331fffe0301c8ff0200000000000000000000000000163a00050200000100" +
        "8f002f200000000104000000ff0200000000000000000001ff0301c8",
      "33:33:00:00:00:16"
    )

    val tests: Seq[() => Unit] = Seq(ns, na, rs, mlrm1, mlrm2)
    LazyList.continually(tests(simRandom.nextInt(tests.length)))
      .take(400).foreach(_())
  }

  def txAllCores(doVoluntaryInv: Boolean) = {
    val testName = s"all-cores-${if (!doVoluntaryInv) "no-" else ""}inv"
    testWithDB(testName, timeout = 10e9.toLong)(Tx) { implicit dut =>
      val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

      implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets-expecting.pcap").toString)

      // create one process with threads on all worker cores
      val proc = mkRandomProc(NUM_WORKER_CORES)

      if (!doVoluntaryInv) {
        dcsMaster.voluntaryInvProb = 0
        dcsMaster.doPartialWrite = false
      }

      0 until NUM_CORES foreach { idx =>
        println(s"====> Testing core $idx")

        val tid = if (idx == 0) -1 else {
          val wcid = idx - 1
          val thr = proc.threads(wcid)
          workerCore(wcid).switchToThread(thr, csrMaster)

          thr.tid
        }

        txTestRange(axisSlave, dcsMaster, csrMaster, 64, 256, 64, tid)
      }
    }
  }

  Seq(false, true) foreach txAllCores

  testWithDB("neighbor-resolve-request")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("neighbor-resolve-request") / "packets-expecting.pcap").toString)

    val pkt = getIpPacketFromEnzian(1, 512)
    val ipPkt = pkt.get(classOf[IpV4Packet])
    val ipDst = ipPkt.getHeader.getDstAddr
    val macDst = pkt.getHeader.getDstAddr

    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(1000)

    // serve ARP resolve request in kernel
    var reqServed = false
    setBypassCore(() => {
      val (info, _) = tryReadPacketDesc(dcsMaster, tid = -1, maxTries = 1).result.get
      val arpReq = info.asInstanceOf[TxArpReqSim]
      val addr = InetAddress.getByAddress(arpReq.ipAddr.toBytesLE.toArray)
      println(s"Received ARP request to $addr on table entry #${arpReq.neighTblIdx}")
      assert(addr == ipDst, "received ARP request for wrong IP address")

      // check if neighbor entry is in `incomplete`
      csrMaster.write(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_idx"), arpReq.neighTblIdx.toBytesLE)
      val addrInTbl = csrMaster.read(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_ipAddr"), 4)
      assert(addrInTbl.toArray sameElements addr.getAddress, "entry waiting for ARP does not have the same address")
      assert(csrMaster.read(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_state"), 1).bytesToBigInt == 1, "entry waiting for ARP is not in `incomplete` state")

      // update entry and resend packet
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), ipDst.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), macDst.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), arpReq.neighTblIdx.toBytesLE)
      reqServed = true
    })

    // enable one thread on worker 0
    val thr = mkRandomProc(1).threads.head
    workerCore(0).switchToThread(thr, csrMaster)
    val tid = thr.tid

    // send one IP packet without programming the neighbor table first
    val pld = ipPkt.getPayload.getRawData.toList
    val desc = TxIpCmdSim(pld.length, ipDst, ipPkt.getHeader.getProtocol.value.toInt)
    txSendSingle(dcsMaster, desc, pld, tid)

    waitUntil(reqServed)

    var checked = false
    fork {
      val data = axisSlave.recv()
      val expected = pkt.getRawData.toList
      check(expected, data)
      println(s"Packet received from TX interface and validated")
      checked = true
    }

    // send packet again, receive on AXIS
    txSendSingle(dcsMaster, desc, pld, tid)
    waitUntil(checked)
  }
}
