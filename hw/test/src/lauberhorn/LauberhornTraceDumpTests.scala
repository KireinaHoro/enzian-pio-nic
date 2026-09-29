package lauberhorn

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.axi.{AxiDmaConfig, AxiStreamAligner}
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axis.Axi4StreamConfig
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}
import spinal.lib.sim._
import spinal.lib._

// Include the actual TX aligner: a byte-list sink alone hides illegal holes in TKEEP.
case class TraceDumpAlignedHarness(axisConfig: Axi4StreamConfig, dmaConfig: AxiDmaConfig,
                                   axiBase: BigInt) extends Component {
  val responder = LauberhornTraceDump(axisConfig, dmaConfig, axiBase)
  val aligner = AxiStreamAligner(axisConfig)
  val rx = slave(cloneOf(responder.rx))
  val tx = master(cloneOf(responder.tx))
  val cfg = in(cloneOf(responder.cfg))
  val dumpOverNetwork = in(Bool())
  val status = in(cloneOf(responder.status))
  val readDesc = master(cloneOf(responder.readDesc))
  val readData = slave(cloneOf(responder.readData))
  val readDescStatus = slave(cloneOf(responder.readDescStatus))
  val debug = out(cloneOf(responder.debug))
  responder.rx << rx
  responder.tx >> aligner.io.input
  aligner.io.output >> tx
  responder.cfg := cfg
  responder.dumpOverNetwork := dumpOverNetwork
  responder.status := status
  readDesc << responder.readDesc
  responder.readData << readData
  responder.readDescStatus << readDescStatus
  debug := responder.debug
}

class LauberhornTraceDumpTests extends DutSimFunSuite[TraceDumpAlignedHarness] {
  val axisConfig = Axi4StreamConfig(dataWidth = 64, useKeep = true, useLast = true)
  val axiConfig = Axi4Config(
    addressWidth = 35,
    dataWidth = 512,
    idWidth = 7,
    useQos = false,
    useRegion = false,
  )
  val dmaConfig = AxiDmaConfig(
    axiConfig = axiConfig,
    axisConfig = axisConfig,
    axiMaxBurstLen = 16,
    lenWidth = 16,
    tagWidth = 1,
  )
  val axiBase = BigInt(0x1000)

  val dut = SimConfig
    .withConfig(SpinalConfig())
    .withFstWave
    .withVerilator
    .allOptimisation
    .compile(TraceDumpAlignedHarness(axisConfig, dmaConfig, axiBase))

  def be16(value: Int): List[Byte] =
    List(((value >> 8) & 0xff).toByte, (value & 0xff).toByte)

  def be32(value: Long): List[Byte] =
    (3 to 0 by -1).map(shift => ((value >> (shift * 8)) & 0xff).toByte).toList

  def be64(value: Long): List[Byte] =
    (7 to 0 by -1).map(shift => ((value >> (shift * 8)) & 0xff).toByte).toList

  def bytes(hex: String): List[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toList

  val fpgaMac = bytes("0c53310301cc")
  val hostMac = bytes("001122334455")
  val fpgaIp = bytes("c0a881cc")
  val hostIp = bytes("c0a88164")
  val srcPort = 44444
  val dstPort = 55555

  def request(seq: Long, offset: Long, length: Int, requestDstPort: Int = dstPort): List[Byte] = {
    val body =
      bytes("4c485452") ++ // LHTR
        List(1.toByte, 1.toByte, 0.toByte, 0.toByte) ++
        be32(seq) ++
        be64(offset) ++
        be32(length)
    val udpLen = 8 + body.length
    val ipLen = 20 + udpLen
    fpgaMac ++ hostMac ++ bytes("0800") ++
      bytes("4500") ++ be16(ipLen) ++ bytes("0000400040110000") ++ hostIp ++ fpgaIp ++
      be16(srcPort) ++ be16(requestDstPort) ++ be16(udpLen) ++ bytes("0000") ++ body
  }

  def arpRequest(): List[Byte] =
    bytes("ffffffffffff") ++ hostMac ++ bytes("0806") ++
      bytes("0001080006040001") ++ hostMac ++ hostIp ++
      bytes("000000000000") ++ fpgaIp

  def setup(dut: TraceDumpAlignedHarness) = {
    SimTimeout(40000)
    dut.clockDomain.forkStimulus(period = 4)

    dut.cfg.localMacOverrideValid #= false
    dut.cfg.localMacOverride #= 0
    dut.cfg.localIpOverrideValid #= false
    dut.cfg.localIpOverride #= 0
    dut.cfg.listenUdpPortOverrideValid #= false
    dut.cfg.listenUdpPortOverride #= 0
    dut.cfg.gatewayMacOverrideValid #= false
    dut.cfg.gatewayMacOverride #= 0
    dut.cfg.dumpServerIpOverrideValid #= false
    dut.cfg.dumpServerIpOverride #= 0
    dut.cfg.dumpServerUdpPortOverrideValid #= false
    dut.cfg.dumpServerUdpPortOverride #= 0
    dut.dumpOverNetwork #= false
    dut.status.writeSlot #= 7
    dut.status.wrapped #= false
    dut.status.sampleLost #= false
    dut.status.dmaError #= false
    dut.readDesc.ready #= true
    dut.readData.valid #= false
    dut.readDescStatus.valid #= false
    dut.readDescStatus.payload.error #= 0
    dut.readDescStatus.payload.tag #= 0

    val rx = Axi4StreamMaster(dut.rx, dut.clockDomain)
    val tx = Axi4StreamSlave(dut.tx, dut.clockDomain)
    val dma = Axi4StreamMaster(dut.readData, dut.clockDomain)
    (rx, tx, dma)
  }

  test("issues DMA read and sends UDP response with default destination") { dut =>
    val (rx, tx, dma) = setup(dut)
    val seq = 0x10203040L
    val offset = 0x80L
    val length = 64
    val payload = (0 until length).map(_.toByte).toList

    fork {
      waitUntil(dut.readDesc.valid.toBoolean)
      assert(dut.readDesc.payload.payload.addr.toBigInt == axiBase + offset)
      assert(dut.readDesc.payload.payload.len.toBigInt == length)
      dma.send(payload)
    }

    rx.send(request(seq, offset, length))
    val response = tx.recv()

    assert(response.slice(0, 6) == bytes("f4521463a091"))
    assert(response.slice(6, 12) == fpgaMac)
    assert(response.slice(12, 14) == bytes("0800"))
    assert(response.slice(16, 18) == be16(20 + 8 + LauberhornTraceDump.ResponseHeaderBytes + length))
    assert(response.slice(26, 30) == fpgaIp)
    assert(response.slice(30, 34) == hostIp)
    assert(response.slice(36, 38) == be16(srcPort))
    assert(response.slice(42, 46) == bytes("4c485444"))
    assert(response(47) == LauberhornTraceDump.StatusOk.toByte)
    assert(response.slice(50, 54) == be32(seq))
    assert(response.slice(54, 62) == be64(offset))
    assert(response.slice(62, 64) == be16(length))
    assert(response.drop(42 + LauberhornTraceDump.ResponseHeaderBytes) == payload)
  }

  test("aligned TX preserves data across short and maximum DDR replies") { dut =>
    val (rx, tx, dma) = setup(dut)
    for ((length, index) <- Seq(64, 128, 1408).zipWithIndex) {
      val payload = (0 until length).map(i => ((i * 29 + index) & 0xff).toByte).toList
      fork {
        waitUntil(dut.readDesc.valid.toBoolean)
        assert(dut.readDesc.payload.payload.len.toBigInt == length)
        dma.send(payload)
      }
      rx.send(request(100 + index, 0, length))
      val response = tx.recv()
      assert(response.length == 42 + LauberhornTraceDump.ResponseHeaderBytes + length)
      assert(response.drop(42 + LauberhornTraceDump.ResponseHeaderBytes) == payload)
    }
  }

  test("answers ARP requests for the trace responder IP") { dut =>
    val (rx, tx, _dma) = setup(dut)

    rx.send(arpRequest())
    val response = tx.recv()

    assert(response.length == 60)
    assert(response.drop(42) == List.fill(18)(0.toByte))
    assert(response.slice(0, 6) == hostMac)
    assert(response.slice(6, 12) == fpgaMac)
    assert(response.slice(12, 14) == bytes("0806"))
    assert(response.slice(20, 22) == bytes("0002"))
    assert(response.slice(22, 28) == fpgaMac)
    assert(response.slice(28, 32) == fpgaIp)
    assert(response.slice(32, 38) == hostMac)
    assert(response.slice(38, 42) == hostIp)
  }

  test("status query replies without issuing DMA") { dut =>
    val (rx, tx, _dma) = setup(dut)

    fork {
      dut.clockDomain.waitSampling(80)
      assert(!dut.readDesc.valid.toBoolean)
    }

    rx.send(request(3, 0, 0))
    val response = tx.recv()
    assert(response.slice(42, 46) == bytes("4c485444"))
    assert(response(47) == LauberhornTraceDump.StatusOk.toByte)
    assert(response.slice(62, 64) == be16(0))
  }

  test("accepts UDP commands after an Ethernet-padded ARP request") { dut =>
    val (rx, tx, _dma) = setup(dut)

    def exchange(frame: List[Byte]): List[Byte] = {
      var reply: Option[List[Byte]] = None
      // ARP can reply before its trailing padding has finished arriving.
      // The slave drops unqueued responses, so arm it before sending.
      tx.recvCB() { data => reply = Some(data) }
      rx.send(frame)
      waitUntil(reply.nonEmpty)
      reply.get
    }

    for (frameBytes <- Seq(42, 60, 128)) {
      val arpReply = exchange(arpRequest().padTo(frameBytes, 0xa5.toByte))
      assert(arpReply.length == 60)
      assert(arpReply.drop(42) == List.fill(18)(0.toByte))
      assert(arpReply.slice(12, 14) == bytes("0806"))
      assert(arpReply.slice(20, 22) == bytes("0002"))

      val response = exchange(request(frameBytes, 0, 0))
      assert(response.slice(42, 46) == bytes("4c485444"))
      assert(response(47) == LauberhornTraceDump.StatusOk.toByte)
      assert(response.slice(50, 54) == be32(frameBytes))
    }
  }

  test("accepts status commands after unrelated Ethernet traffic") { dut =>
    val (rx, tx, _dma) = setup(dut)
    val broadcast = bytes("ffffffffffff")
    val otherIp = bytes("c0a88101")
    val unrelatedArp = arpRequest().dropRight(4) ++ otherIp
    val lldp = bytes("0180c200000e") ++ hostMac ++ bytes("88cc") ++ List.fill(46)(0.toByte)
    val ipv6 = bytes("333300000001") ++ hostMac ++ bytes("86dd") ++ List.fill(114)(0.toByte)
    val otherPort = request(99, 0, 0, dstPort + 1)
    val largeBroadcast = broadcast ++ hostMac ++ bytes("0800") ++ List.fill(1486)(0.toByte)

    // The trace port sees broadcast/multicast traffic even before a capture
    // session starts. None of it may strand the header/body association.
    for ((frame, index) <- Seq(unrelatedArp.padTo(60, 0.toByte), lldp,
      ipv6, otherPort, largeBroadcast).zipWithIndex) {
      var response: Option[List[Byte]] = None
      tx.recvCB() { data => response = Some(data) }
      rx.send(frame)
      rx.send(request(100 + index, 0, 0))
      waitUntil(response.nonEmpty)
      assert(response.get.slice(42, 46) == bytes("4c485444"))
      assert(response.get(47) == LauberhornTraceDump.StatusOk.toByte)
      assert(response.get.slice(50, 54) == be32(100 + index))
    }
  }

  test("diagnostics distinguish tagged traffic from ARP and accepted commands") { dut =>
    val (rx, tx, _dma) = setup(dut)
    val command = request(123, 0, 0)
    // Tagged commands are currently unsupported; count the header without
    // mistaking it for an accepted command or issuing a DMA read.
    val tagged = command.take(12) ++ bytes("81000005") ++ command.drop(12)
    rx.send(tagged)
    dut.clockDomain.waitSampling(40)
    assert(dut.debug.headers.toLong == 1)
    assert(dut.debug.taggedHeaders.toLong == 1)
    assert(dut.debug.commands.toLong == 0)
    assert(dut.debug.responses.toLong == 0)

    def exchange(frame: List[Byte]): List[Byte] = {
      var reply: Option[List[Byte]] = None
      tx.recvCB() { data => reply = Some(data) }
      rx.send(frame)
      waitUntil(reply.nonEmpty)
      reply.get
    }
    assert(exchange(arpRequest().padTo(60, 0.toByte)).slice(12, 14) == bytes("0806"))
    assert(exchange(command).slice(50, 54) == be32(123))
    dut.clockDomain.waitSampling(10)
    assert(dut.debug.headers.toLong == 3)
    assert(dut.debug.arpRequests.toLong == 1)
    assert(dut.debug.commands.toLong == 1)
    assert(dut.debug.responses.toLong == 2)
    assert(dut.debug.taggedHeaders.toLong == 1)
    assert(dut.debug.lastHeader.toBigInt == BigInt(1, command.take(32).reverse.toArray))
    assert((dut.debug.state.toInt & 0xff) == 1) // idle, with no saved header
  }

  test("sends metadata to dump server on dump-over-network rising edge") { dut =>
    val (_rx, tx, _dma) = setup(dut)

    dut.clockDomain.waitSampling(2)
    dut.dumpOverNetwork #= true
    val response = tx.recv()

    assert(response.slice(0, 6) == bytes("f4521463a091"))
    assert(response.slice(6, 12) == fpgaMac)
    assert(response.slice(12, 14) == bytes("0800"))
    assert(response.slice(26, 30) == fpgaIp)
    assert(response.slice(30, 34) == bytes("81846608"))
    assert(response.slice(34, 36) == be16(LauberhornTraceDump.DefaultListenUdpPort))
    assert(response.slice(36, 38) == be16(LauberhornTraceDump.DefaultDumpServerUdpPort))
    assert(response.slice(42, 46) == bytes("4c485444"))
    assert(response(47) == LauberhornTraceDump.StatusOk.toByte)
    assert(response.slice(50, 54) == be32(0))
    assert(response.slice(62, 64) == be16(0))
  }

  test("uses VIO override local and gateway fields") { dut =>
    val (rx, tx, dma) = setup(dut)
    val payload = List.fill(64)(0x5a.toByte)

    dut.cfg.localMacOverrideValid #= true
    dut.cfg.localMacOverride #= BigInt("0c533103beef", 16)
    dut.cfg.localIpOverrideValid #= true
    dut.cfg.localIpOverride #= BigInt("c0a881ca", 16)
    dut.cfg.listenUdpPortOverrideValid #= true
    dut.cfg.listenUdpPortOverride #= 60000
    dut.cfg.gatewayMacOverrideValid #= true
    dut.cfg.gatewayMacOverride #= BigInt("aabbccddeeff", 16)

    fork {
      waitUntil(dut.readDesc.valid.toBoolean)
      dma.send(payload)
    }

    rx.send(request(1, 0, payload.length, requestDstPort = 60000).patch(30, bytes("c0a881ca"), 4))
    val response = tx.recv()
    assert(response.slice(0, 6) == bytes("aabbccddeeff"))
    assert(response.slice(6, 12) == bytes("0c533103beef"))
    assert(response.slice(26, 30) == bytes("c0a881ca"))
    assert(response.slice(30, 34) == hostIp)
    assert(response.slice(34, 36) == be16(60000))
    assert(response.slice(36, 38) == be16(srcPort))
  }

  test("uses VIO override dump server fields for metadata") { dut =>
    val (_rx, tx, _dma) = setup(dut)

    dut.cfg.dumpServerIpOverrideValid #= true
    dut.cfg.dumpServerIpOverride #= BigInt("c0a80264", 16)
    dut.cfg.dumpServerUdpPortOverrideValid #= true
    dut.cfg.dumpServerUdpPortOverride #= 60001

    dut.clockDomain.waitSampling(2)
    dut.dumpOverNetwork #= true
    val response = tx.recv()

    assert(response.slice(30, 34) == bytes("c0a80264"))
    assert(response.slice(36, 38) == be16(60001))
  }

  test("rejects unaligned read commands without issuing DMA") { dut =>
    val (rx, tx, _dma) = setup(dut)

    fork {
      dut.clockDomain.waitSampling(200)
      assert(!dut.readDesc.valid.toBoolean)
    }

    rx.send(request(2, 1, 64))
    val response = tx.recv()
    assert(response.slice(42, 46) == bytes("4c485444"))
    assert(response(47) == LauberhornTraceDump.StatusBadAlignment.toByte)
    assert(response.slice(62, 64) == be16(0))
  }
}
