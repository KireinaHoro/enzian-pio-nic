package lauberhorn

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

class TraceAutoPipelineSim extends AnyFunSuite {
  test("payload-valid-alignment-and-reset-for-all-allowed-depths") {
    val compiled = SimConfig.withVerilator.compile(new Component {
      val input = slave Flow(Bits(75 bits))
      val outputs = Vec(master(Flow(Bits(75 bits))), 11)
      for (extra <- 0 to 10) {
        val pipe = TraceAutoPipeline(75, 10, extra, extra)
        pipe.input << input
        outputs(extra) << pipe.output
      }
    })
    compiled.doSim { dut =>
      SimTimeout(10000)
      dut.input.valid #= false
      dut.input.payload #= 0
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(20)
      for (round <- 0 until 2) {
        dut.clockDomain.assertReset()
        dut.clockDomain.waitRisingEdge(3)
        dut.clockDomain.deassertReset()
        val queues = Array.fill(11)(scala.collection.mutable.Queue[(Boolean, BigInt)]())
        for (cycle <- 0 until 200) {
          dut.clockDomain.waitFallingEdge()
          val valid = cycle % 7 != 0
          val value = (BigInt(cycle + round * 200) << 55) | BigInt(cycle * 31)
          dut.input.valid #= valid
          dut.input.payload #= value
          queues.foreach(_.enqueue(valid -> value))
          dut.clockDomain.waitSampling()
          sleep(1)
          for (extra <- 0 to 10) {
            if (queues(extra).size >= extra + 2) {
              val expected = queues(extra).dequeue()
              if (cycle >= 14) {
                assert(dut.outputs(extra).valid.toBoolean == expected._1)
                if (expected._1) assert(dut.outputs(extra).payload.toBigInt == expected._2)
              }
            }
            if (cycle < 11) assert(!dut.outputs(extra).valid.toBoolean)
          }
        }
      }
    }
  }
}
