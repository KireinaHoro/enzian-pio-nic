package lauberhorn.host.eci

import jsteward.blocks.misc.sim.IntRicherEndianAware
import lauberhorn.Global._
import lauberhorn.sim._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

class NativeTcpSim extends NicSim {
  testWithDB("native-tcp-all-segments-validation-and-bypass", timeout = 1500000000L)(Rx, Tx) { implicit dut =>
    val (csr, input, output, dcs) = commonDutSetup(1000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val proc = mkRandomProc(1)
    proc.enableAt(1, csr)
    val regs = ALLOC.readBack("NativeTcpDecoder")
    csr.write(regs("ctrl", "listen_enabled"), 1.toBytesLE)
    csr.write(regs("ctrl", "listen_port"), 11211.toBytesLE)
    csr.write(regs("ctrl", "listen_pid"), proc.pid.toBytesLE)
    csr.write(regs("ctrl", "listen_endpoint"), 42.toBytesLE)
    csr.write(regs("ctrl", "listen_idx"), 0.toBytesLE)
    csr.write(regs("ctrl", "listen_port"), 11212.toBytesLE)
    csr.write(regs("ctrl", "listen_pid"), (proc.pid + 1).toBytesLE)
    csr.write(regs("ctrl", "listen_endpoint"), 43.toBytesLE)
    csr.write(regs("ctrl", "listen_idx"), 1.toBytesLE)
    val (clientIp, _, clientMac) = enzianIpMacAddrs(0)
    val (serverIp, _, serverMac) = enzianIpMacAddrs(1)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE)
    csr.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)
    val replies = mutable.ArrayBuffer[List[Byte]]()
    fork { while (true) replies += output.recv() }
    def put16(bytes: Array[Byte], offset: Int, value: Int): Unit = {
      bytes(offset) = (value >>> 8).toByte; bytes(offset + 1) = value.toByte
    }
    def checksum(bytes: Seq[Byte]): Int = {
      val words = bytes.padTo((bytes.length + 1) & ~1, 0.toByte).grouped(2)
      var sum = words.map(w => ((w.head & 255) << 8) | (w(1) & 255)).sum
      while (sum > 65535) sum = (sum & 65535) + (sum >>> 16)
      ~sum & 65535
    }
    def fixIp(bytes: Array[Byte]): Unit = {
      put16(bytes, 24, 0)
      put16(bytes, 24, checksum(bytes.slice(14, 14 + (bytes(14) & 15) * 4).toSeq))
    }
    def fixTcp(bytes: Array[Byte]): Unit = {
      val length = ((bytes(16) & 255) << 8 | (bytes(17) & 255)) - 20
      put16(bytes, 50, 0)
      val pseudo = bytes.slice(26, 34).toSeq ++
        Seq(0.toByte, 6.toByte, (length >>> 8).toByte, length.toByte)
      put16(bytes, 50, checksum(pseudo ++ bytes.slice(34, 34 + length)))
    }
    def packet(payload: Int, flags: Int = 16, header: Int = 20, port: Int = 11211): List[Byte] = {
      val tcpLength = payload + header
      val bytes = Array.fill[Byte](14 + 20 + tcpLength)(0)
      Array.copy(serverMac.getAddress, 0, bytes, 0, 6)
      Array.copy(clientMac.getAddress, 0, bytes, 6, 6)
      put16(bytes, 12, 0x0800)
      bytes(14) = 0x45; put16(bytes, 16, 20 + tcpLength)
      put16(bytes, 20, 0x4000); bytes(22) = 64; bytes(23) = 6
      Array.copy(clientIp.getAddress, 0, bytes, 26, 4)
      Array.copy(serverIp.getAddress, 0, bytes, 30, 4)
      put16(bytes, 34, 32123); put16(bytes, 36, port)
      bytes(41) = 1; bytes(45) = 2
      bytes(46) = ((header / 4) << 4).toByte; bytes(47) = flags.toByte
      put16(bytes, 48, 4096)
      for (i <- 20 until header) bytes(34 + i) = 1 // NOP options, retained verbatim
      for (i <- 0 until payload) bytes(34 + header + i) = (i * 7 + flags).toByte
      val pseudo = clientIp.getAddress.toSeq ++ serverIp.getAddress.toSeq ++
        Seq(0.toByte, 6.toByte, (tcpLength >>> 8).toByte, tcpLength.toByte)
      put16(bytes, 50, checksum(pseudo ++ bytes.drop(34)))
      fixIp(bytes)
      bytes.toList.padTo(60, 0.toByte)
    }
    val bypass = mutable.ArrayBuffer[List[Byte]]()
    setBypassCore(() => {
      val (desc, body) = tryReadPacketDesc(dcs, -1).result.get
      assert(desc.isInstanceOf[RxBypassCtrlInfoSim])
      bypass += readPayload(dcs, body, desc.len)
    })
    // Unmatched TCP, IPv4 fragments and options must preserve the full frame.
    val unmatched = packet(11, port = 11213)
    val fragment = packet(11).toArray
    put16(fragment, 20, 0x2000); fixIp(fragment)
    val original = packet(11).toArray
    val options = original.take(34) ++ Array[Byte](1, 1, 1, 1) ++ original.drop(34)
    options(14) = 0x46; put16(options, 16, 55); fixIp(options)
    for (wire <- Seq(unmatched, fragment.toList, options.toList)) {
      val count = bypass.size
      input.send(wire)
      waitUntil(bypass.size == count + 1)
      check(wire, bypass.last)
    }
    assert(proc.threads.forall(_.runningOn.isEmpty))
    var drops = 0
    for ((size, flags, header) <- Seq((0, 2, 20), (0, 16, 20), (0, 17, 20), (0, 4, 20),
        (1, 24, 20), (23, 24, 20), (24, 24, 20), (25, 24, 20), (64, 24, 24),
        (129, 24, 60), (1460, 24, 20))) {
      val wire = packet(size, flags, header)
      if (size == 64) {
        val badIp = wire.updated(24, (wire(24) ^ 1).toByte)
        val badTcp = wire.updated(50, (wire(50) ^ 1).toByte)
        val shortHeader = wire.toArray
        shortHeader(46) = 0x40.toByte; fixTcp(shortHeader)
        val shortPacket = wire.dropRight(10)
        val shortLength = wire.toArray
        put16(shortLength, 16, 39); fixIp(shortLength)
        for (bad <- Seq(badIp, badTcp, shortHeader.toList, shortPacket, shortLength.toList, packet(1461))) {
          input.send(bad); drops += 1
        }
      }
      input.send(wire)
      sleepCycles(1000)
      assert(csr.read(regs("stat", "dropCount"), 8).bytesToBigInt == drops)
      workerCore(0).waitUser()
      val tid = workerCore(0).currThread.get.tid
      val (desc, body) = tryReadPacketDesc(dcs, tid).result.get
      assert(desc == RxNativeTcpSim(20 + header + size, 42))
      check(wire.drop(14).take(desc.len), readPayload(dcs, body, desc.len))
      val response = wire.toArray
      Array.copy(serverIp.getAddress, 0, response, 26, 4)
      Array.copy(clientIp.getAddress, 0, response, 30, 4)
      put16(response, 34, 11211); put16(response, 36, 32123)
      fixTcp(response); fixIp(response)
      val expected = response.slice(14, 14 + desc.len).toList
      // Deliberately leave the input checksum wrong: hardware must complete it.
      put16(response, 50, 0x1234)
      val tx = response.slice(14, 14 + desc.len).toList
      txSendSingle(dcs, TxNativeTcpSim(desc.len, 43), tx, tid, expectedStatus = 3)
      txSendSingle(dcs, TxNativeTcpSim(desc.len, 99), tx, tid, expectedStatus = 3)
      val before = replies.size
      if (size == 64) {
        // Descriptor acceptance releases the buffer; bad packet contents are
        // subsequently dropped by the encoder, before any wire output.
        txSendSingle(dcs, TxNativeTcpSim(desc.len, 42), tx.updated(12, 0.toByte), tid)
        txSendSingle(dcs, TxNativeTcpSim(desc.len, 42), tx.updated(20, 0.toByte), tid)
      }
      for (overflow <- Seq(false, true)) {
        txSendSingle(dcs, TxNativeTcpSim(desc.len, 42), tx, tid,
          forceOverflow = overflow, reservationCapacity = if (overflow) 1500 else desc.len,
          dirtyReservation = true)
      }
      waitUntil(replies.size == before + 2)
      sleepCycles(100)
      assert(replies.size == before + 2)
      replies.drop(before).foreach { raw =>
        assert(raw.length == scala.math.max(60, 14 + desc.len))
        check(clientMac.getAddress.toList, raw.take(6))
        check(expected, raw.slice(14, 14 + desc.len))
        assert(raw.drop(14 + desc.len).forall(_ == 0))
      }
    }
    val tid = workerCore(0).currThread.get.tid
    assert(tryReadPacketDesc(dcs, tid, maxTries = 1).result.isEmpty)
  }
}
