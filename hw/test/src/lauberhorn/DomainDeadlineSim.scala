package lauberhorn

import jsteward.blocks.DutSimFunSuite
import spinal.core.sim._

class DomainDeadlineSim extends DutSimFunSuite[DomainDeadline] {
  val dut = Config.sim.compile(new DomainDeadline(8, 4, 8, 4))
  test("publication-expiry-claim-supersession-and-lifetime-fencing") { implicit dut =>
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(4)
    dut.io.now #= 10
    dut.io.enabled #= false
    dut.io.lifetime #= 1
    dut.io.command.valid #= false
    dut.io.command.lifetime #= 1
    dut.io.command.expectedGeneration #= 0
    dut.io.command.next #= 0
    dut.io.claim #= false
    dut.clockDomain.waitSampling(10)
    def tick(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    def publish(expected: Int, next: Int, accepted: Boolean): Unit = {
      dut.io.command.expectedGeneration #= expected
      dut.io.command.next #= next
      dut.io.command.valid #= true
      sleep(1)
      assert(dut.io.accepted.toBoolean == accepted)
      tick()
      dut.io.command.valid #= false
    }
    dut.io.enabled #= true
    tick()
    publish(0, 13, false) // minimum lead is inclusive
    assert(dut.io.generation.toInt == 0)
    publish(0, 14, true)
    dut.io.now #= 13; tick()
    assert(!dut.io.pending.toBoolean)
    dut.io.now #= 14; tick()
    assert(dut.io.runnable.toBoolean)
    dut.io.claim #= true; tick(); dut.io.claim #= false
    assert(dut.io.pending.toBoolean && dut.io.claimed.toBoolean && !dut.io.runnable.toBoolean)
    publish(0, 30, false) // stale acknowledgement cannot clear claimed expiry
    publish(1, 17, false) // rejected arm preserves the previous slot
    assert(dut.io.pending.toBoolean && dut.io.generation.toInt == 1)
    publish(1, 30, true)
    assert(!dut.io.pending.toBoolean && !dut.io.claimed.toBoolean)
    publish(1, 0, false)
    assert(dut.io.next.toInt == 30)
    dut.io.now #= 30; tick()
    dut.io.claim #= true
    publish(2, 40, true) // publication wins simultaneous claim
    dut.io.claim #= false
    assert(!dut.io.pending.toBoolean && !dut.io.claimed.toBoolean)
    publish(3, 0, true)
    dut.io.now #= 100; tick()
    assert(!dut.io.pending.toBoolean)
    // Modular ticks permit a deadline across counter wrap, within half range.
    dut.io.now #= 250
    publish(4, 5, true)
    dut.io.now #= 4; tick(); assert(!dut.io.pending.toBoolean)
    dut.io.now #= 5; tick(); assert(dut.io.pending.toBoolean)
    dut.io.lifetime #= 2; tick(); tick()
    assert(dut.io.generation.toInt == 0 && !dut.io.pending.toBoolean)
    publish(0, 20, false) // prior lifetime, even with matching generation
    dut.io.command.lifetime #= 2
    publish(0, 20, true)
    dut.io.enabled #= false; tick()
    assert(dut.io.next.toInt == 0 && !dut.io.runnable.toBoolean)
    dut.io.enabled #= true; tick()
    for (g <- 0 until 15) publish(g, 0, true)
    publish(15, 0, false) // never reuse a generation within one lifetime
    assert(dut.io.exhausted.toBoolean)
  }
}
