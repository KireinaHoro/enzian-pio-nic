package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.Config
import spinal.core.sim._

class EciDomainFaultsSim extends DutSimFunSuite[EciDomainFaults] {
  val dut = Config.sim.compile(EciDomainFaults(4, 2))
  test("faults kill PA lifetime owners and survive concurrent notification consumption") { implicit dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.bound #= 14
    dut.io.codes(0) #= 0
    dut.io.codes(1) #= 0x40001
    dut.io.codes(2) #= 7
    dut.io.codes(3) #= 0x12345
    dut.io.interrupt.ready #= false
    dut.io.violations.foreach { v => v.valid #= false; v.payload #= 0 }
    dut.clockDomain.waitSampling(20)
    def step(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    dut.io.violations(0).valid #= true
    dut.io.violations(0).payload #= 0x40001
    dut.io.violations(1).valid #= true
    dut.io.violations(1).payload #= 7
    step()
    dut.io.violations.foreach(_.valid #= false)
    assert(dut.io.failed.toInt == 6 && dut.io.interrupt.valid.toBoolean)
    step()
    assert(dut.io.failed.toInt == 6 && dut.io.interrupt.valid.toBoolean)
    // Consume the old notification while a third domain faults.
    dut.io.interrupt.ready #= true
    dut.io.violations(1).valid #= true
    dut.io.violations(1).payload #= 0x12345
    step()
    dut.io.violations(1).valid #= false
    assert(dut.io.failed.toInt == 14 && dut.io.interrupt.valid.toBoolean)
    step()
    assert(!dut.io.interrupt.valid.toBoolean && dut.io.failed.toInt == 14)
    // Repeated errors coalesce; an unbound code cannot blame another slot.
    dut.io.violations(0).valid #= true
    dut.io.violations(0).payload #= 0
    step()
    assert(!dut.io.interrupt.valid.toBoolean && dut.io.failed.toInt == 14)
  }
}
