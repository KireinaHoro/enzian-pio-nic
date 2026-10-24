package lauberhorn

import spinal.core._
import spinal.lib._

/** One acknowledged, payload-free timer slot. Time is in device ticks; the
  * kernel converts CLOCK_MONOTONIC at the control boundary. Commands carry the
  * domain lifetime as well as the compare-and-replace publication generation.
  * Claim is selection for RX, not acknowledgement. Only a successful command
  * or lifetime retirement clears pending work.
  */
class DomainDeadline(timeWidth: Int = 64, generationWidth: Int = 64,
                     lifetimeWidth: Int = 64, minimumLead: BigInt = 1) extends Component {
  require(timeWidth >= 2 && generationWidth >= 2 && lifetimeWidth > 0)
  require(minimumLead > 0 && minimumLead < (BigInt(1) << (timeWidth - 1)))
  val io = new Bundle {
    val now = in UInt(timeWidth bits)
    val enabled = in Bool()
    val lifetime = in UInt(lifetimeWidth bits)
    val command = slave Flow(new Bundle {
      val lifetime = UInt(lifetimeWidth bits)
      val expectedGeneration = UInt(generationWidth bits)
      val next = UInt(timeWidth bits) // zero cancels
    })
    val claim = in Bool()
    val generation = out UInt(generationWidth bits)
    val next = out UInt(timeWidth bits)
    val pending, claimed, runnable = out Bool()
    // Combinational command result; capture with command.valid at the CSR boundary.
    val accepted, stale, tooSoon, unavailable, exhausted = out Bool()
  }
  val generation = Reg(UInt(generationWidth bits)) init 0
  val next = Reg(UInt(timeWidth bits)) init 0
  val pending, claimed = RegInit(False)
  val lifetime = RegNext(io.lifetime) init 0
  val retired = !io.enabled || lifetime =/= io.lifetime
  val lead = (io.command.next - io.now).resize(timeWidth)
  val elapsed = (io.now - next).resize(timeWidth)
  io.unavailable := retired
  io.stale := io.command.lifetime =/= io.lifetime || io.command.expectedGeneration =/= generation
  io.tooSoon := io.command.next =/= 0 && (lead.msb || lead < minimumLead)
  io.exhausted := generation.andR
  io.accepted := !io.unavailable && !io.stale && !io.tooSoon && !io.exhausted
  when(retired) {
    generation := 0
    next := 0
    pending := False
    claimed := False
  } otherwise {
    when(next =/= 0 && !elapsed.msb) { pending := True }
    when(io.claim && pending && !claimed) { claimed := True }
    // Supersession wins a simultaneous expiry/claim. A descriptor already
    // selected retains its old generation and cannot acknowledge this new one.
    when(io.command.valid && io.accepted) {
      generation := generation + 1
      next := io.command.next
      pending := False
      claimed := False
    }
  }
  io.generation := generation
  io.next := next
  io.pending := pending
  io.claimed := claimed
  io.runnable := pending && !claimed && !retired
}
