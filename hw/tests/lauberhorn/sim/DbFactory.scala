package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import lauberhorn.NicEngine

trait DbFactory { this: DutSimFunSuite[NicEngine] =>
  def testWithDB(name: String, tags: org.scalatest.Tag*)(body: NicEngine => Unit) = {
    test(name, tags: _*) { dut =>
      dut.database on body(dut)
    }
  }
}
