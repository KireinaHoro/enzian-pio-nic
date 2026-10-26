package lauberhorn

import jsteward.blocks.DutSimFunSuite
import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

class StreamPipelineBoundarySim extends DutSimFunSuite[StreamPipelineBoundary[UInt]] {
  val dut = Config.sim.compile(StreamPipelineBoundary(UInt(16 bits)))
  test("two-record-capacity-order-and-backpressure") { implicit dut =>
    SimTimeout(20000)
    dut.input.valid #= false
    dut.input.payload #= 0
    dut.output.ready #= false
    dut.clockDomain.forkStimulus(10)
    dut.clockDomain.waitSampling(10)
    val expected = mutable.Queue[Int]()
    var sent = 0
    var received = 0
    var stalled: Option[Int] = None
    for (cycle <- 0 until 700) {
      dut.clockDomain.waitFallingEdge()
      dut.input.valid #= (sent < 300 && cycle % 7 != 6)
      dut.input.payload #= sent
      dut.output.ready #= (cycle >= 8 && (cycle >= 600 || cycle % 11 >= 4))
      sleep(1)
      // A full boundary keeps ready low even when downstream ready rises.
      if (cycle == 8) assert(!dut.input.ready.toBoolean)
      dut.clockDomain.waitSampling()
      stalled.foreach(value => {
        assert(dut.output.valid.toBoolean)
        assert(dut.output.payload.toInt == value)
      })
      if (dut.output.valid.toBoolean && dut.output.ready.toBoolean) {
        assert(expected.nonEmpty)
        assert(dut.output.payload.toInt == expected.dequeue())
        received += 1
      }
      if (dut.input.valid.toBoolean && dut.input.ready.toBoolean) {
        expected.enqueue(sent)
        sent += 1
      }
      if (cycle == 7) assert(sent == 2 && received == 0)
      stalled = if (dut.output.valid.toBoolean && !dut.output.ready.toBoolean)
        Some(dut.output.payload.toInt) else None
    }
    assert(sent == 300 && received == 300 && expected.isEmpty)
  }
}
