package lauberhorn.host.eci

import spinal.core._

/** Allocation survives application reset. Only FPGA initialization at a full
  * coherence-reset boundary clears lifetime state. Exhaustion never wraps. */
case class EciDomainAllocator(domainBits: Int = EciAddressMap.domain_bits) extends Component {
  require(domainBits > 0 && domainBits <= EciAddressMap.domain_bits)
  val io = new Bundle {
    val allocate = in Bool()
    val fatal = in Bool()
    val appReset = in Bool()
    val next = out UInt(domainBits + 1 bits)
    val resetRequired = out Bool()
  }
  val persistent = new ClockingArea(ClockDomain.current.withBootReset()) {
    val next = Reg(UInt(domainBits + 1 bits)) init 0
    val resetRequired = RegInit(False)
    when(io.appReset && next =/= 0) { resetRequired := True }
    when(!io.appReset) {
      when(io.fatal) { resetRequired := True }
      when(io.allocate && !resetRequired && !io.fatal && next < (BigInt(1) << domainBits)) {
        next := next + 1
      }
    }
  }
  io.next := persistent.next
  io.resetRequired := persistent.resetRequired
}
