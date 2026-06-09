package lauberhorn

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.axi.AxiDmaConfig
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axis.Axi4StreamConfig
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}
import spinal.lib.sim._

class LauberhornTraceDumpTests extends DutSimFunSuite[LauberhornTraceDump] {
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
    .compile(LauberhornTraceDump(axisConfig, dmaConfig, axiBase))

  def be16(value: Int): List[Byte] =
    List(((value >> 8) & 0xff).toByte, (value & 0xff).toByte)

  def be32(value: Long): List[Byte] =
    (3 to 0 by -1).map(shift => ((value >> (shift * 8)) & 0xff).toByte).toList

  def be64(value: Long): List[Byte] =
    (7 to 0 by -1).map(shift => ((value >> (shift * 8)) & 0xff).toByte).toList

  def bytes(hex: String): List[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toList

  val fpgaMac = bytes("0c5331030028")
  val hostMac = bytes("001122334455")
  val fpgaIp = bytes("0a000002")
  val hostIp = bytes("0a000001")
  val srcPort = 44444
  val dstPort = 55555

  def request(seq: Long, offset: Long, length: Int): List[Byte] = {
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
      be16(srcPort) ++ be16(dstPort) ++ be16(udpLen) ++ bytes("0000") ++ body
  }

  def setup(dut: LauberhornTraceDump) = {
    SimTimeout(40000)
    dut.clockDomain.forkStimulus(period = 4)

    dut.cfg.gatewayMacOverrideValid #= false
    dut.cfg.gatewayMacOverride #= 0
    dut.cfg.destIpOverrideValid #= false
    dut.cfg.destIpOverride #= 0
    dut.cfg.destUdpPortOverrideValid #= false
    dut.cfg.destUdpPortOverride #= 0
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
    assert(response.slice(30, 34) == bytes("81846608"))
    assert(response.slice(36, 38) == be16(LauberhornTraceDump.DefaultDestUdpPort))
    assert(response.slice(42, 46) == bytes("4c485444"))
    assert(response(47) == LauberhornTraceDump.StatusOk.toByte)
    assert(response.slice(50, 54) == be32(seq))
    assert(response.slice(54, 62) == be64(offset))
    assert(response.slice(62, 64) == be16(length))
    assert(response.drop(42 + LauberhornTraceDump.ResponseHeaderBytes) == payload)
  }

  test("uses VIO override destination fields") { dut =>
    val (rx, tx, dma) = setup(dut)
    val payload = List.fill(64)(0x5a.toByte)

    dut.cfg.gatewayMacOverrideValid #= true
    dut.cfg.gatewayMacOverride #= BigInt("aabbccddeeff", 16)
    dut.cfg.destIpOverrideValid #= true
    dut.cfg.destIpOverride #= BigInt("c0a80164", 16)
    dut.cfg.destUdpPortOverrideValid #= true
    dut.cfg.destUdpPortOverride #= 60000

    fork {
      waitUntil(dut.readDesc.valid.toBoolean)
      dma.send(payload)
    }

    rx.send(request(1, 0, payload.length))
    val response = tx.recv()
    assert(response.slice(0, 6) == bytes("aabbccddeeff"))
    assert(response.slice(30, 34) == bytes("c0a80164"))
    assert(response.slice(36, 38) == be16(60000))
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
