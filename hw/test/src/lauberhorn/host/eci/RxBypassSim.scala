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

class RxBypassSim extends NicSim {
  /** Set up the DUT and create the DCS and AXI-Lite simulation bus masters and arm an IRQ handler.
    * Calls into [[GenericHostCPUModel]] to simulate software state changes in the kernel.
    */
  /** In addition to [[commonDutSetup]], assert that the TX interface is inactive during RX-only tests.  */
  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }
    
    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)

    // enable rx already for normal tests -- there is a separate test to see
    // if the rx drop all switch is effective
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)

    (csrMaster, axisMaster, dcsMaster)
  }

  def checkSingle(expectedPacket: Packet, expectedProto: PacketType, gotPacket: List[Byte], gotDesc: BypassCtrlInfoSim): Boolean = {
    Try {
      assert(expectedProto.id == gotDesc.packetType, s"proto mismatch: expected $expectedProto, got ${PacketType(gotDesc.packetType.toInt)}")
      checkHeader(expectedProto, expectedPacket, gotDesc.hdrP4jPacket)

      // check payload length
      val payload = getPayloadAndCheckLen(expectedPacket, expectedProto, gotDesc.len)

      // we have already checked length, truncate the received data to expected len
      check(payload, gotPacket.take(payload.length))
    } match {
      case Failure(a: AssertionError) =>
        println(s"Check single packet failed with assertion $a")
        false
      case Failure(e: TestFailedException) =>
        println(s"Check single packet failed with $e")
        false
      case Success(_) =>
        true
    }
  }

  /** read back one single bypass packet */
  def rxSingle(dcsMaster: DcsAppMaster, maxRetries: Int)(implicit dut: NicEngine): Option[(BypassCtrlInfoSim, List[Byte])] =
    tryReadPacketDesc(dcsMaster, tid = -1, maxTries = maxRetries + 1).result.map { case (info, pldDesc) =>
      println(s"Received status register: $info")
      assert(info.isInstanceOf[BypassCtrlInfoSim], "should only receive bypass packet!")

      val bypassDesc = info.asInstanceOf[BypassCtrlInfoSim]

      (bypassDesc, readPayload(dcsMaster, pldDesc, bypassDesc.len))
    }

  /** test reading one bypass packet; when called multiple times, this checks in a blocking fashion */
  def rxTestSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, packet: Packet, proto: PacketType, maxRetries: Int)(implicit dut: NicEngine): Unit = {
    // set bypass handler
    var checked = false

    setBypassCore(() => {
      // read memory and check data
      val (desc, data) = rxSingle(dcsMaster, maxRetries).get
      assert(checkSingle(packet, proto, data, desc), "failed to receive single packet")

      // TODO: check performance counters

      println(s"Successfully received packet")
      checked = true

      // packet will be acknowledged by reading next packet
    })

    fork {
      randomSleep(200)

      val toSend = packet.getRawData.toList
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    waitUntil(checked)
  }

  /** test scanning a range of lengths of packets to send and check */
  def rxTestRange(csrMaster: AxiLite4Master, axisMaster: Axi4StreamMaster, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, maxRetries: Int)(implicit dut: NicEngine) = {
    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until simRandom.between(25, 50) foreach { _ =>
        import PacketType._
        val (packet, proto) = randomPacket(size, randomizeLen = false)(Ethernet, Ip, Udp)
        rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxRetries)
      }
    }

    // TODO: check DCS master cacheline state
  }

  testWithDB("scan-sizes")(Slow, Rx) { implicit dut =>
    // set a large enough rx block cycles, such that there shouldn't be a need to retry
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(5000000) // 20 ms @ 250 MHz
    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 9618, 64, maxRetries = 0)
  }
  
  testWithDB("drop-when-uninitialized")(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    
    // enable rx drop all again
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 1.toBytesLE)

    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)
    
    // send a packet
    val (packet, proto) = randomPacket(512, randomizeLen = false)(Ethernet, Ip, Udp)
    axisMaster.send(packet.getRawData.toList)
    
    // try receive -- no packet should come
    (0 until 10).foreach { _ =>
      assert(tryReadPacketDesc(dcsMaster, -1, 5).result.isEmpty, "should not have packet on standby yet")
    }
  }

  testWithDB("simple")(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 256, 64, maxRetries = 5)
  }

  testWithDB("multicast")(Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)

    // leave promisc mode off -- we have a IP and MAC address by default for zuestoll01
    // send one IGMP membership qeury to test both IP and Ethernet filtering
    val ipBuilder = (new IpV4Packet.Builder)
      .version(IpVersion.IPV4)
      .protocol(IpNumber.IGMP)
      .tos(IpV4Rfc1349Tos.newInstance(0))
      .ttl(simRandom.nextInt().toByte)
      .srcAddr(InetAddress.getByAddress(simRandom.nextBytes(4)).asInstanceOf[Inet4Address])
      .dstAddr(InetAddress.getByName("224.0.0.1").asInstanceOf[Inet4Address])
      .correctLengthAtBuild(true)
      .correctChecksumAtBuild(true)
      .payloadBuilder(rawPayloadBuilder(hexToBytesBE("1164ee9b00000000").toArray))

    val ethernetBuilder = (new EthernetPacket.Builder)
      .srcAddr(MacAddress.getByAddress(simRandom.nextBytes(6)))
      .dstAddr(MacAddress.getByName("01:00:5e:00:00:01"))
      .`type`(EtherType.IPV4)
      .paddingAtBuild(true)
      .payloadBuilder(ipBuilder)

    val ethernetPacket = ethernetBuilder.build()
    val proto = PacketType.Ip // we should receive this as a bypass IP packet

    rxTestSimple(dcsMaster, axisMaster, ethernetPacket, proto, maxRetries = 5)
  }
}