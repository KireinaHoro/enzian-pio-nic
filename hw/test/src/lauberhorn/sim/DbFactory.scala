package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import lauberhorn.NicEngine
import spinal.core.sim.SimTimeout

trait DbFactory { this: DutSimFunSuite[NicEngine] =>
  protected var activeTraceConsumer: Option[TraceEventConsumer] = None

  def testWithDB(name: String, timeout: Long = 5e9.toLong)(tags: org.scalatest.Tag*)(body: NicEngine => Unit) = {
    test(name, tags: _*) { dut =>
      SimTimeout(timeout)
      val trace = TraceEventConsumer(dut)
      activeTraceConsumer = Some(trace)
      try {
        dut.database on body(dut)
      } finally {
        val tracePath = workspace(name) / "lauberhorn_trace.pcapng"
        trace.writePcapng(tracePath)
        println(s"[trace] wrote pcapng trace to $tracePath")
        activeTraceConsumer = None
      }
    }
  }
}
