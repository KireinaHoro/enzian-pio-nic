import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

import lauberhorn.Global._
import spinal.lib.misc.database.Element.toValue

import scala.language.postfixOps

package object lauberhorn {
  implicit class RichUInt(v: UInt) {
    def toPacketLength = {
      val len = PacketLength()
      len.bits := v
      len
    }
  }

  /**
    * Address of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketAddr() extends Bundle {
    override def clone = PacketAddr()

    val bits = UInt(PKT_BUF_ADDR_WIDTH bits)
  }

  /**
    * Length of a packet payload (of any protocol) in the packet buffer.
    */
  case class PacketLength() extends Bundle {
    override def clone = PacketLength()

    val bits = UInt(PKT_BUF_LEN_WIDTH bits)
  }

  /*
  // FIXME: remove after all sim usages have been migrated
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
   */
}
