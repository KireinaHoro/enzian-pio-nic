package lauberhorn.net

import spinal.core._

import scala.language.postfixOps

package object udp {
  case class UdpHeader() extends Bundle {
    val sport = Bits(16 bits)
    val dport = Bits(16 bits)
    val len = Bits(16 bits)
    val csum = Bits(16 bits)
  }
}
