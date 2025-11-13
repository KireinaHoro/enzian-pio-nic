package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import lauberhorn.NicEngine
import spinal.core.sim.SimTimeout

trait DbFactory { this: DutSimFunSuite[NicEngine] =>
  def testWithDB(name: String, timeout: Long = 5e9.toLong)(tags: org.scalatest.Tag*)(body: NicEngine => Unit) = {
    test(name, tags: _*) { dut =>
      SimTimeout(timeout)
      dut.database on body(dut)
    }
  }
}
