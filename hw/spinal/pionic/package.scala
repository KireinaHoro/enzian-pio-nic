import spinal.core._
import spinal.lib._

import scala.language.postfixOps

package object pionic {
  object StreamDispatcherWithEnable {
    def apply[T <: Data](
        input: Stream[T],
        outputCount: Int,
        enableMask: Bits,
        maskChanged: Bool
    ): Vec[Stream[T]] = new ImplicitArea[Vec[Stream[T]]] {
      // FIXME: same as OHMasking.roundRobin?
      assert(
        outputCount == enableMask.getWidth,
        "enable mask bit width does not match with output count"
      )
      val select = Reg(UInt(log2Up(outputCount) bits))

      // reset select when mask changes
      // FIXME: can this happen when a request is ongoing?
      when(maskChanged) {
        select := CountTrailingZeroes(enableMask).resized
      }

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

  /**
    * Address of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketAddr()(implicit config: PioNicConfig) extends Bundle {
    override def clone = PacketAddr()

    val bits = UInt(config.pktBufAddrWidth bits)
  }

  /**
    * Length of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketLength()(implicit config: PioNicConfig) extends Bundle {
    override def clone = PacketLength()

    val bits = UInt(config.pktBufLenWidth bits)
  }
}
