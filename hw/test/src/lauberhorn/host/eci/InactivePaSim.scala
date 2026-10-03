package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn.sim._
import spinal.core.sim._
import jsteward.blocks.misc.sim.IntRicherEndianAware
import spinal.lib.BytesRicher

/** A domain's valid mapping includes PAs for workers it does not own. */
class InactivePaSim extends NicSim with OncRpcSuiteFactory {
  override def numWorkerCores = 2

  testWithDB("domain-fault-rejects-tx-collected-after-fault")(Rx) { implicit dut =>
    val (csr, packets, output, dcs) = commonDutSetup(100000)
    csr.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)
    dcs.voluntaryInvProb = 0
    val proc = mkRandomProc(1)
    val healthy = mkRandomProc(1)
    val factories = oncRpcCallPacketFactory(csr, Seq(proc, healthy).map(_ -> Seq(RpcSrvDef.mkRandom)))
    val (packet, _, _) = factories.head._2()
    packets.send(packet.getRawData.toList)
    waitUntil(proc.threads.head.runningOn.nonEmpty)
    val thr = proc.threads.head
    val activeWorker = thr.runningOn.get.cid - 1
    assert(tryReadPacketDesc(dcs, thr.tid).result.nonEmpty)
    var faults = 0
    domainFaultHandler = () => { faults += 1 } // Deliberately delay kernel SIGKILL.
    var transmitted = false
    fork { output.recv(); transmitted = true }
    setBypassCore(() => { transmitted = true })
    // Accept the TX trigger, but retain dirty host data until after the fault.
    dcs.pauseInvalidationResponses = true
    var txDone = false
    fork {
      val call = TxOncRpcCallSim(ONCRPC_NESTED_CALL_INLINE_BYTES.get + 64,
        proc.pid, 1, 2, BigInt("c0a88101", 16), 12345, 12346, 1, 1, 1, 0)
      txSendSingle(dcs, call, List.fill(64)(0x5a.toByte), thr.tid, expectedStatus = 3)
      txDone = true
    }
    waitUntil(dcs.invalidationsAwaitingReply > 0)
    // Feed the same sticky fault collector through its software input. The
    // inactive-PA ingress path and DECERR are checked separately: DcsAppMaster
    // aborts on DECERR, unlike production DCS which ignores RRESP.
    val regs = ALLOC.readBack("ownership")
    csr.write(regs("failDomain"), proc.slot.toBytesLE)
    waitUntil(faults == 1)
    assert(csr.read(regs("failedDomains"), 8).bytesToBigInt == (BigInt(1) << proc.slot))
    dcs.pauseInvalidationResponses = false
    waitUntil(txDone)
    sleepCycles(100)
    assert(!transmitted, "faulted domain admitted a new TX before kernel kill")
    // The never-installed worker also starts with owner code zero. It must not
    // inherit this first domain's failure solely from that reset register value.
    assert(proc.domainCode == 0)
    val (nextPacket, _, _) = factories(1)._2()
    packets.send(nextPacket.getRawData.toList)
    var cycles = 0
    while (healthy.threads.head.runningOn.isEmpty && cycles < 10000) {
      sleepCycles(1)
      cycles += 1
    }
    assert(healthy.threads.head.runningOn.nonEmpty, "failed reset owner stranded an unused worker")
    assert(healthy.threads.head.runningOn.get.cid - 1 != activeWorker)
    assert(tryReadPacketDesc(dcs, healthy.threads.head.tid).result.nonEmpty)
  }
}
