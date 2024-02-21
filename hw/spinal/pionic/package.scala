import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._

import scala.language.postfixOps

package object pionic {
  def Timestamp(implicit config: PioNicConfig) = UInt(config.timestampWidth bits)
  object CLZ {
    // https://electronics.stackexchange.com/a/649761
    def apply(v: Bits): UInt = new Composite(v, "clz") {
      val w = v.getWidth // input width
      assert(w % 2 == 0, s"cannot do clz for odd width $w")
      val ow = log2Up(w) + 1 // output width
      val olrw = ow - 1 // output width of halves
      val value: UInt = w match {
        // encode
        case 2 => v.mux(
          0 -> U(2, ow bits),
          1 -> U(1, ow bits),
          default -> U(0, ow bits),
        )
        // assemble
        case _ =>
          val clzL = CLZ(v(w / 2, w / 2 bits))
          val clzR = CLZ(v(0, w / 2 bits))
          val first = clzL(olrw - 1) & clzR(olrw - 1)
          val mux = Mux(~clzL(olrw - 1),
            U("0") ## clzL(0, olrw - 1 bits),
            (~clzR(olrw - 1)) ## clzR(0, olrw - 1 bits))
          (first ## mux).asUInt
      }
    }.value
  }

  object CTZ {
    def apply(v: Bits): UInt = CLZ(v.reversed)
  }

  object StreamDispatcherWithEnable {
    def apply[T <: Data](input: Stream[T], outputCount: Int, enableMask: Bits): Vec[Stream[T]] = {
      // FIXME: same as OHMasking.roundRobin?
      // FIXME: first packet always goes to core 0
      assert(outputCount == enableMask.getWidth, "enable mask bit width does not match with output count")
      val select = Reg(UInt(log2Up(outputCount) bits)) init 0
      val doubleMask = enableMask ## enableMask
      val shiftedMask = doubleMask >> (select + 1)
      val inc = CTZ(shiftedMask.resize(outputCount)) + 1
      when(input.fire) {
        select := select + inc.resized
      }
      StreamDemux(input, select, outputCount)
    }
  }

  implicit class RichUInt(v: UInt) {
    def toPacketLength(implicit config: PioNicConfig) = {
      val len = PacketLength()
      len.bits := v
      len
    }
  }
}
