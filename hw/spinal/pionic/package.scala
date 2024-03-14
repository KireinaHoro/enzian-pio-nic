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
  object CLZ {
    // https://electronics.stackexchange.com/a/649761
    def apply(v: Bits): UInt = new ImplicitArea[UInt] {
      val w = v.getWidth // input width
      assert(w % 2 == 0 && w > 0, s"cannot do clz for width $w")
      val ow = log2Up(w) + 1 // output width
      val olrw = ow - 1 // output width of halves
      val implicitValue: UInt = w match {
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
    }.setCompositeName(v, "clz", true)
  }

  object CTZ {
    def apply(v: Bits): UInt = CLZ(v.reversed).setCompositeName(v, "ctz", true)
  }

  object StreamDispatcherWithEnable {
    def apply[T <: Data](input: Stream[T], outputCount: Int, enableMask: Bits): Vec[Stream[T]] = new ImplicitArea[Vec[Stream[T]]] {
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

  def writeHeader(outPath: os.Path)(implicit config: PioNicConfig): Unit = {
    import config._
    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_CONFIG_H__
          |#define __PIONIC_CONFIG_H__
          |
          |#define PIONIC_NUM_CORES $numCores
          |#define PIONIC_PKT_ADDR_WIDTH $pktBufAddrWidth
          |#define PIONIC_PKT_ADDR_MASK ((1 << PIONIC_PKT_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_LEN_WIDTH $pktBufLenWidth
          |#define PIONIC_PKT_LEN_MASK ((1 << PIONIC_PKT_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${Config.spinal().defaultClockDomainFrequency.getValue.toLong}
          |
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }
}
