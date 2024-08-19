import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps
import scala.reflect.runtime.universe._

package object pionic {
  abstract class PioNicPlugin extends FiberPlugin {
    implicit lazy val c = host[ConfigDatabase]

    // alias commonly used config values
    lazy val numCores = c[Int]("num cores")
    lazy val regWidth = c[Int]("reg width")

    lazy val mtu = c[Seq[(Int, Double)]]("pkt buf alloc size map").map(_._1).max
    lazy val roundMtu = roundUp(mtu, c[Int]("axis data width")).toInt

    lazy val pktBufAddrWidth = c[Int]("pkt buf addr width")
    lazy val pktBufLenWidth = c[Int]("pkt buf len width")
    lazy val pktBufAddrMask = (BigInt(1) << pktBufAddrWidth) - BigInt(1)
    lazy val pktBufLenMask = (BigInt(1) << pktBufLenWidth) - BigInt(1)
    lazy val pktBufSize = numCores * c[Int]("pkt buf size per core")

    def postConfig[T: TypeTag](name: String, value: T): Unit = {
      during setup c.postConfig(name, value)
    }
  }

  class RegAlloc extends FiberPlugin {
    val f = new RegAllocatorFactory
  }

  implicit class RichUInt(v: UInt) {
    def toPacketLength(implicit c: ConfigDatabase) = {
      val len = PacketLength()
      len.bits := v
      len
    }
  }

  /**
    * Address of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketAddr()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = PacketAddr()

    val bits = UInt(c[Int]("pkt buf addr width") bits)
  }

  /**
    * Length of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketLength()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = PacketLength()

    val bits = UInt(c[Int]("pkt buf len width") bits)
  }
}
