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

  testWithDB("new-owner-cannot-read-previous-owner-tx-payload")(Rx) { implicit dut =>
    val (csr, packets, output, dcs) = commonDutSetup(100000)
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
    assert(tryReadPacketDesc(dcs, worker.currThread.get.tid).result.nonEmpty)
    var expectedDmaTail = List.empty[Byte]
    var dmaChecked = false
    setBypassCore(() => {
      val (info, payload) = tryReadPacketDesc(dcs, tid = -1, maxTries = 1).result.get
      val miss = info.asInstanceOf[TxNeighborMissSim]
      val bytes = readPayload(dcs, payload, miss.len)
      assert(bytes.takeRight(expectedDmaTail.size) == expectedDmaTail,
        "DMA transmitted bytes outside the current owner's writes")
      dmaChecked = true
    })
    for (destination <- Seq(1, 0, 1)) {
      val old = worker.currThread.get
      val secret = List.fill(1400)((0x50 + destination).toByte)
      val call = TxOncRpcCallSim(ONCRPC_NESTED_CALL_INLINE_BYTES.get + secret.size,
        old.proc.pid, 1, old.proc.pid, BigInt("c0a88101", 16), 12345, 12346, 1, 1, 1, 0)
      expectedDmaTail = secret
      dmaChecked = false
      txSendSingle(dcs, call, secret, old.tid)
      waitUntil(dmaChecked)
      send(destination)
      val previous = getEciThreadData(old.tid)
      var desched = false
      while (!desched) {
        val control = dcs.read(previous.baseAddr + ECI_RX_BASE.get + previous.rxNextCl * 128, 64).bytesToBigInt
        previous.flipRx()
        desched = (control & 3) == 2
      }
      cooperativeYield(csr, 1)
      assert(worker.currThread.get.proc == domains(destination))
      val (rxDesc, rxPayload) = tryReadPacketDesc(dcs, worker.currThread.get.tid).result.get
      val inlineBytes = dcs.read(rxPayload.firstReadAddr, 64)
      val overflowBytes = dcs.read(rxPayload.overflowAddr, 12 * 128)
      assert(inlineBytes.drop(if (rxDesc.len <= 64) rxDesc.len else 0).forall(_ == 0),
        "RX exposed bytes in the inactive control payload half")
      assert(overflowBytes.drop(if (rxDesc.len > 64) rxDesc.len else 0).forall(_ == 0),
        "RX exposed bytes beyond the received packet")
      val current = getEciThreadData(worker.currThread.get.tid)
      val bytes = dcs.read(current.baseAddr + ECI_TX_BASE.get + current.txNextCl * 128 + 64, 64) ++
        dcs.read(current.baseAddr + ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET, 12 * 128)
      assert(bytes.forall(_ == 0),
        s"new owner's valid TX aliases exposed stale payload: ${bytes.map(b => f"${b & 0xff}%02x").mkString}")
      expectedDmaTail = List.fill(64)(0.toByte)
      dmaChecked = false
      val unwritten = TxOncRpcCallSim(ONCRPC_NESTED_CALL_INLINE_BYTES.get + 64,
        domains(destination).pid, 2, domains(destination).pid + 1000,
        BigInt("c0a88101", 16), 12345, 12346, 1, 1, 1, 0)
      txSendSingle(dcs, unwritten, Nil, worker.currThread.get.tid)
      waitUntil(dmaChecked)
    }
  }

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
    // Duplicate idle kicks must not consume another DeschedInfo snapshot.
    genericIrqCb(csr, 8, 1)
    genericIrqCb(csr, 8, 1)

    for (destination <- Seq(1, 0, 1, 0)) {
      val old = worker.currThread.get
      val etd = getEciThreadData(old.tid)
      send(destination)
      var desched = false
      while (!desched) {
        val control = dcs.read(etd.baseAddr + ECI_RX_BASE.get + etd.rxNextCl * 128, 64).bytesToBigInt
        etd.flipRx()
        assert((control & 1) == 0, "DESCHED consumed a queued network packet")
        desched = (control & 3) == 2
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
