package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn.sim._
import spinal.core.sim._
import jsteward.blocks.misc.sim.IntRicherEndianAware

class CooperativeAllocationSim extends NicSim with OncRpcSuiteFactory {
  override def numWorkerCores = 3
  testWithDB("watermark-allocation-prefers-idle-worker-over-ready-owner")(Rx) { implicit dut =>
    val (csr, packets, _, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val domains = Seq(mkRandomProc(1), mkRandomProc(2))
    val factories = oncRpcCallPacketFactory(csr, domains.map(_ -> Seq(RpcSrvDef.mkRandom)))
    def send(index: Int): Unit = {
      val (packet, _, _) = factories(index)._2()
      packets.send(packet.getRawData.toList)
    }
    send(0)
    workerCore(0).waitUser()
    assert(tryReadPacketDesc(dcs, workerCore(0).currThread.get.tid).result.nonEmpty)
    txSendSingle(dcs, TxExecutionSim(1), Nil, workerCore(0).currThread.get.tid)
    val aThread = workerCore(0).currThread.get.tid
    fork { tryReadPacketDesc(dcs, aThread, maxTries = 1).result }
    sleepCycles(100)
    send(1)
    workerCore(1).waitUser()
    // Fill B's queue past the existing half-full additional-core watermark.
    for (_ <- 0 until 12) send(1)
    sleepCycles(5000)
    assert(workerCore(2).currThread.exists(_.proc == domains(1)), "idle worker was not allocated")
    assert(workerCore(0).currThread.exists(_.proc == domains(0)), "watermark evicted A while an idle worker existed")
    assert(schedulingIrqs == 3)
  }
  testWithDB("single-thread-domains-migrate-and-return-through-cooperative-claims")(Rx) { implicit dut =>
    val (csr, packets, _, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    val domains = Seq.fill(4)(mkRandomProc(1))
    val factories = oncRpcCallPacketFactory(csr, domains.map(_ -> Seq(RpcSrvDef.mkRandom)))
    def send(index: Int): Unit = {
      val (packet, _, _) = factories(index)._2()
      packets.send(packet.getRawData.toList)
    }
    for (index <- 0 until 3) {
      send(index)
      workerCore(index).waitUser()
      assert(workerCore(index).currThread.get.proc == domains(index))
      assert(tryReadPacketDesc(dcs, workerCore(index).currThread.get.tid).result.nonEmpty)
    }
    assert(schedulingIrqs == 3)
    // Only the selected outgoing worker waits on RX; other workers retain their
    // packet/assignment. Background discovery must find the queued destination.
    for ((victim, destination) <- Seq((0,3), (1,0), (0,1), (1,3), (0,0), (1,1))) {
      val worker = workerCore(victim)
      val outgoing = worker.currThread.get.tid
      var yielded = false
      fork { tryReadPacketDesc(dcs, outgoing, maxTries = 1).result; yielded = true }
      sleepCycles(100)
      send(destination)
      waitUntil(yielded && !worker.inISR && worker.currThread.exists(_.proc == domains(destination)))
      val incoming = worker.currThread.get
      assert(incoming == domains(destination).threads.head)
      assert(incoming.runningOn.contains(worker))
      assert(tryReadPacketDesc(dcs, incoming.tid).result.nonEmpty)
      assert(schedulingIrqs == 3, "migration used an ordinary scheduling IRQ")
    }
    assert(domains(0).threads.head.runningOn.contains(workerCore(0)))
    assert(domains(1).threads.head.runningOn.contains(workerCore(1)))
    assert(domains(2).threads.head.runningOn.contains(workerCore(2)))
  }

}
