package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.Config
import spinal.core.sim._

class EciDomainAllocatorSim extends DutSimFunSuite[EciDomainAllocator] {
  val dut = Config.sim.compile(EciDomainAllocator())
  test("all lifetime codes allocate once and exhaustion never wraps") { implicit dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.appReset #= false
    dut.io.allocate #= false
    dut.io.fatal #= false
    dut.clockDomain.waitSampling(10)
    for (code <- 0 until EciAddressMap.domain_limit) {
      assert(dut.io.next.toInt == code)
      dut.io.allocate #= true
      dut.clockDomain.waitSampling()
      sleep(1)
    }
    dut.clockDomain.waitSampling(10)
    assert(dut.io.next.toInt == EciAddressMap.domain_limit)
    assert(!dut.io.resetRequired.toBoolean)
    // Application reset cannot recycle a code or restore a usable session.
    dut.io.appReset #= true
    dut.clockDomain.waitSampling(2)
    dut.io.appReset #= false
    dut.clockDomain.waitSampling(2)
    assert(dut.io.next.toInt == EciAddressMap.domain_limit)
    assert(dut.io.resetRequired.toBoolean)
  }
  test("fatal failure fences allocation and application reset preserves lifetime") { implicit dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.appReset #= false
    dut.io.allocate #= false
    dut.io.fatal #= false
    dut.clockDomain.waitSampling(10)
    dut.io.allocate #= true
    dut.clockDomain.waitSampling(3)
    dut.io.allocate #= false
    sleep(1)
    val allocated = dut.io.next.toInt
    assert(allocated == 3)
    dut.io.fatal #= true
    dut.clockDomain.waitSampling(2)
    dut.io.fatal #= false
    dut.io.allocate #= true
    dut.clockDomain.waitSampling(3)
    assert(dut.io.next.toInt == allocated && dut.io.resetRequired.toBoolean)
    dut.io.appReset #= true
    dut.clockDomain.waitSampling(2)
    dut.io.appReset #= false
    dut.clockDomain.waitSampling(2)
    assert(dut.io.next.toInt == allocated && dut.io.resetRequired.toBoolean)
  }
}
