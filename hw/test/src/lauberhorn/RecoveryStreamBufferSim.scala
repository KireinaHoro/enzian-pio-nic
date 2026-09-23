package lauberhorn

import jsteward.blocks.DutSimFunSuite
import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

class RecoveryStreamBufferSim extends DutSimFunSuite[RecoveryStreamBuffer[Bits]] {
  val dut = Config.sim.compile(new RecoveryStreamBuffer(Bits(16 bits), 16))
  test("retains-every-owner-under-stalls-and-sustains-one-per-cycle") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    dut.io.push.valid #= false
    dut.io.push.payload #= 0
    dut.io.pushOwner #= 0
    dut.io.pop.ready #= false
    dut.clockDomain.waitSampling(20)
    val pending = mutable.Queue[(Int, Int)]()
    val random = new scala.util.Random(0xb4)
    var submitted = 0
    var retired = 0
    for(cycle <- 0 until 1100) {
      val producing = cycle < 1000
      val ready = if(cycle < 64 || cycle >= 1000) true
        else if(cycle < 96) false else random.nextInt(4) != 0
      dut.io.push.valid #= producing
      dut.io.push.payload #= submitted
      dut.io.pushOwner #= submitted % 3 + 1
      dut.io.pop.ready #= ready
      sleep(1)
      val push = producing && dut.io.push.ready.toBoolean
      val pop = ready && dut.io.pop.valid.toBoolean
      if(cycle > 0 && cycle < 64) assert(push && pop, "lost steady-state throughput")
      if(pop) {
        assert(pending.nonEmpty)
        assert(dut.io.pop.payload.toInt == pending.dequeue()._1)
        retired += 1
      }
      if(push) {
        pending.enqueue((submitted, submitted % 3 + 1))
        submitted += 1
      }
      dut.clockDomain.waitSampling()
      sleep(1)
      val owners = dut.io.owners.filter(_.valid.toBoolean).map(_.payload.toInt).sorted
      assert(owners == pending.map(_._2).toSeq.sorted,
        "drain ownership lost, duplicated, or retained after retirement")
      assert(dut.io.pop.valid.toBoolean == pending.nonEmpty)
      if(pending.nonEmpty) assert(dut.io.pop.payload.toInt == pending.front._1)
      if(cycle >= 66 && cycle < 96) assert(pending.size == 2)
    }
    assert(submitted > 600 && submitted == retired && pending.isEmpty)
  }
}

class RecoveryInFlightSim extends DutSimFunSuite[RecoveryInFlight] {
  val dut = Config.sim.compile(new RecoveryInFlight(9, 2))
  test("staged-arrivals-and-departures-never-report-early-drain") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    dut.io.arrivals #= 0
    dut.io.departure #= false
    dut.clockDomain.waitSampling(20)
    var live = 0
    def step(mask: Int, departure: Boolean): Unit = {
      val previous = live
      dut.io.arrivals #= mask
      dut.io.departure #= departure
      sleep(1)
      if(mask != 0) assert(dut.io.busy.toBoolean, "new admission is invisible to drain")
      live += Integer.bitCount(mask) - (if(departure) 1 else 0)
      assert(live >= 0 && live <= 9)
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.count.toInt == previous)
      if(live != 0) assert(dut.io.busy.toBoolean, "accounting pipeline reported early drain")
    }
    for(_ <- 0 until 4) step(3, false)
    step(1, false)
    for(_ <- 0 until 9) step(0, true)
    step(0, false)
    assert(!dut.io.busy.toBoolean)
    val random = new scala.util.Random(0xb4)
    for(_ <- 0 until 500) {
      val departure = live != 0 && random.nextBoolean()
      val capacity = math.min(2, 9 - live + (if(departure) 1 else 0))
      val arrivals = random.nextInt(capacity + 1)
      val mask = if(arrivals == 2) 3 else if(arrivals == 1) 1 << random.nextInt(2) else 0
      step(mask, departure)
    }
    while(live != 0) step(0, true)
    step(0, false)
    assert(!dut.io.busy.toBoolean)
  }
}
