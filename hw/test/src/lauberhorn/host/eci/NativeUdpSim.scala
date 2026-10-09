package lauberhorn.host.eci

import jsteward.blocks.misc.sim.IntRicherEndianAware
import lauberhorn.Global._
import lauberhorn.sim._
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, UdpPacket}
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

class NativeUdpSim extends NicSim {
  testWithDB("native-udp-lengths-checksums-ownership-and-segmentation", timeout = 500000000)(Rx, Tx) { implicit dut =>
    val (csr, input, output, dcs) = commonDutSetup(1000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val proc = mkRandomProc(1)
    proc.enableAt(1, csr)
    val regs = ALLOC.readBack("UdpDecoder")
    def listen(port: Int, handle: Int, pid: Int, slot: Int): Unit = {
      csr.write(regs("ctrl", "listen_port"), port.toBytesLE)
      csr.write(regs("ctrl", "listen_nextProto"), 3.toBytesLE)
      csr.write(regs("ctrl", "listen_pid"), pid.toBytesLE)
      csr.write(regs("ctrl", "listen_endpoint"), handle.toBytesLE)
      csr.write(regs("ctrl", "listen_idx"), slot.toBytesLE)
    }
    listen(11211, 42, proc.pid, 0)
    listen(11212, 43, proc.pid + 1, 1)
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)
    def packet(bytes: List[Byte]): EthernetPacket = {
      val random = udpPacket(32123, 11211, rawPayloadBuilder(bytes.toArray))
      val udp = random.get(classOf[UdpPacket]).getBuilder.srcAddr(clientIp).dstAddr(serverIp)
        .correctChecksumAtBuild(true)
      val ip = random.get(classOf[IpV4Packet]).getBuilder.srcAddr(clientIp).dstAddr(serverIp)
        .correctChecksumAtBuild(true).payloadBuilder(udp)
      random.getBuilder.srcAddr(clientMac).dstAddr(serverMac).payloadBuilder(ip).build()
    }
    val replies = mutable.ArrayBuffer[List[Byte]]()
    fork { while (true) replies += output.recv() }
    var expectedDrops = 0
    def ipChecksum(bytes: List[Byte]): List[Byte] = {
      val clear = bytes.updated(24, 0.toByte).updated(25, 0.toByte)
      val sum = clear.slice(14, 34).grouped(2).map(w => ((w.head & 255) << 8) | (w(1) & 255)).sum
      val first = (sum & 65535) + (sum >>> 16)
      val checksum = ~((first & 65535) + (first >>> 16)) & 65535
      clear.updated(24, (checksum >>> 8).toByte).updated(25, checksum.toByte)
    }
    for ((size, padding) <- Seq((0, false), (0, true), (1, true), (7, true),
        (16, true), (17, true), (18, true), (19, true), (21, true), (22, true),
        (23, true), (63, true), (64, true), (65, true), (1024, true), (1472, true))) {
      val bytes = List.tabulate(size)(i => (i * 7).toByte)
      val wire = packet(bytes).getBuilder.paddingAtBuild(padding).build().getRawData.toList
      // Corrupt UDP checksum, IP checksum, fragmentation and lengths. None may
      // escape into host scheduling; the following valid packet checks recovery.
      if (size == 65) {
        for (offset <- Seq(24, 40, 38)) {
          input.send(wire.updated(offset, (wire(offset) ^ 1).toByte))
          expectedDrops += 1
        }
        // A checksum-correct IPv4 fragment, short UDP length and oversized UDP.
        input.send(ipChecksum(wire.updated(20, 0x20.toByte)))
        input.send(wire.updated(38, 0.toByte).updated(39, 4.toByte))
        input.send(packet(List.fill(1473)(1.toByte)).getRawData.toList)
        expectedDrops += 3
      }
      println(s"Native UDP test size=$size")
      // IPv4 permits a zero UDP checksum; check that path as well.
      input.send(if (size == 7) wire.updated(40, 0.toByte).updated(41, 0.toByte) else wire)
      sleepCycles(1000)
      val dropped = csr.read(ALLOC.readBack("NativeUdpDecoder")("stat", "dropCount"), 8).bytesToBigInt
      val reason = csr.read(ALLOC.readBack("NativeUdpDecoder")("stat", "lastReject"), 8).bytesToBigInt
      println(s"Native UDP rejected=$dropped reason=$reason")
      assert(dropped == expectedDrops, s"unexpected native UDP drop count for size $size")
      val worker = workerCore(0)
      worker.waitUser()
      val tid = worker.currThread.get.tid
      val (desc, payload) = tryReadPacketDesc(dcs, tid).result.get
      val udp = desc.asInstanceOf[RxNativeUdpSim]
      assert(udp.len == size && udp.endpoint == 42 && udp.sport == 32123 && udp.dport == 11211)
      assert(udp.saddr == BigInt(1, clientIp.getAddress) && udp.daddr == BigInt(1, serverIp.getAddress))
      if (size > 0) check(bytes, readPayload(dcs, payload, size))
      // A valid handle from another domain and an unknown handle must reject.
      txSendSingle(dcs, TxNativeUdpSim(size, 43, udp.saddr, udp.sport), bytes, tid, expectedStatus = 3)
      txSendSingle(dcs, TxNativeUdpSim(size, 99, udp.saddr, udp.sport), bytes, tid, expectedStatus = 3)
      if (size == 1472) txSendSingle(dcs, TxNativeUdpSim(1473, 42, udp.saddr, udp.sport), bytes :+ 0.toByte, tid, expectedStatus = 3)
      // Two replies from a single receive exercise stateless response ownership.
      val before = replies.size
      for (_ <- 0 until 2) txSendSingle(dcs, TxNativeUdpSim(size, 42, udp.saddr, udp.sport), bytes, tid)
      waitUntil(replies.size == before + 2)
      for (raw <- replies.drop(before)) {
        // The CMAC appends FCS but does not pad undersized Ethernet frames.
        // STORED\r\n plus memcached framing is only 16 UDP payload bytes.
        assert(raw.length == scala.math.max(60, 42 + size), s"invalid Ethernet frame length ${raw.length} for UDP payload $size")
        assert(raw.drop(42 + size).forall(_ == 0), "Ethernet padding must not disclose stale data")
        val result = EthernetPacket.newPacket(raw.toArray, 0, raw.length).get(classOf[UdpPacket])
        assert(result.getHeader.getSrcPort.valueAsInt() == 11211)
        assert(result.getHeader.getDstPort.valueAsInt() == 32123)
        val data = Option(result.getPayload).map(_.getRawData.toList).getOrElse(Nil)
        check(bytes, data)
      }
    }
  }
}
