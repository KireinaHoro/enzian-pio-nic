package pionic

import jsteward.blocks.eci.LclChannel
import spinal.core._
import spinal.lib._

import spinal.lib.misc.plugin.FiberPlugin

case class TestBundle() extends Bundle {
  val xb5 = Bits(5 bits).assignDontCare()
  val u = UInt(32 bits)
}

case class TestUnion() extends Union {
  val b = newElement(TestBundle())
}

object TestStreamUnion extends App {
  SpinalVerilog(new Component {
    val a = out(TestUnion())
    val b = out(TestBundle())
    b.u := U(7, 32 bits)
    a.b.get := b
  })
}

trait XService {
  def a: Stream[UInt]
}

class PluginX extends FiberPlugin with XService {
  val a = during setup Stream(UInt(4 bits))
}