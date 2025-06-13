import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps
import scala.reflect.runtime.universe._

package object pionic {
  abstract class PioNicPlugin extends FiberPlugin {
    implicit lazy val c = host[ConfigDatabase]

    // alias commonly used config values
    lazy val numWorkerCores = c[Int]("num worker cores")
    lazy val numCores = numWorkerCores + 1 // with bypass
    lazy val regWidth = c[Int]("reg width")

    lazy val mtu = c[Int]("mtu")
    lazy val roundMtu = c[Int]("rounded mtu")

    lazy val pktBufAddrWidth = c[Int]("pkt buf addr width")
    lazy val pktBufLenWidth = c[Int]("pkt buf len width")
    lazy val pktBufAddrMask = (BigInt(1) << pktBufAddrWidth) - BigInt(1)
    lazy val pktBufLenMask = (BigInt(1) << pktBufLenWidth) - BigInt(1)
    lazy val pktBufSize = numCores * c[Int]("pkt buf size per core")

    assert(log2Up(pktBufSize) <= pktBufAddrWidth, "not the entire packet buffer is addressable!")

    def postConfig[T: TypeTag](name: String, value: => T, action: ConfigDatabase.PostAction = ConfigDatabase.OneShot): Unit = {
      during setup c.post(name, value, action)
    }
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

    val bits = UInt(Widths.aw bits)
  }

  /**
    * Length of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketLength()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = PacketLength()

    val bits = UInt(Widths.lw bits)
  }

  object Widths {
    def aw(implicit c: ConfigDatabase) = c[Int]("pkt buf addr width")
    def lw(implicit c: ConfigDatabase) = c[Int]("pkt buf len width")
    def tw(implicit c: ConfigDatabase) = c[Int]("host packet desc type width")
    def dw(implicit c: ConfigDatabase) = c[Int]("host desc size")
    def bptw(implicit c: ConfigDatabase) = c[Int]("proto packet desc type width")
    def bphw(implicit c: ConfigDatabase) = c[Int]("bypass header max width")
    def oargw(implicit c: ConfigDatabase) = c[Int]("max onc rpc inline bytes") * 8

    def pidw(implicit c: ConfigDatabase) = c[Int]("process id width")
  }
}
