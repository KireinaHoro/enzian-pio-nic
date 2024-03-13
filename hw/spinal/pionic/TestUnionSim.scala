package pionic

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.sim.SimManagerContext

import scala.collection.mutable

case class MyUnion() extends Union {
  val a = newElement(UInt(64 bits))
  val b = newElement(Bits(64 bits))
}

object TestUnionSim extends App {
  val sim = SimConfig.withConfig(SpinalConfig()).withVerilator.allOptimisation.withFstWave

  val dut = sim.compile(new Component {
    val o = master(Event)
    val i = in(UInt(64 bits))
    o.valid := True
  })
  dut.doSim("access-union-member") { dut =>
    dut.clockDomain.forkStimulus(period = 4)

    val m = SimMutex()

    val q = mutable.Queue[Unit]()

    dut.i #= 3
    sleep(0)
    println(s"dut.i ${dut.i.toBigInt}")
    dut.i #= 4
    sleep(0)
    println(s"dut.i ${dut.i.toBigInt}")
    dut.i #= 5
    sleep(0)
    println(s"dut.i ${dut.i.toBigInt}")



    StreamReadyRandomizer(dut.o, dut.clockDomain)
    StreamMonitor(dut.o, dut.clockDomain) { _ =>
      // q.enqueue()

      fork { m.lock() }.join()
    }

    fork {
      while (true) {
        waitUntil(q.nonEmpty)
        q.dequeue()
        println(s"Locking...")
        m.lock()
      }
    }.join()
  }
}