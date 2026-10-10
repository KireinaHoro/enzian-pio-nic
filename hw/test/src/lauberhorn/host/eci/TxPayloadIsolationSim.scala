package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.{Config, Global}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.database.Database

case class TxPayloadIsolationFixture() extends Component {
  val cfg = Axi4Config(addressWidth = 24, dataWidth = 512, idWidth = 3,
    useRegion = false, useQos = false)
  val axi = slave(Axi4(cfg))
  val memory = master(Axi4(cfg))
  val preempt = in Bool()
  val quiet = out Bool()
  val invalidateDone = in Bool()
  val payloadLines = out UInt(4 bits)
  val router = DcsTxAxiRouter(cfg, cfg)
  router.dcsAxi <> axi
  memory <> router.pktBufAxi
  router.currCl := 0
  router.txAddr.bits := 4096
  router.doPreempt := preempt
  router.invDone := invalidateDone
  payloadLines := router.payloadLines
  router.txDesc.ready := False
  router.txResult.valid := False
  router.txResult.payload := 0
  quiet := router.quiescent
}

/** Direct AXI checks complement the coherent host, which writes full cache lines. */
class TxPayloadIsolationSim extends DutSimFunSuite[TxPayloadIsolationFixture] {
  val db = new Database
  db on { Global.initDatabase(2, 1); Global.HOST_REQ_WIDTH.set(512); Global.ECI_TX_BASE.set(EciAddressMap.tx_control) }
  val dut = Config.sim.compile(db on TxPayloadIsolationFixture())
  test("scrub completion fences reads and partial writes and protects adjacent allocations") { implicit dut =>
    SimTimeout(200000)
    dut.clockDomain.forkStimulus(10)
    val a = dut.axi
    val m = dut.memory
    val bytes = Array.fill[Byte](8192)(0x5a.toByte)
    var holdResponse = true
    var scrubWrites = 0
    dut.preempt #= false
    dut.invalidateDone #= false
    a.ar.valid #= false; a.aw.valid #= false; a.w.valid #= false
    a.r.ready #= false; a.b.ready #= false
    for (c <- Seq(a.ar, a.aw)) {
      c.addr #= 0; c.id #= 3; c.len #= 1; c.size #= 6
      c.burst #= 1; c.lock #= 0; c.cache #= 0; c.prot #= 0
    }
    a.w.data #= 0; a.w.strb #= 0; a.w.last #= false
    m.ar.ready #= false; m.aw.ready #= false; m.w.ready #= false
    m.r.valid #= false; m.r.data #= 0; m.r.id #= 0; m.r.resp #= 0; m.r.last #= true
    m.b.valid #= false; m.b.id #= 0; m.b.resp #= 0
    dut.clockDomain.waitSampling(20)
    def step(n: Int = 1): Unit = { dut.clockDomain.waitSampling(n); sleep(1) }
    fork {
      while (true) {
        m.aw.ready #= true
        dut.clockDomain.waitSamplingWhere(m.aw.valid.toBoolean)
        val address = m.aw.addr.toInt
        val id = m.aw.id.toInt
        assert(m.aw.len.toInt == 0 && address >= 4096 && address + 64 <= 4096 + 1536)
        m.aw.ready #= false
        step(3)
        m.w.ready #= true
        dut.clockDomain.waitSamplingWhere(m.w.valid.toBoolean)
        val data = m.w.data.toBigInt
        val mask = m.w.strb.toBigInt
        assert(m.w.last.toBoolean)
        for (i <- 0 until 64 if mask.testBit(i)) bytes(address + i) = (data >> (i * 8)).toByte
        scrubWrites += 1
        m.w.ready #= false
        while (holdResponse) step()
        step(3)
        m.b.id #= id; m.b.valid #= true
        dut.clockDomain.waitSamplingWhere(m.b.ready.toBoolean)
        m.b.valid #= false
      }
    }
    fork {
      while (true) {
        m.ar.ready #= true
        dut.clockDomain.waitSamplingWhere(m.ar.valid.toBoolean)
        val address = m.ar.addr.toInt
        val id = m.ar.id.toInt
        assert(m.ar.len.toInt == 0 && address >= 4096 && address + 64 <= 4096 + 1536)
        m.ar.ready #= false
        val data = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(bytes(address+i) & 255) << (8*i)))
        step(4)
        m.r.data #= data; m.r.id #= id; m.r.valid #= true
        dut.clockDomain.waitSamplingWhere(m.r.ready.toBoolean)
        m.r.valid #= false
      }
    }
    def ar(address: Int): Unit = {
      a.ar.addr #= address; a.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
      a.ar.valid #= false
    }
    def readResult(): List[BigInt] = (0 until 2).map { i =>
      a.r.ready #= false
      dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
      val data = a.r.data.toBigInt
      step(5)
      assert(a.r.valid.toBoolean && a.r.data.toBigInt == data && a.r.id.toInt == 3)
      assert(a.r.last.toBoolean == (i == 1))
      a.r.ready #= true; step(); a.r.ready #= false
      data
    }.toList
    dut.preempt #= true; step(); dut.preempt #= false
    ar(0x1400)
    while (scrubWrites == 0) step()
    step(12)
    assert(!dut.quiet.toBoolean && !a.r.valid.toBoolean, "read bypassed pending scrub B")
    holdResponse = false
    assert(readResult() == List(BigInt(0), BigInt(0)))
    assert(bytes.slice(4096, 4096+1536).forall(_ == 0))
    assert(bytes.drop(4096+1536).forall(_ == 0x5a.toByte))

    def write(address: Int, mask: BigInt, first: BigInt = (BigInt(1) << 512) - 1,
              second: BigInt = (BigInt(1) << 512) - 1): Unit = {
      a.aw.addr #= address; a.aw.valid #= true
      dut.clockDomain.waitSamplingWhere(a.aw.ready.toBoolean)
      a.aw.valid #= false
      for (i <- 0 until 2) {
        a.w.data #= (if (i == 0) first else second)
        a.w.strb #= mask; a.w.last #= (i == 1); a.w.valid #= true
        dut.clockDomain.waitSamplingWhere(a.w.ready.toBoolean)
        a.w.valid #= false
      }
      a.b.ready #= false
      dut.clockDomain.waitSamplingWhere(a.b.valid.toBoolean)
      step(5)
      assert(a.b.valid.toBoolean && a.b.id.toInt == 3 && a.b.resp.toInt == 0)
      a.b.ready #= true; step(); a.b.ready #= false
    }
    write(0x1400, 1)
    ar(0x1400)
    assert(readResult() == List(BigInt(255), BigInt(255)))
    // Contiguous layout uses both beats of the last overflow CL.
    write(0x1980, (BigInt(1) << 64) - 1)
    ar(0x1980)
    assert(readResult() == List.fill(2)((BigInt(1) << 512) - 1))
    // The next CL must neither expose nor modify the adjacent allocation.
    write(0x1a00, (BigInt(1) << 64) - 1)
    ar(0x1a00)
    assert(readResult() == List(BigInt(0), BigInt(0)))
    assert(bytes.drop(4096+1536).forall(_ == 0x5a.toByte), "padding write corrupted adjacent slot")
    while (!dut.quiet.toBoolean) step()
    dut.preempt #= true; step(); dut.preempt #= false
    ar(0x1400)
    assert(readResult() == List(BigInt(0), BigInt(0)))
    // Voluntary overflow returns are accepted before commit, even after an
    // inline control eviction. Commit restores the retained inline bytes.
    val mask = (BigInt(1) << 64) - 1
    def control(length: Int, inline: Boolean): BigInt =
      (BigInt(length) << 4) | (if (inline) BigInt(1) << 23 else BigInt(0))
    write(0x1300, mask, control(32, true), BigInt(0x42))
    write(0x1400, mask, BigInt(0x99), BigInt(0x77))
    assert(bytes(4096) == 0x99.toByte)
    assert(dut.payloadLines.toInt == 0)
    dut.invalidateDone #= true; step(3); dut.invalidateDone #= false
    while (!dut.quiet.toBoolean) step()
    assert(bytes(4096) == 0x42.toByte)
    // Late overflow data must not overwrite the committed inline packet.
    write(0x1400, mask, BigInt(0x33), BigInt(0x55))
    assert(bytes(4096) == 0x42.toByte)
    // Cleanup extent uses actual payload length, including short overflow sends.
    for ((length, lines) <- Seq(0 -> 0, 1 -> 1, 64 -> 1, 128 -> 1, 129 -> 2, 1472 -> 12)) {
      write(0x1300, mask, control(length, false), BigInt(0))
      assert(dut.payloadLines.toInt == lines)
    }
    while (!dut.quiet.toBoolean) step()
    dut.preempt #= true; step(); dut.preempt #= false
    ar(0x1400)
    assert(readResult() == List(BigInt(0), BigInt(0)))

  }
}
