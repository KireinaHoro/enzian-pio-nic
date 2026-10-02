package lauberhorn.host.eci

import spinal.core._
import spinal.lib._

/** Sticky fatal faults, indexed by trusted slot bindings, not current worker.
  * Kernel kills the faulting domain and retains its resources until reset.
  * No notification ACK clears a fault or authorizes domain/slot reuse.
  */
case class EciDomainFaults(slots: Int, sources: Int) extends Component {
  val io = new Bundle {
    val codes = in Vec(UInt(EciAddressMap.domain_bits bits), slots)
    val bound = in Bits(slots bits)
    val violations = Vec(slave(Flow(UInt(EciAddressMap.domain_bits bits))), sources)
    val failed = out Bits(slots bits)
    val interrupt = master(Stream(Bool()))
  }
  val failed = Reg(Bits(slots bits)) init 0
  val reported = Reg(Bits(slots bits)) init 0
  val fresh = Bits(slots bits)
  for (slot <- 0 until slots) {
    fresh(slot) := io.bound(slot) && io.violations.map(v =>
      v.valid && v.payload === io.codes(slot)).reduce(_ || _)
  }
  failed := failed | fresh
  io.failed := failed
  io.interrupt.valid := (failed & ~reported).orR
  io.interrupt.payload := True
  when(io.interrupt.fire) { reported := failed }
}
