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

class AllNicSim extends NicSim
  with OncRpcSuiteFactory {
  /** Send one descriptor, optionally with a tail payload. */
  def txSendSingle(dcsMaster: DcsAppMaster, txDesc: EciHostCtrlInfoSim, toSend: List[Byte], tid: Int, skipCS: Boolean = false)(implicit dut: NicEngine): Unit = {
    val etd = getEciThreadData(tid)
    val coreBase = etd.baseAddr
    def clAddr = etd.txNextCl * 0x80 + ECI_TX_BASE.get + coreBase

    if (!skipCS) {
      // since we didn't implement killing a process yet, we should never get descheduled during TX
      val descheduled = !enterCriticalSection(dcsMaster, tid)
      assert(!descheduled, "should never get descheduled during TX")
    }

    etd.log(f"sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, txDesc.toTxDesc)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      val overflowAddr = ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET + coreBase
      dcsMaster.write(overflowAddr, toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    etd.log(f"sent packet at $clAddr%#x")

    etd.flipTx()
    dcsMaster.read(clAddr, 1)

    if (!skipCS) {
      exitCriticalSection(dcsMaster, tid)
    }
  }

  /** Test sending one single packet as bypass on a specific thread.  Also checks if the expected packet appears on the
   * outgoing AXI-Stream interface.
   *
   * Sends packet through the bypass interface, which only takes destination addresses.  Checks the output against
   * the full packet.
   *
   * This function pre-programs the neighbor table entry!  Test "tx-neighbor-resolve-request" checks if a bypass
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
    val testName = s"tx-scan-sizes-core$cid"
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

  testWithDB("tx-bypass-simple")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("tx-bypass-simple") / "packets-expecting.pcap").toString)

    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

    txTestRange(axisSlave, dcsMaster, csrMaster, 64, 256, 64, -1)
  }

  testWithDB("tx-voluntary-inv")(Tx) { implicit dut =>
    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

    // write 0x8000 and drop, simulating a R12 -> R23 -> V31d
    dcsMaster.write(0x8000, 0.toBytesLE)
  }

  // Test sending IPv6 packets (produced by Linux kernel)
  testWithDB("tx-icmp6")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("tx-icmp6") / "packets-expecting.pcap").toString)

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
    val testName = s"tx-all-cores-${if (!doVoluntaryInv) "no-" else ""}inv"
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

  testWithDB("tx-neighbor-resolve-request")(Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("tx-neighbor-resolve-request") / "packets-expecting.pcap").toString)

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

  type TaggedPkt = (Packet, PacketType)
  type ToCheckPkts = mutable.ArrayDeque[TaggedPkt]
  type NextPkt = () => Option[TaggedPkt]

  def checkRxPacket(toCheck: ToCheckPkts, desc: BypassCtrlInfoSim, data: List[Byte]): Unit = {
    // XXX: occasionally the packet received is out of order
    //      e.g. receiving Ethernet after Udp.  Udp takes longer to go through the pipeline,
    //      resulting in Ethernet packet arriving first
    toCheck.view.map { case (p, pr) => checkSingle(p, pr, data, desc) }
      .zipWithIndex.dropWhile(!_._1).headOption match {
      case Some((_, idx)) =>
        println(s"Found expected packet as #$idx in queue")
        toCheck.remove(idx)
      case None => fail("failed to find received packet in expect queue")
    }
  }

  def bypassStandardSend(axisMaster: Axi4StreamMaster, toCheck: ToCheckPkts, nextPacket: NextPkt, dumper: Option[PcapDumper], noSleep: Boolean = false)(implicit dut: NicEngine): (() => Boolean, () => Int) = {
    var numPackets = 0
    var doneSending = false
    fork {
      while (!doneSending) nextPacket() match {
        case Some((packet, proto)) =>
          dumper.foreach(_.dump(packet))
          dumper.foreach(_.flush())

          val toSend = packet.getRawData.toList
          axisMaster.send(toSend)
          println(s"Sent packet #$numPackets of length ${toSend.length}")

          toCheck.append((packet, proto))

          numPackets += 1

          // Add random delay to trigger more paths
          if (!noSleep) randomSleep(2000)
        case None =>
          doneSending = true
      }
    }

    (
      () => doneSending,
      () => numPackets
    )
  }

  def bypassStandardRecv(dcsMaster: DcsAppMaster, toCheck: ToCheckPkts)(implicit dut: NicEngine): () => Int = {
    var received = 0
    setBypassCore { () =>
      // poll loop on interrupt, same as in kernel
      // when bypass interrupt happens, there must be a descriptor to fetch
      var canHaveMoreData = true
      while (canHaveMoreData) {
        rxSingle(dcsMaster, maxRetries = 0) match {
          case Some((desc, data)) =>
            checkRxPacket(toCheck, desc, data)
            println(s"Received packet #$received")
            received += 1
          case None =>
            println(s"Received NACK, finishing polling loop")
            canHaveMoreData = false
        }
      }
    }

    () => received
  }

  def rxTestPipelined(name: String, nextPacket: () => Option[(Packet, PacketType)],
      savePackets: Boolean = false,
      doPromisc: Boolean = true) = {
  val testName = s"rx-bypass-pipelined-$name${if (!doPromisc) "-nopromisc" else ""}"
  testWithDB(testName)(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)
    val (myIp, prefixLen, myMac) = enzianIpMacAddrs(14) // pcap traces are recorded on zuestoll14

    if (doPromisc) {
      // enable promisc mode
      csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)
    } else {
      // program with zuestoll14 MAC/IP addresses for trace replay
      csrMaster.write(ALLOC.readBack("IpDecoder")("ctrl", "ipAddress"), myIp.getAddress.toList)
      csrMaster.write(ALLOC.readBack("EthernetDecoder")("ctrl", "macAddress"), myMac.getAddress.toList)
    }

    val toCheck = new ToCheckPkts
    val dumper = if (savePackets) {
      Some(Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets.pcap").toString))
    } else None

    def filtered() = if (!doPromisc) {
      def addrToInt(addr: Inet4Address) = addr.getAddress.map(_.toInt).reduceLeft { (v, acc: Int) =>
        (acc << 8) + v
      }
      def subnet(addr: Inet4Address) = addrToInt(addr) >> (32 - prefixLen)
      def host(addr: Inet4Address) = addrToInt(addr) & ((1 << (32 - prefixLen)) - 1)

      // unicast packets that do not belong to us will be ignored
      toCheck.count { case (pkt, _) =>
        val ethDst = pkt.get(classOf[EthernetPacket]).getHeader.getDstAddr
        val macForUs = ethDst == myMac
        var ipForUs = false
        var isMulticast = !ethDst.isUnicast

        if (pkt.contains(classOf[IpV4Packet])) {
          val ipDst = pkt.get(classOf[IpV4Packet]).getHeader.getDstAddr
          val isLocalBroadcast = addrToInt(ipDst) == 0xffffffff
          val isDirectedBroadcast = subnet(ipDst) == subnet(myIp) && host(ipDst) == ((1 << (32 - prefixLen)) - 1)

          isMulticast &= ipDst.isMulticastAddress || isLocalBroadcast || isDirectedBroadcast
          ipForUs = ipDst == myIp
        }

        !(macForUs || ipForUs || isMulticast)
      }
    } else 0

    val received = bypassStandardRecv(dcsMaster, toCheck)
    val (doneSending, sent) = bypassStandardSend(axisMaster, toCheck, nextPacket, dumper)

    waitUntil(doneSending() && received() + filtered() == sent())
  } }

  def loadRandomPackets(numPackets: Int) = {
    var sent = 0
    () => {
      if (sent == numPackets) None else {
        sent += 1
        val len = simRandom.between(64, 1536)
        Some(randomPacket(len)(Ethernet, Ip, Udp))
      }
    }
  }

  def loadPcapForRxTest(name: String) = {
    val projRoot = os.call("git rev-parse --show-toplevel".split(" ")).out.text().stripLineEnd
    val pcapPath = os.Path(projRoot) / "data" / "eci" / "iladata" / name
    val pcapHandle = Pcaps.openOffline(pcapPath.toString)
    () => {
      val packet = pcapHandle.getNextPacket
      if (packet == null) None
      else Some((packet, pcap4jPacketToType(packet.get(classOf[EthernetPacket]))))
    }
  }

  def rxTestOverflow(name: String, nextPacket: () => Option[(Packet, PacketType)],
                     savePackets: Boolean = false,
                    ) = {
  val testName = s"rx-bypass-overflow-$name"
  testWithDB(testName)(Rx) { implicit dut =>
    // flood RX with too many packets, receive full packets and check dropped counter

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)

    // always enable promisc mode for overflow test
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    val toCheck = new ToCheckPkts
    val dumper = if (savePackets) {
      Some(Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets.pcap").toString))
    } else None

    // simulate real flood send
    axisMaster.setFactor(1)
    val (doneSending, sent) = bypassStandardSend(axisMaster, toCheck, nextPacket, dumper, noSleep = true)

    // wait until all packets are sent
    waitUntil(doneSending())

    val received = bypassStandardRecv(dcsMaster, toCheck)

    // periodically check overflow counter
    var done = false
    fork {
      while (!done) {
        val overflowCount = csrMaster.read(ALLOC.readBack("macIf")("stat", "rxMacOverflowCount"), 8).bytesToBigInt
        val ingressCount = csrMaster.read(ALLOC.readBack("macIf")("stat", "rxMacIngressCount"), 8).bytesToBigInt
        val afterCdcCount = csrMaster.read(ALLOC.readBack("macIf")("stat", "rxMacIngressAfterCdcCount"), 8).bytesToBigInt
        val rcvd = received()
        val snd = sent()

        // If RX is locked up, we will be stuck at:
        // - ingress - afterCdc - dropped > 0: number of packets still in FIFO
        // - afterCdc - received > 0: packets decoded but stuck (not sent to host)
        //
        // possible causes:
        // - decoder pipeline locked up:
        //    - hostRx at bypass is not valid (bypassDescMux inputs are not valid)
        //    - Ethernet decoder ingress is stalled
        // - 2F2F message passing locked up:
        //    - hostRx stalled, bypass queue full
        println(s"Sent $snd, received $rcvd, dropped $overflowCount; ingress $ingressCount, afterCdc: $afterCdcCount")

        assert(snd == ingressCount)
        assert(rcvd + overflowCount <= snd)
        done = snd == rcvd + overflowCount

        sleepCycles(2000)
      }
    }

    waitUntil(done)
  }
  }

  rxTestPipelined("random", loadRandomPackets(200), savePackets = true)
  rxTestPipelined("lockup", loadPcapForRxTest("rx-lockup.pcap"))
  rxTestPipelined("lockup-2", loadPcapForRxTest("rx-lockup-2.pcap"))

  rxTestOverflow("random", loadRandomPackets(500), savePackets = true)
  rxTestOverflow("lockup", loadPcapForRxTest("rx-lockup.pcap"))
  rxTestOverflow("lockup-2", loadPcapForRxTest("rx-lockup-2.pcap"))

  rxTestPipelined("random", loadRandomPackets(500), savePackets = true, doPromisc = false)
  rxTestPipelined("lockup", loadPcapForRxTest("rx-lockup.pcap"), doPromisc = false)
  rxTestPipelined("lockup-2", loadPcapForRxTest("rx-lockup-2.pcap"), doPromisc = false)

  testWithDB("rx-bypass-no-repeat")(Rx) { implicit dut =>
    // send one packet, receive twice -- no second packet should arrive
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)
    val maxTries = 5

    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    (0 until 10).foreach { _ =>
      assert(tryReadPacketDesc(dcsMaster, -1, maxTries).result.isEmpty, "should not have packet on standby yet")
    }

    import PacketType._
    val (packet, proto) = randomPacket(512, randomizeLen = false)(Ethernet, Ip, Udp)
    rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxTries + 1)

    assert(tryReadPacketDesc(dcsMaster, -1, maxTries).result.isEmpty, "packet should not be duplicated")
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

  testWithDB("rx-rpc-hol-blocking-free")(Rx) { implicit dut =>
    // This test checks that no HOL-blocking happens between RX of different worker
    // cores.  This is important since if HOL-blocking happens, the 2F2F state machine
    // won't even see a read that's blocked by another stall(-to-NACK) read, triggering
    // timeout for the blocked read easily.

    // Same requirements for wait-freedom apply as in rx-tx-interleave.
    // TODO
  }

  testWithDB("rx-simple-no-promisc")(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)

    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("rx-simple-no-promisc") / "packets.pcap").toString)

    // NOT enabling promisc

    // default values is for zuestoll01
    rxTestSimple(dcsMaster, axisMaster, getIpPacketToEnzian(1, 512), PacketType.Ip, maxRetries = 1)

    // change host ID: address regs are in big endian
    val (ip, _, mac) = enzianIpMacAddrs(2)
    csrMaster.write(ALLOC.readBack("IpDecoder")("ctrl", "ipAddress"), ip.getAddress.toList)
    csrMaster.write(ALLOC.readBack("EthernetDecoder")("ctrl", "macAddress"), mac.getAddress.toList)

    rxTestSimple(dcsMaster, axisMaster, getIpPacketToEnzian(2, 512), PacketType.Ip, maxRetries = 1)
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

  testWithDB("dcs-trace")() { implicit dut =>
    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val traceBuf = dut.host[DcsTraceBuffer].logic.get

    traceBuf.dump #= false
    Seq(eciIf.dcsEven, eciIf.dcsOdd).flatMap(_.tracing).foreach { tp =>
      tp.valid #= false
    }

    commonDutSetup(100)

    // wait until memory is initialized
    sleepCycles(1000)

    Seq(eciIf.dcsEven, eciIf.dcsOdd).flatMap(_.tracing).foreach { tp =>
      var samples = 0
      FlowDriver(tp, dut.clockDomain) { p =>
        if (samples < 200 && simRandom.nextDouble() > 0.8) {
          p.cli     #= samples * 16
          p.action  #= 3
          p.error   #= false
          p.request #= 5
          p.state   #= 2

          samples += 1
          true
        } else false
      }
    }

    sleepCycles(200)

    traceBuf.dump #= true

    sleepCycles(1000)
  }
}
