import spinal.core._
import spinal.lib.{Stream, _}
import spinal.lib.bus.misc._

import scala.language.postfixOps

package object pionic {
  def checkStreamValidDrop[T <: Data](s: Stream[T]) = {
    assert(
      assertion = !(s.valid.fall && ((!s.ready && !s.ready.fall) || s.ready.rise)),
      message = s"${s.getName()}: Valid dropped when ready was low",
      severity = FAILURE
    )
  }

  def Timestamp(implicit config: PioNicConfig) = UInt(config.timestampWidth bits)

  object StreamDispatcherWithEnable {
    def apply[T <: Data](input: Stream[T], outputCount: Int, enableMask: Bits): Vec[Stream[T]] = new ImplicitArea[Vec[Stream[T]]] {
      // FIXME: same as OHMasking.roundRobin?
      // FIXME: first packet always goes to core 0
      assert(outputCount == enableMask.getWidth, "enable mask bit width does not match with output count")
      val select = Reg(UInt(log2Up(outputCount) bits)) init 0
      val doubleMask = enableMask ## enableMask
      val shiftedMask = doubleMask >> (select + 1)
      val inc = CountTrailingZeroes(shiftedMask.resize(outputCount)) + 1
      when(input.fire) {
        select := select + inc.resized
      }
      val implicitValue = StreamDemux(input, select, outputCount)
    }.setCompositeName(input, "streamDispatch", true)
  }

  implicit class RichUInt(v: UInt) {
    def toPacketLength(implicit config: PioNicConfig) = {
      val len = PacketLength()
      len.bits := v
      len
    }
  }
}
