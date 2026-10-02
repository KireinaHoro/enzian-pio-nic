package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.{Config, Global}
import spinal.core.sim._
import spinal.lib.misc.database.Database

/** Independent AXI channels, response backpressure and queued ownership. */
class RxWritebackSim extends DutSimFunSuite[RxWritebackFixture] {
  val db = new Database
  db on {
    Global.initDatabase(5, 4)
    Global.HOST_REQ_WIDTH.set(512)
  }
  val dut = Config.sim.compile(db on RxWritebackFixture())
  test("RX-writeback-drains-two-beats-with-independent-channels-and-stable-B") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    dut.invDone #= false
    dut.axi.aw.valid #= false
    dut.axi.w.valid #= false
    dut.axi.b.ready #= false
    dut.axi.ar.valid #= false
    dut.axi.r.ready #= true
    dut.axi.aw.addr #= 0x100
    dut.axi.aw.id #= 0
    dut.axi.aw.len #= 1
    dut.axi.aw.size #= 6
    dut.axi.aw.burst #= 1
    dut.axi.aw.region #= 0
    dut.axi.aw.lock #= 0
    dut.axi.aw.cache #= 0
    dut.axi.aw.qos #= 0
    dut.axi.aw.prot #= 0
    dut.axi.w.data #= 0
    dut.axi.w.strb #= 0
    dut.axi.w.last #= false
    dut.clockDomain.waitSampling(20)
    def step(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    def aw(id: Int): Unit = {
      dut.axi.aw.id #= id
      dut.axi.aw.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.axi.aw.ready.toBoolean)
      dut.axi.aw.valid #= false
      sleep(1)
    }
    def beat(last: Boolean, mask: BigInt): Unit = {
      dut.axi.w.last #= last
      dut.axi.w.strb #= mask
      dut.axi.w.data #= BigInt("12345678", 16)
      dut.axi.w.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.axi.w.ready.toBoolean)
      dut.axi.w.valid #= false
      sleep(1)
    }
    def response(id: Int): Unit = {
      dut.clockDomain.waitSamplingWhere(dut.axi.b.valid.toBoolean)
      for(_ <- 0 until 12) {
        assert(dut.axi.b.valid.toBoolean && dut.axi.b.id.toInt == id && dut.axi.b.resp.toInt == 0)
        assert(!dut.quiescent.toBoolean)
        step()
      }
      dut.axi.b.ready #= true
      step()
      dut.axi.b.ready #= false
    }
    // W before AW is legal: retain it upstream until an address has arrived.
    dut.axi.w.valid #= true
    for(_ <- 0 until 6) { step(); assert(!dut.axi.w.ready.toBoolean && !dut.axi.b.valid.toBoolean) }
    aw(13)
    dut.clockDomain.waitSamplingWhere(dut.axi.w.ready.toBoolean)
    dut.axi.w.valid #= false
    sleep(1)
    assert(!dut.quiescent.toBoolean)
    for(_ <- 0 until 8) { step(); assert(!dut.axi.b.valid.toBoolean) }
    // Queue the next AW before completing this burst. Responses must retain IDs.
    aw(71)
    beat(true, (BigInt(1) << 64) - 1)
    response(13)
    assert(!dut.quiescent.toBoolean)
    beat(false, BigInt("ffffffff", 16))
    beat(true, 0)
    response(71)
    for(_ <- 0 until 4) step()
    assert(dut.quiescent.toBoolean)
  }
}
