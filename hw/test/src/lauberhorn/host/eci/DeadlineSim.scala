package lauberhorn.host.eci

import jsteward.blocks.misc.sim.IntRicherEndianAware
import lauberhorn.Global._
import lauberhorn.sim._
import spinal.core.sim._
import spinal.lib._

/** Real CSR, scheduler, idle activation and coherent RX composition; no packets
  * or packet-buffer allocations are used to trigger these notifications. */
class DeadlineSim extends NicSim {
  testWithDB("deadline-only-activation-acknowledgement-and-control-retirement", timeout = 10000000000L)(Rx, Tx) { implicit dut =>
    val (csr, _, _, dcs) = commonDutSetup(100)
    val proc = mkRandomProc(4)
    proc.enableAt(1, csr)
    val regs = ALLOC.readBack("sched")
    def read(name: String): BigInt = csr.read(regs("deadline", name), 8).bytesToBigInt
    def write(name: String, value: BigInt): Unit =
      csr.write(regs("deadline", name), spinal.core.sim.SimBigIntPimper(value).toBytes(64).toList)
    def snapshot(): Unit = write("snapshot", 1)
    def publish(expected: Int, next: BigInt, result: Int = 0): Unit = {
      write("next", next)
      write("expected", expected)
      write("lifetime", proc.domainCode)
      write("command", 1)
      assert(read("result") == result)
    }
    snapshot()
    assert(read("stateGeneration") == 0 && read("statePending") == 0)
    publish(0, read("now") + 1, 3)
    snapshot(); assert(read("stateGeneration") == 0)
    assert(proc.threads.forall(_.runningOn.isEmpty))
    val lead = read("minimumLead")
    publish(0, read("now") + lead + 5000)
    workerCore(0).waitUser()
    assert(proc.threads.count(_.runningOn.nonEmpty) == 1)
    val tid = workerCore(0).currThread.get.tid
    for (generation <- 1 to 3) {
      val (desc, _) = tryReadPacketDesc(dcs, tid).result.get
      assert(desc == RxDeadlineSim(generation))
      snapshot()
      assert(read("statePending") == 1 && read("stateClaimed") == 1)
      publish(generation - 1, 0, 2) // a stale ACK leaves the event intact
      snapshot(); assert(read("statePending") == 1)
      // The next RX retires the control cacheline without a packet free and
      // must return NACK, never a duplicate generation.
      assert(tryReadPacketDesc(dcs, tid, maxTries = 1).result.isEmpty)
      assert(proc.threads.count(_.runningOn.nonEmpty) == 1)
      publish(generation, if (generation == 3) BigInt(0) else read("now") + lead + 5000)
      if (generation != 3) sleepCycles(lead.toInt + 6000)
    }
    snapshot()
    assert(read("stateGeneration") == 4 && read("statePending") == 0 && read("stateNext") == 0)
    assert(tryReadPacketDesc(dcs, tid, maxTries = 1).result.isEmpty)
  }
}
