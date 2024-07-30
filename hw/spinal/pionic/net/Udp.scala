package pionic.net

import spinal.core._

case class UdpMetadata() extends Bundle {
  val sport = Bits(16 bits)
  val dport = Bits(16 bits)
}

class Udp {

}
