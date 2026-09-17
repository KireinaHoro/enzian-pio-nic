package lauberhorn

import jsteward.blocks.DutSimFunSuite
import spinal.core.sim._

class ExecutionWatchdogSim extends DutSimFunSuite[ExecutionWatchdog] {
  val dut = Config.sim.compile(new ExecutionWatchdog(8))
  test("disabled-suspension-resumption-wrap-and-sticky-expiry") { implicit dut =>
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(4)
    dut.io.now #= 0
    dut.io.limit #= 10
    dut.io.enabled #= false
    dut.io.clear #= false
    dut.io.begin #= false
    dut.io.end #= false
    dut.clockDomain.waitSampling(20)
    def step(now: Int, begin: Boolean = false, end: Boolean = false): Unit = {
      dut.io.now #= now
      dut.io.begin #= begin
      dut.io.end #= end
      dut.clockDomain.waitSampling()
      sleep(1)
    }
    step(0, begin = true)
    step(100)
    assert(!dut.io.active.toBoolean && !dut.io.expired.toBoolean)
    dut.io.enabled #= true
    step(100, begin = true)
    assert(dut.io.deadline.toInt == 110 && dut.io.active.toBoolean)
    step(109, end = true)
    step(200)
    assert(!dut.io.active.toBoolean && !dut.io.expired.toBoolean)
    step(250, begin = true)
    assert(dut.io.deadline.toInt == 4)
    step(3)
    assert(!dut.io.expired.toBoolean)
    step(4)
    assert(dut.io.expired.toBoolean && !dut.io.active.toBoolean)
    step(5, begin = true)
    step(6, end = true)
    assert(dut.io.expired.toBoolean)
    dut.io.clear #= true
    step(7)
    dut.io.clear #= false
    assert(!dut.io.expired.toBoolean)
    step(8, begin = true)
    step(15, begin = true, end = true)
    assert(dut.io.deadline.toInt == 25 && dut.io.active.toBoolean)
    dut.io.enabled #= false
    step(30)
    assert(!dut.io.active.toBoolean && !dut.io.expired.toBoolean)
  }
}
