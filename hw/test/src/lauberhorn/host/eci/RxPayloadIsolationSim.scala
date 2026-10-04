package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.{Config, Global}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.database.Database

case class RxPayloadIsolationFixture() extends Component {
  val cfg = Axi4Config(addressWidth = 24, dataWidth = 512, idWidth = 3,
    useRegion = false, useQos = false)
  val axi = slave(Axi4(cfg))
  val memory = master(Axi4(cfg))
  val length = in UInt(16 bits)
  val router = DcsRxAxiRouter(cfg, cfg)
  router.dcsAxi <> axi
  memory <> router.pktBufAxi
  router.rxDesc.valid := router.rxDesc.ready
  router.rxDesc.payload.assignFromBits(B(0, router.rxDesc.payload.getBitsWidth bits))
  router.rxDesc.len.bits.allowOverride
  router.rxDesc.len.bits := length
  router.rxDesc.buffer.addr.bits.allowOverride
  router.rxDesc.buffer.addr.bits := 4096
  router.rxDesc.buffer.size.bits.allowOverride
  router.rxDesc.buffer.size.bits := 128
  router.cancelRxDesc := False
  router.blockCycles := 100
  router.currCl := 0
  router.doPreempt := False
  router.yieldRequested := False
  router.fatalRelease := False
  router.gsyncRelease := False
  router.invDone := True
}

class RxPayloadIsolationSim extends DutSimFunSuite[RxPayloadIsolationFixture] {
  val db = new Database
  db on { Global.initDatabase(2, 1); Global.HOST_REQ_WIDTH.set(512) }
  val dut = Config.sim.compile(db on RxPayloadIsolationFixture())
  test("RX masks stale allocation padding across short packet reuse and overflow") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    val a = dut.axi
    val m = dut.memory
    val poison = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(0x5a) << (8*i)))
    dut.length #= 65
    a.ar.valid #= false; a.aw.valid #= false; a.w.valid #= false
    a.r.ready #= false; a.b.ready #= true
    for (c <- Seq(a.ar, a.aw)) {
      c.addr #= 0; c.id #= 3; c.len #= 1; c.size #= 6
      c.burst #= 1; c.lock #= 0; c.cache #= 0; c.prot #= 0
    }
    a.w.data #= 0; a.w.strb #= 0; a.w.last #= false
    m.ar.ready #= false; m.aw.ready #= false; m.w.ready #= false
    m.r.valid #= false; m.r.data #= 0; m.r.id #= 3; m.r.resp #= 0; m.r.last #= true
    m.b.valid #= false; m.b.id #= 0; m.b.resp #= 0
    dut.clockDomain.waitSampling(20)
    def step(n: Int = 1): Unit = { dut.clockDomain.waitSampling(n); sleep(1) }
    fork {
      while (true) {
        m.ar.ready #= true
        dut.clockDomain.waitSamplingWhere(m.ar.valid.toBoolean)
        assert(m.ar.len.toInt == 0 && m.ar.addr.toInt >= 4096 && m.ar.addr.toInt < 4224)
        m.ar.ready #= false
        step(4)
        // Every unwritten byte deliberately retains the previous packet's data.
        m.r.data #= poison; m.r.valid #= true
        dut.clockDomain.waitSamplingWhere(m.r.ready.toBoolean)
        m.r.valid #= false
      }
    }
    def read(address: Int): List[BigInt] = {
      a.ar.addr #= address; a.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
      a.ar.valid #= false
      (0 until 2).map { i =>
        dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
        val data = a.r.data.toBigInt
        step(5)
        assert(a.r.valid.toBoolean && a.r.data.toBigInt == data && a.r.id.toInt == 3)
        assert(a.r.last.toBoolean == (i == 1))
        a.r.ready #= true; step(); a.r.ready #= false
        data
      }.toList
    }
    assert(read(0x300)(1) == poison)
    assert(read(0x400) == List(BigInt(0x5a), BigInt(0)))
    assert(read(0x480) == List(BigInt(0), BigInt(0)))
    dut.length #= 1
    assert(read(0x380)(1) == 0x5a)
    assert(read(0x400) == List(BigInt(0), BigInt(0)))
  }
}
