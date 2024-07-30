package pionic.net

import spinal.core._

// IPv4
case class IpMetadata() extends Bundle {
  val proto = Bits(8 bits)
  val saddr = Bits(32 bits)
  val daddr = Bits(32 bits)
}
