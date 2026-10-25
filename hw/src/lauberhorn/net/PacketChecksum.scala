package lauberhorn.net

import spinal.core._
import spinal.lib._

/** Packet checksum with two registered reduction stages. Byte-lane selection
  * belongs to the caller; only accepted beats enter the pipeline. Seed before
  * the first beat and wait for busy to clear before inspecting the result or
  * starting another packet. The 32-bit sum also bounds malformed frame drains.
  */
class PacketChecksum(lanes: Int) extends Component {
  val io = new Bundle {
    val seed = slave Flow(UInt(32 bits))
    val beat = slave Flow(Vec(UInt(16 bits), lanes))
    val sum = out UInt(32 bits)
    val busy = out Bool()
  }
  val partials = Vec(io.beat.payload.grouped(8).map { group =>
    RegNextWhen(group.map(_.resize(32)).reduceBalancedTree(_ + _), io.beat.valid)
  }.toSeq)
  val partialValid = RegNext(io.beat.valid) init False
  val subtotal = RegNextWhen(partials.reduceBalancedTree(_ + _), partialValid)
  val subtotalValid = RegNext(partialValid) init False
  val sum = Reg(UInt(32 bits)) init 0
  when(subtotalValid) { sum := sum + subtotal }
  when(io.seed.valid) {
    assert(!partialValid && !subtotalValid, "checksum reseeded before pipeline drained")
    sum := io.seed.payload
  }
  io.sum := sum
  io.busy := partialValid || subtotalValid
}
