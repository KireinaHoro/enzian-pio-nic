package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn.sim._
import spinal.core.sim._
import jsteward.blocks.misc.sim.IntRicherEndianAware
import spinal.lib.BytesRicher

/** Full engine, actual AXI crossbar and coherent host model. Silicon/DCS RTL
  * progress is checked separately; delaying LCIA here is a finite observation. */
class CooperativeYieldSim extends NicSim with OncRpcSuiteFactory {
  override def numWorkerCores = 1

  testWithDB("ordinary-handoff-delays-control-without-irq-and-preserves-a-b-a")(Rx) { implicit dut =>
    val (csr, packets, _, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    dcs.voluntaryInvProb = 0
    val domains = Seq.fill(2)(mkRandomProc(1))
    val factories = oncRpcCallPacketFactory(csr, domains.map(_ -> Seq(RpcSrvDef.mkRandom)))
    def send(domain: Int): Unit = {
      val (packet, _, _) = factories(domain)._2()
      packets.send(packet.getRawData.toList)
    }
    val worker = workerCore(0)
    send(0)
    worker.waitUser()
    assert(schedulingIrqs == 1)
    assert(worker.currThread.get.proc == domains(0))
    assert(tryReadPacketDesc(dcs, worker.currThread.get.tid).result.nonEmpty)
    // Duplicate idle kicks must not consume another PreemptInfo snapshot.
    genericIrqCb(csr, 8, 1)
    genericIrqCb(csr, 8, 1)

    for (destination <- Seq(1, 0, 1, 0)) {
      val old = worker.currThread.get
      val etd = getEciThreadData(old.tid)
      send(destination)
      var preempt = false
      while (!preempt) {
        val control = dcs.read(etd.baseAddr + ECI_RX_BASE.get + etd.rxNextCl * 128, 64).bytesToBigInt
        etd.flipRx()
        assert((control & 1) == 0, "PREEMPT consumed a queued network packet")
        preempt = (control & 3) == 2
      }
      sleepCycles(100) // Delayed yield retains the old assignment without an IRQ.
      assert(worker.currThread.contains(old) && schedulingIrqs == 1)
      dcs.pauseInvalidationResponses = true
      var yielded = false
      fork {
        cooperativeYield(csr, 1)
        yielded = true
      }
      waitUntil(dcs.invalidationsAwaitingReply > 0)
      var otherControlDone = false
      fork {
        assert(csr.read(ALLOC.readBack("ownership")("abi"), 8).bytesToBigInt == 2)
        otherControlDone = true
      }
      var duplicateDone = false
      fork { genericIrqCb(csr, 8, 1); genericIrqCb(csr, 8, 1); duplicateDone = true }
      sleepCycles(100)
      assert(!yielded && !otherControlDone, "blocked info read should retain the shared control bus")
      assert(schedulingIrqs == 1)
      dcs.pauseInvalidationResponses = false // autonomous transport service resumes
      waitUntil(yielded && otherControlDone && duplicateDone)
      assert(worker.currThread.get.proc == domains(destination))
      assert(tryReadPacketDesc(dcs, worker.currThread.get.tid).result.nonEmpty)
      assert(schedulingIrqs == 1, "ordinary handoff generated a scheduling IRQ")
    }
  }
  testWithDB("fatal-domain-fence-preserves-independent-current-owner")(Rx) { implicit dut =>
    val (csr, packets, _, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val domains = Seq.fill(2)(mkRandomProc(1))
    val factories = oncRpcCallPacketFactory(csr, domains.map(_ -> Seq(RpcSrvDef.mkRandom)))
    val worker = workerCore(0)
    def sendAndReceive(): Unit = {
      val (packet, _, _) = factories(0)._2()
      packets.send(packet.getRawData.toList)
      worker.waitUser()
      assert(worker.currThread.get.proc == domains(0))
      assert(tryReadPacketDesc(dcs, worker.currThread.get.tid).result.nonEmpty)
    }
    sendAndReceive()
    var faults = 0
    domainFaultHandler = () => { faults += 1 }
    val regs = ALLOC.readBack("ownership")
    csr.write(regs("failDomain"), domains(1).slot.toBytesLE)
    waitUntil(faults == 1)
    assert(csr.read(regs("failedDomains"), 8).bytesToBigInt == (BigInt(1) << domains(1).slot))
    assert(csr.read(regs("resetRequired"), 8).bytesToBigInt == 1)
    sendAndReceive()
    assert(schedulingIrqs == 1 && faults == 1)
  }

  testWithDB("worker-tx-rejects-foreign-pid-and-oversized-descriptor")(Rx) { implicit dut =>
    val (csr, packets, output, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val domains = Seq.fill(2)(mkRandomProc(1))
    val factories = oncRpcCallPacketFactory(csr, domains.map(_ -> Seq(RpcSrvDef.mkRandom)))
    val (packet, _, _) = factories(0)._2()
    packets.send(packet.getRawData.toList)
    val worker = workerCore(0)
    worker.waitUser()
    val tid = worker.currThread.get.tid
    assert(tryReadPacketDesc(dcs, tid).result.nonEmpty)
    var transmitted = false
    fork { output.recv(); transmitted = true }
    def call(length: Int, pid: Int) = TxOncRpcCallSim(length, pid, 1, 2,
      BigInt("c0a88101", 16), 12345, 12346, 1, 1, 1, 0)
    txSendSingle(dcs, call(0, domains(1).pid), Nil, tid, expectedStatus = 3)
    txSendSingle(dcs, call(3000, domains(0).pid), Nil, tid, expectedStatus = 3)
    // Rejection must finish coherently and permit the next valid submission.
    txSendSingle(dcs, TxExecutionSim(1), Nil, tid)
    sleepCycles(100)
    assert(!transmitted && schedulingIrqs == 1)
  }

}
