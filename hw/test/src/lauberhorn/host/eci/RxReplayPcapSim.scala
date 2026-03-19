package lauberhorn.host.eci

import jsteward.blocks.eci.sim.DcsAppMaster
import jsteward.blocks.misc.sim.IntRicherEndianAware
import lauberhorn.Global.ALLOC
import lauberhorn.NicEngine
import lauberhorn.sim.PacketType.{Ethernet, Ip, PacketType, Udp}
import lauberhorn.sim.{Rx, enzianIpMacAddrs, pcap4jPacketToType, randomPacket}
import org.pcap4j.core.{PcapDumper, Pcaps}
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, Packet}
import org.pcap4j.packet.namednumber.DataLinkType
import spinal.core.sim.{fork, simRandom, waitUntil}
import spinal.lib.BytesRicher
import spinal.lib.bus.amba4.axis.sim.Axi4StreamMaster

import java.net.Inet4Address
import scala.collection.mutable

class RxReplayPcapSim extends NicSim with RxHelpers {
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
    val testName = s"pipelined-$name${if (!doPromisc) "-nopromisc" else ""}"
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
}
