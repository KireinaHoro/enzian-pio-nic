package pionic.sim

import pionic.NicEngine
import spinal.core.sim._

abstract class SimApp extends App {
  def dut: SimCompiled[NicEngine]

  def sleepCycles(n: Int)(implicit dut: NicEngine) = dut.clockDomain.waitActiveEdge(n)

  assert(args.length <= 1, "usage: SimApp [test name pattern]")

  def test(name: String)(body: NicEngine => Unit) = {
    if (args.isEmpty || args.head.r.findFirstIn(name).nonEmpty) {
      dut.doSim(name)(body)
    } else {
      println(s"[info] skipping test $name")
    }
  }
}
