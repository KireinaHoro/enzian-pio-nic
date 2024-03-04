package pionic

import jsteward.blocks.eci.LclChannel
import spinal.core._
import spinal.lib._

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
