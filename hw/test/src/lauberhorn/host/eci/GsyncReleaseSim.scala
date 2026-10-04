package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.{Config, Global}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.database.Database

case class GsyncReleaseFixture() extends Component {
  val cfg = Axi4Config(addressWidth = 24, dataWidth = 512, idWidth = 3,
    useRegion = false, useQos = false)
  val release = in Bool()
  val descriptor = in Bool()
  val invalidate = in Bool()
  val parity = in UInt(1 bit)
  val axi = Vec.fill(5)(slave(Axi4(cfg)))
  for (a <- axi) {
    val r = DcsRxAxiRouter(cfg, cfg)
    r.dcsAxi <> a
    r.pktBufAxi.setBlocked()
    r.rxDesc.valid := descriptor && r.rxDesc.ready
    r.rxDesc.payload.assignFromBits(B(0, r.rxDesc.payload.getBitsWidth bits))
    r.cancelRxDesc := False
    r.blockCycles := 1000
    r.currCl := parity
    r.doPreempt := False
    r.yieldRequested := False
    r.fatalRelease := False
    r.gsyncRelease := release
    r.invDone := invalidate
  }
}

class GsyncReleaseSim extends DutSimFunSuite[GsyncReleaseFixture] {
  val db = new Database
  db on { Global.initDatabase(5, 4); Global.HOST_REQ_WIDTH.set(512) }
  val dut = Config.sim.compile(db on GsyncReleaseFixture())
  test("GSYNC releases current waits only, preserves invalidation and descriptor priority") { implicit dut =>
    SimTimeout(200000)
    dut.clockDomain.forkStimulus(10)
    dut.release #= false; dut.descriptor #= false
    dut.invalidate #= false; dut.parity #= 0
    for (a <- dut.axi) {
      a.ar.valid #= false; a.aw.valid #= false; a.w.valid #= false
      a.r.ready #= false; a.b.ready #= true
      for (c <- Seq(a.ar, a.aw)) {
        c.addr #= 0; c.id #= 3; c.len #= 1; c.size #= 6
        c.burst #= 1; c.lock #= 0; c.cache #= 0; c.prot #= 0
      }
      a.w.data #= 0; a.w.strb #= 0; a.w.last #= false
    }
    def step(n: Int = 1): Unit = { dut.clockDomain.waitSampling(n); sleep(1) }
    step(20)
    def pulse(): Unit = { dut.release #= true; step(); dut.release #= false }
    def request(address: Int): Unit = {
      for (a <- dut.axi) { a.ar.addr #= address; a.ar.valid #= true }
      dut.clockDomain.waitSamplingWhere(dut.axi.forall(_.ar.ready.toBoolean))
      for (a <- dut.axi) a.ar.valid #= false
      step(15)
    }
    def noResponse(n: Int): Unit = for (_ <- 0 until n) {
      assert(dut.axi.forall(a => !a.r.valid.toBoolean), "unexpected early response")
      step()
    }
    var acceptedControl = Seq.empty[BigInt]
    def response(valid: Boolean): Unit = {
      var waited = 0
      while (!dut.axi.forall(_.r.valid.toBoolean) && waited < 50) { step(); waited += 1 }
      assert(waited < 50, "GSYNC release waited for the RX timeout")
      // Both beats must remain stable under host backpressure, including zero
      // payload after a NACK. No packet memory service is supplied by the fixture.
      for (beat <- 0 until 2) {
        dut.clockDomain.waitSamplingWhere(dut.axi.forall(_.r.valid.toBoolean))
        val expected = dut.axi.map(_.r.data.toBigInt).toSeq
        if (beat == 0 && valid) {
          assert(expected.forall(_.testBit(0)))
          if (acceptedControl.nonEmpty) assert(expected == acceptedControl, "replay changed descriptor")
          acceptedControl = expected
        } else assert(expected.forall(_ == 0), "NACK or zero-length payload leaked data")
        for (_ <- 0 until 5) {
          for ((a, data) <- dut.axi.zip(expected)) {
            assert(a.r.valid.toBoolean && a.r.id.toInt == 3)
            assert(a.r.last.toBoolean == (beat == 1))
            assert(a.r.data.toBigInt == data)
          }
          step()
        }
        for (a <- dut.axi) a.r.ready #= true
        step()
        for (a <- dut.axi) a.r.ready #= false
      }
      step(10)
    }
    // An idle notification does not shorten a later read.
    pulse(); step(10)
    request(0x300); noResponse(50)
    pulse(); response(valid = false)

    // Alternating CL requires invalidation; GSYNC cannot bypass it.
    dut.parity #= 0
    request(0x380); noResponse(50)
    pulse(); noResponse(30)
    dut.invalidate #= true; step(); dut.invalidate #= false
    response(valid = false)

    // Neither the previous pulse nor the completed NACK poisons the next wait.
    dut.parity #= 1
    request(0x300); noResponse(50)
    dut.invalidate #= true; step(); dut.invalidate #= false
    // A descriptor already available on the trigger cycle wins, as at timeout.
    dut.descriptor #= true
    pulse(); dut.descriptor #= false
    response(valid = true)

    // A same-CL replay retains its accepted descriptor despite GSYNC.
    dut.parity #= 0
    dut.release #= true
    request(0x300)
    dut.release #= false
    response(valid = true)

    // With no new GSYNC, the normal timeout still applies.
    request(0x380)
    dut.invalidate #= true; step(); dut.invalidate #= false
    noResponse(900)
    step(100)
    response(valid = false)
  }
}
