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
}
