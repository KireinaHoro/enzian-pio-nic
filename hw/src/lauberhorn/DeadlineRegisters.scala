package lauberhorn

import jsteward.blocks.misc.RegBlockAlloc
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.{RO, WO}

/** Kernel-serialized staging registers and atomic command/snapshot strobes.
  * Never expose these privileged CSRs in an application's mapping.
  */
class DeadlineRegisters(bus: BusSlaveFactory, alloc: RegBlockAlloc,
                        slots: Seq[DomainDeadline], now: UInt, minimumLead: BigInt) extends Area {
  val indexWidth = log2Up(slots.size)
  val next, expected, lifetime = UInt(64 bits)
  bus.drive(next, alloc("deadline", "Absolute device tick; zero cancels", "next", attr = WO))
  bus.drive(expected, alloc("deadline", "Expected publication generation", "expected", attr = WO))
  bus.drive(lifetime, alloc("deadline", "Bound domain lifetime code", "lifetime", attr = WO))
  val index = UInt(indexWidth bits)
  index := 0
  val command = alloc("deadline", "Atomic compare and replace on this domain", "command", attr = WO)
  bus.write(index, command)
  val result = Reg(UInt(3 bits)) init 0
  val generation = Reg(UInt(64 bits)) init 0
  slots.zipWithIndex.foreach { case (slot, i) =>
    slot.io.command.valid := False
    slot.io.command.next := next
    slot.io.command.expectedGeneration := expected
    slot.io.command.lifetime := lifetime
    bus.onWrite(command) {
      when(index === i) {
        slot.io.command.valid := True
        // 0 success, 1 unavailable, 2 stale, 3 too soon, 4 exhausted
        result := 0
        when(slot.io.unavailable) { result := 1 }
          .elsewhen(slot.io.stale) { result := 2 }
          .elsewhen(slot.io.exhausted) { result := 4 }
          .elsewhen(slot.io.tooSoon) { result := 3 }
        generation := slot.io.generation + slot.io.accepted.asUInt
      }
    }
  }
  bus.onWrite(command) { when(index >= slots.size) { result := 1 } }
  bus.read(result, alloc("deadline", "Result of atomic command", "result", attr = RO))
  bus.read(generation, alloc("deadline", "Generation after command", "generation", attr = RO))
  bus.read(now, alloc("deadline", "Live device clock ticks", "now", attr = RO))
  bus.read(U(minimumLead, 64 bits), alloc("deadline", "Minimum publication lead in ticks", "minimumLead", attr = RO))
  val snapshotIndex = UInt(indexWidth bits)
  snapshotIndex := 0
  val snapshot = alloc("deadline", "Latch consistent domain timer state", "snapshot", attr = WO)
  bus.write(snapshotIndex, snapshot)
  val stateGeneration, stateNext = Reg(UInt(64 bits)) init 0
  val statePending, stateClaimed = RegInit(False)
  bus.onWrite(snapshot) {
    stateGeneration := 0; stateNext := 0; statePending := False; stateClaimed := False
    slots.zipWithIndex.foreach { case (slot, i) =>
      when(snapshotIndex === i) {
        stateGeneration := slot.io.generation
        stateNext := slot.io.next
        statePending := slot.io.pending
        stateClaimed := slot.io.claimed
      }
    }
  }
  bus.read(stateGeneration, alloc("deadline", "Snapshot generation", "stateGeneration", attr = RO))
  bus.read(stateNext, alloc("deadline", "Snapshot next tick", "stateNext", attr = RO))
  bus.read(statePending, alloc("deadline", "Snapshot pending expiry", "statePending", attr = RO))
  bus.read(stateClaimed, alloc("deadline", "Snapshot claimed notification", "stateClaimed", attr = RO))
}
