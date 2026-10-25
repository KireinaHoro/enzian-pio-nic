package lauberhorn

import jsteward.blocks.DutSimFunSuite
import spinal.core.sim._

class SchedulerAllocationSim extends DutSimFunSuite[SchedulerAllocation] {
  val dut = Config.sim.compile(new SchedulerAllocation(4, 4, 16))
  test("reservation-limits-watermark-rechecks-and-fair-deadlines") { implicit dut =>
    SimTimeout(20000)
    dut.clockDomain.forkStimulus(4)
    dut.io.enabled #= 30
    dut.io.maximum.foreach(_ #= 4)
    dut.io.maximum(0) #= 0
    dut.io.fill.foreach(_ #= 0)
    dut.io.owner.foreach(_ #= 0)
    dut.io.available #= 15
    dut.io.ready #= 15
    dut.io.deadlines #= 0
    dut.io.arrival.valid #= false
    dut.io.arrival.payload #= 1
    dut.io.arrivalFill #= 0
    dut.clockDomain.waitSampling(10)
    def settle(): Unit = sleep(1)
    def tick(): Unit = { dut.clockDomain.waitSampling(); settle() }
    def grant(slot: Int, core: Int, reason: AllocationReason.E): Unit = {
      settle()
      assert(dut.io.grant.valid.toBoolean)
      assert(dut.io.grant.slot.toInt == slot)
      assert(dut.io.grant.core.toInt == core)
      assert(dut.io.grant.reason.toEnum == reason)
    }
    dut.io.arrival.valid #= true
    grant(1, 0, AllocationReason.initialPacket)
    dut.io.owner(0) #= 1; settle()
    assert(!dut.io.grant.valid.toBoolean) // reservation already counts
    dut.io.arrivalFill #= 8
    grant(1, 1, AllocationReason.packetBacklog)
    dut.io.owner.foreach(_ #= 2)
    dut.io.fill(2) #= 16
    grant(1, 0, AllocationReason.packetBacklog) // packet scaling permits busy donors
    dut.io.arrival.valid #= false
    dut.io.deadlines #= 2
    settle(); assert(!dut.io.grant.valid.toBoolean)
    dut.io.fill(2) #= 9
    settle(); assert(!dut.io.grant.valid.toBoolean)
    dut.io.fill(2) #= 8
    grant(1, 0, AllocationReason.deadline)
    dut.io.available #= 14
    grant(1, 1, AllocationReason.deadline) // no in-flight core can be reserved
    dut.io.owner(1) #= 1
    settle(); assert(!dut.io.grant.valid.toBoolean) // no second timer core
    dut.io.owner(1) #= 2
    dut.io.maximum(1) #= 0
    settle(); assert(!dut.io.grant.valid.toBoolean)
    dut.io.maximum(1) #= 4
    dut.io.ready #= 0
    settle(); assert(!dut.io.grant.valid.toBoolean) // handler never preempted
    dut.io.owner(3) #= 0
    grant(1, 3, AllocationReason.deadline)
    // Background discovery requires a fully drained donor; first domain wins.
    dut.io.deadlines #= 0
    dut.io.owner(3) #= 2
    dut.io.ready #= 15
    dut.io.fill(1) #= 1
    dut.io.fill(3) #= 1
    settle(); assert(!dut.io.grant.valid.toBoolean)
    dut.io.fill(2) #= 0
    grant(1, 1, AllocationReason.background)
    dut.io.fill(1) #= 0; dut.io.fill(3) #= 0
    // Repeated eligible expiries rotate even when no grant gets acknowledged
    // by a test driver; actual scheduler commits owner on each granted edge.
    dut.io.available #= 15
    dut.io.deadlines #= 10 // domains 1 and 3
    grant(1, 0, AllocationReason.deadline); tick()
    grant(3, 0, AllocationReason.deadline); tick()
    grant(1, 0, AllocationReason.deadline)
    dut.io.arrival.valid #= true
    dut.io.arrival.payload #= 4
    dut.io.arrivalFill #= 8
    var packets = 0
    var timers = 0
    for (_ <- 0 until 20) {
      settle()
      assert(dut.io.grant.valid.toBoolean)
      if (dut.io.grant.reason.toEnum == AllocationReason.deadline) timers += 1 else packets += 1
      tick()
    }
    assert(packets == 10 && timers == 10)
    dut.io.enabled #= 0
    settle(); assert(!dut.io.grant.valid.toBoolean)
  }
}
