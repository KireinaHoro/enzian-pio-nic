package pionic.host.eci

import jsteward.blocks.eci.sim.{DcsAppMaster, IpiSlave}
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.misc.sim.{isSorted, BigIntParser}
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.{EthernetPacket, Packet}
import org.pcap4j.packet.namednumber.DataLinkType
import org.scalatest.exceptions.TestFailedException
import pionic._
import pionic.Global._
import pionic.sim._
import pionic.sim.PacketType.PacketType
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _, _}
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}

import scala.collection.mutable
import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._
import org.scalatest.tagobjects.Slow

class NicSim extends DutSimFunSuite[NicEngine] with DbFactory with OncRpcSuiteFactory with TimestampSuiteFactory {
  // NUM_CORES in Database only available inside test context
  val numWorkerCores = 4
  val numCores = numWorkerCores + 1

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("eci")
    .compile(pionic.GenEngineVerilog.engine(numWorkerCores, "eci"))

  def commonDutSetup(rxBlockCycles: Int, irqCb: (Int, Int) => Unit)(implicit dut: NicEngine) = {
    val globalBlock = ALLOC.readBack("global")

    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)

    IpiSlave(eciIf.ipiToIntc, dut.clockDomain) { case (coreId, intId) =>
      println(s"Received IRQ #$intId for core $coreId")
      irqCb(coreId, intId)
    }

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    CSRSim.csrSanityChecks(globalBlock, csrMaster, rxBlockCycles)

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  def rxDutSetup(rxBlockCycles: Int, irqCb: (Int, Int) => Unit = (_, _) => ())(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get
    for (i <- rxNextCl.indices) { rxNextCl(i) = 0 }

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles, irqCb)
    (csrMaster, axisMaster, dcsMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000, (_, _) => ()) // arbitrary rxBlockCycles
    for (i <- txNextCl.indices) { txNextCl(i) = 0 }

    (csrMaster, axisSlave, dcsMaster)
  }

  def pollReady(dcsMaster: DcsAppMaster, cid: Int, maxAttempts: Int = 20): Unit = {
    if (cid != 0) {
      println(s"Checking READY for core $cid before returning to user space...")
      val coreBase = ECI_RX_BASE.get + ECI_CORE_OFFSET.get * cid
      val preemptCtrlAddr = coreBase + 0x10000

      var done = false
      var attempts = 0
      while (!done) {
        assert(attempts < maxAttempts, s"failed to read READY as 1 before returning to userspace")
        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        done = (busyReady & 0x2) != 0
        attempts += 1
      }
    }
  }

  def enterCriticalSection(dcsMaster: DcsAppMaster, cid: Int, maxAttempts: Int = 20): Unit = {
    if (cid != 0) {
      println(s"[core $cid] Entering critical section...")
      val coreBase = ECI_RX_BASE.get + ECI_CORE_OFFSET.get * cid
      val preemptCtrlAddr = coreBase + 0x10000

      // CAS READY/BUSY to enter critical region
      var done = false
      var attempts = 0
      while (!done) {
        assert(attempts < maxAttempts, s"failed to enter critical section for $maxAttempts times!")

        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        assert((busyReady & 0x1) == 0, "BUSY already high!")
        if ((busyReady & 0x2) != 0) {
          // READY is set, set BUSY
          done = dcsMaster.casByte(preemptCtrlAddr, busyReady, busyReady | 0x1)
          if (!done) {
            println("CAS failed, retrying")
          }
        } else {
          // otherwise READY is 0, try again
          println("READY is 0, retrying...")
        }
        attempts += 1
      }

      println(s"[core $cid] in critical section")
    }
  }

  def exitCriticalSection(dcsMaster: DcsAppMaster, cid: Int): Unit = {
    if (cid != 0) {
      println(s"[core $cid] Exiting critical section...")
      val coreBase = ECI_RX_BASE.get + ECI_CORE_OFFSET.get * cid
      val preemptCtrlAddr = coreBase + 0x10000

      // CAS unset BUSY to exit critical region
      // need CAS, otherwise might overwrite READY that the FPGA might have just cleared
      var done = false
      while (!done) {
        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        assert((busyReady & 0x1) != 0, "BUSY not high!")
        done = dcsMaster.casByte(preemptCtrlAddr, busyReady, busyReady & ~0x1)
      }

      println(s"[core $cid] out of critical section")
    }
  }

  def ackIrq(csrMaster: AxiLite4Master, coreBlock: RegBlockReadBack) = {
    val ipiAck = new BigIntParser(csrMaster.read(coreBlock("ipiAck"), 8).bytesToBigInt)

    // TODO: put into sim data struct to reuse
    val rxParity   = ipiAck.pop(1) != 0
    val txParity   = ipiAck.pop(1) != 0
    val killed     = ipiAck.pop(1) != 0
    val pidToSched = ipiAck.pop(PID_WIDTH, skip = 5)

    (pidToSched, rxParity, txParity, killed)
  }

  // we have one more core as bypass
  val rxNextCl = mutable.ArrayBuffer.fill(numCores)(0)
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, cid: Int, maxTries: Int = 20, exitCS: Boolean = true)(implicit dut: NicEngine): TailRec[Option[(EciHostCtrlInfoSim, BigInt)]] = {
    if (maxTries == 0) done(None)
    else {
      val coreBase = ECI_RX_BASE.get + ECI_CORE_OFFSET.get * cid

      enterCriticalSection(dcsMaster, cid)

      val clAddr = rxNextCl(cid) * 0x80 + coreBase
      val overflowAddr: BigInt = clAddr + 0x40
      println(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      // XXX: we do not check if the cacheline stays idempotent (refer to EciDecoupledRxTxProtocol)
      val control = dcsMaster.read(clAddr, 64, doInvIdemptCheck = false).bytesToBigInt
      // always toggle cacheline
      rxNextCl(cid) = 1 - rxNextCl(cid)
      val ret = if ((control & 1) == 0) {
        sleepCycles(20)
        // we didn't get a packet, exit critical section here irrespective of exitCS
        exitCriticalSection(dcsMaster, cid)

        // pass exitCS so the retry can skip exitCS if needed
        tailcall(tryReadPacketDesc(dcsMaster, cid, maxTries - 1, exitCS))
      } else {
        // got packet!
        if (exitCS) {
          // should only be used, when there's no intention to check the packet data in overflow
          exitCriticalSection(dcsMaster, cid)
        }

        done(Some((EciHostCtrlInfoSim.fromBigInt(control >> 1), overflowAddr)))
      }

      ret
    }
  }

  def checkSingle(expectedPacket: Packet, expectedProto: PacketType, gotPacket: List[Byte], gotDesc: BypassCtrlInfoSim): Boolean = {
    Try {
      assert(expectedProto.id == gotDesc.packetType, s"proto mismatch: expected $expectedProto, got ${PacketType(gotDesc.packetType.toInt)}")
      checkHeader(expectedProto, expectedPacket, gotDesc.hdrP4jPacket)

      // check payload length
      val payload = getPayloadAndCheckLen(expectedPacket, expectedProto, gotDesc.len)

      // we have already checked length, truncate the received data to expected len
      check(payload, gotPacket.take(payload.length))
    } match {
      case Failure(a: AssertionError) =>
        println(s"Check single packet failed with assertion $a")
        false
      case Failure(e: TestFailedException) =>
        println(s"Check single packet failed with $e")
        false
      case Success(_) =>
        true
    }
  }

  /** read back and check one single bypass packet */
  def rxSingle(dcsMaster: DcsAppMaster, maxRetries: Int)(implicit dut: NicEngine): (BypassCtrlInfoSim, List[Byte]) = {
    val (info, addr) = tryReadPacketDesc(dcsMaster, cid = 0, maxTries = maxRetries + 1).result.get
    println(s"Received status register: $info")
    assert(info.isInstanceOf[BypassCtrlInfoSim], "should only receive bypass packet!")

    val bypassDesc = info.asInstanceOf[BypassCtrlInfoSim]

    // read payload back and check data
    val firstReadSize = Math.min(info.len, 64)
    var data = dcsMaster.read(addr, firstReadSize)
    if (info.len > 64) {
      data ++= dcsMaster.read(
        ECI_RX_BASE.get + ECI_OVERFLOW_OFFSET,
        info.len - 64)
    }

    (bypassDesc, data)
  }

  /** test reading one bypass packet; when called multiple times, this checks in a blockign fashion */
  def rxTestSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, packet: Packet, proto: PacketType, maxRetries: Int)(implicit dut: NicEngine): Unit = {
    fork {
      sleepCycles(Random.nextInt(200))

      val toSend = packet.getRawData.toList
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // TODO: check performance counters

    // read memory and check data
    val (desc, data) = rxSingle(dcsMaster, maxRetries)
    assert(checkSingle(packet, proto, data, desc), "failed to receive single packet")

    println(s"Successfully received packet")

    // packet will be acknowledged by reading next packet
  }

  /** test scanning a range of lengths of packets to send and check */
  def rxTestRange(csrMaster: AxiLite4Master, axisMaster: Axi4StreamMaster, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, maxRetries: Int)(implicit dut: NicEngine) = {
    val globalBlock = ALLOC.readBack("global")

    // assert(tryReadPacketDesc(dcsMaster, cid, maxTries = maxRetries + 1).result.isEmpty, "should not have packet on standby yet")

    // reset packet allocator
    csrMaster.write(globalBlock("dmaCtrl", "allocReset"), 1.toBytes)
    sleepCycles(200)
    csrMaster.write(globalBlock("dmaCtrl", "allocReset"), 0.toBytes)

    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until Random.between(25, 50) foreach { _ =>
        import PacketType._
        val (packet, proto) = randomPacket(size, randomizeLen = false)(Ethernet, Ip, Udp)
        rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxRetries)
      }
    }

    // TODO: check DCS master cacheline state
  }

  testWithDB("rx-bypass-scan-sizes", Slow) { implicit dut =>
    // set a large enough rx block cycles, such that there shouldn't be a need to retry
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(5000000) // 20 ms @ 250 MHz
    val globalBlock = ALLOC.readBack("global")

    // enable promisc mode
    csrMaster.write(globalBlock("csr", "promisc"), 1.toBytes)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 9618, 64, maxRetries = 0)
  }

  testWithDB("rx-bypass-simple") { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    val globalBlock = ALLOC.readBack("global")

    // enable promisc mode
    csrMaster.write(globalBlock("csr", "promisc"), 1.toBytes)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 256, 64, maxRetries = 5)
  }

  testWithDB("rx-oncrpc-allcores") { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores
    // - send 50 * numWorkerCores requests
    // - cores are preempted as requests come in, they start to read
    // - eventually all packets are received through all cores

    val totalToSend = 50 * NUM_WORKER_CORES

    val irqReceived = mutable.ArrayBuffer.fill(NUM_CORES)(false)

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000, { case (coreId, intId) =>
      // every core will be preempted from IDLE to run PID exactly once
      assert(!irqReceived(coreId), s"core $coreId has already been preempted once!")
      assert(intId == 8, s"expecting interrupt ID 8 for a normal preemption")

      irqReceived(coreId) = true
    })

    val globalBlock = ALLOC.readBack("global")

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster, globalBlock,
      packetDumpWorkspace = Some("rx-oncrpc-allcores")
    )
    val sentPackets = mutable.Map[Int, (EthernetPacket, List[Byte])]()
    var packetsReceived = 0

    fork {
      // send all packets
      0 until totalToSend foreach { idx =>
        if (idx != 0 && idx % 32 == 0) {
          // crude flow control: queue could've become full, wait a bit before continuing
          // TODO: proper flow control?
          val toWait = 3000
          println(s"Waiting for $toWait cycles before continuing sending...")
          sleepCycles(toWait)
        }

        val (packet, payload, xid) = getPacket()
        println(f"Sending packet with XID $xid%#x")
        val toSend = packet.getRawData.toList
        // blocking send
        axisMaster.send(toSend)

        // record packet in map: xid is key
        // FIXME: this might collide..
        assert(!sentPackets.contains(xid), "random packet generation collision")
        sentPackets(xid) = (packet, payload)
      }
    }

    1 to NUM_WORKER_CORES foreach { cid =>
      fork {
        def log(msg: String) = println(s"[core $cid] $msg")

        log("Waiting for IRQ...")

        // wait for schedule request
        waitUntil(irqReceived(cid))
        log("Received IRQ, ack-ing interrupt...")

        val coreBlock = ALLOC.readBack("core", blockIdx = cid)
        val (pidToSched, rxParity, txParity, killed) = ackIrq(csrMaster, coreBlock)
        assert(pidToSched == pid, "requested PID does not match what we programmed")
        assert(!rxParity, "no read happened yet, should be on CL #0")
        assert(!txParity, "no write happened yet, should be on CL #0")
        assert(!killed, "we should be preempted on IDLE, so shouldn't be killed")

        // kernel needs to poll READY to make sure that datapath preemption is done
        pollReady(dcsMaster, cid)
        log("returned to userspace")

        while (packetsReceived != totalToSend) {
          // read and check packet against sent
          val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, cid, exitCS = false).result.get
          val info = desc.asInstanceOf[OncRpcCallPacketDescSim]
          log(f"Received status register: $desc")

          // do not process packets too fast, or other cores will never get invoked
          sleepCycles(Random.between(50, 100))
          log("Sleep finished, checking packet data...")

          // packet generator return little endian xid but sends in big endian
          // HW does not change (i.e. we get big endian back)
          val xid = Integer.reverseBytes(info.xid.toInt)
          log(f"Received XID $xid%x")

          // find the payload that we sent
          val (pkt, pld) = sentPackets(xid)
          log(f"Expecting packet: $pkt")

          checkOncRpcCall(desc, desc.len, funcPtr, pld, dcsMaster.read(overflowAddr, desc.len))
          log(f"Received packet #$packetsReceived (XID $xid%x)")
          packetsReceived += 1

          exitCriticalSection(dcsMaster, cid)
        }
      }
    }

    waitUntil(packetsReceived == totalToSend)
  }

  var txNextCl = mutable.ArrayBuffer.fill(numCores)(0)

  /** Send one descriptor, optionally with a tail payload. */
  def txSendSingle(dcsMaster: DcsAppMaster, txDesc: EciHostCtrlInfoSim, toSend: List[Byte], cid: Int): Unit = {
    def clAddr = txNextCl(cid) * 0x80 + ECI_TX_BASE.get + ECI_CORE_OFFSET * cid

    println(f"Core $cid: sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, txDesc.toTxDesc)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      dcsMaster.write(
        ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET + ECI_CORE_OFFSET * cid,
        toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    println(f"Core $cid: sent packet at $clAddr%#x")

    txNextCl(cid) = 1 - txNextCl(cid)
    dcsMaster.read(clAddr, 1)
  }

  /** Test sending one single packet as bypass on a specific core.  Also checks if the expected packet appears on the
    * outgoing AXI-Stream interface.
    */
  def txTestSingle[T <: Packet](dcsMaster: DcsAppMaster, axisSlave: Axi4StreamSlave, ty: PacketType, hdr: T, pld: List[Byte], cid: Int): Unit = {
    var received = false
    val hdrBytes = if (ty == PacketType.Raw) List() else hdr.getRawData.toList
    fork {
      val data = axisSlave.recv()
      val expected = hdrBytes ++ pld

      check(expected, data)
      println(s"Core $cid: packet received from TX interface and validated")
      received = true
    }

    val hdrBigInt = if (ty == PacketType.Raw) BigInt(0) else hdrBytes.bytesToBigInt
    txSendSingle(dcsMaster, BypassCtrlInfoSim(pld.length, ty.id, hdrBigInt), pld, cid)

    println(s"Core $cid: waiting for packet")

    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  def txTestRange(axisSlave: Axi4StreamSlave, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, cid: Int) = {
    // Sweep at given range and step.  Send packet as Raw bypass
    // TODO: test also Ethernet and other encoders
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until Random.between(25, 50) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        txTestSingle(dcsMaster, axisSlave, PacketType.Raw, null, toSend, cid)
      }
    }
  }

  def txScanOnCore(cid: Int) = testWithDB(s"tx-scan-sizes-core$cid", Slow) { implicit dut =>
    val (_, axisSlave, dcsMaster) = txDutSetup()

    txTestRange(axisSlave, dcsMaster, 64, 9618, 64, cid)
  }

  0 until numCores foreach txScanOnCore

  testWithDB("tx-all-cores-serialized") { implicit dut =>
    val (_, axisSlave, dcsMaster) = txDutSetup()

    0 until NUM_CORES foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }

  testWithDB("tx-no-voluntary-inv") { implicit dut =>
    val (_, axisSlave, dcsMaster) = txDutSetup()
    dcsMaster.voluntaryInvProb = 0
    dcsMaster.doPartialWrite = false

    0 until NUM_CORES foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }

  testWithDB("rx-bypass-pipelined") { implicit dut =>
    val globalBlock = ALLOC.readBack("global")
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)

    val numPackets = 5

    // enable promisc mode
    csrMaster.write(globalBlock("csr", "promisc"), 1.toBytes)

    val toCheck = new mutable.ArrayDeque[(Packet, PacketType)]
    val size = 128

    val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("rx-bypass-pipelined") / "packets.pcap").toString)

    fork {
      0 until numPackets foreach { pid =>
        import PacketType._
        val (packet, proto) = randomPacket(size)(Ethernet, Ip, Udp)
        dumper.dump(packet)
        dumper.flush()

        val toSend = packet.getRawData.toList
        axisMaster.send(toSend)
        println(s"Sent packet #$pid of length ${toSend.length}")

        toCheck.append((packet, proto))
      }
    }

    0 until numPackets foreach { pid =>
      waitUntil(toCheck.nonEmpty)
      val (desc, data) = rxSingle(dcsMaster, maxRetries = 0)

      // XXX: occasionally the packet received is out of order
      //      e.g. receiving Ethernet after Udp.  Udp takes longer to go through the pipeline,
      //      resulting in Ethernet packet arriving first
      toCheck.view.map { case (p, pr) => checkSingle(p, pr, data, desc) }
        .zipWithIndex.dropWhile(!_._1).headOption match {
        case Some((_, idx)) =>
          println(s"Found expected packet as #$idx in queue")
          toCheck.remove(idx)
        case None => fail("failed to find received packet in expect queue")
      }
      println(s"Received packet #$pid")
      sleepCycles(20)
    }
  }

  testWithDB("rx-bypass-no-repeat") { implicit dut =>
    // send one packet, receive twice -- no second packet should arrive
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)
    val globalBlock = ALLOC.readBack("global")
    val maxTries = 5

    // enable promisc mode
    csrMaster.write(globalBlock("csr", "promisc"), 1.toBytes)

    assert(tryReadPacketDesc(dcsMaster, 0, maxTries).result.isEmpty, "should not have packet on standby yet")

    import PacketType._
    val (packet, proto) = randomPacket(512, randomizeLen = false)(Ethernet, Ip, Udp)
    rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxTries + 1)

    assert(tryReadPacketDesc(dcsMaster, 0, maxTries).result.isEmpty, "packet should not be duplicated")
  }

  testWithDB("rx-oncrpc-timestamped") { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores
    // - send first packet before core is scheduled
    // - core is preempted to run proc
    // - read first packet, check timestamps (queued)
    // - read second packet (stalled)
    // - send second packet, check timestamps

    // test timestamp collection with oncrpc call
    val globalBlock = ALLOC.readBack("global")
    // test on first non-bypass core
    val coreBlock = ALLOC.readBack("core", blockIdx = 1)

    var irqReceived = false
    var readingSecond = false

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100, { case (coreId, intId) =>
      assert(coreId == 1, "only one packet, should have asked for preemption on core 1")
      assert(intId == 8, s"expecting interrupt ID 8 for a normal preemption")

      assert(!irqReceived, "should only receive one interrupt")
      irqReceived = true
    })

    val delayed = 1000

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster, globalBlock)
    val (packet, pld, xid) = getPacket()
    val (packet2, pld2, xid2) = getPacket()

    fork {
      // send first packet -- host not ready yet, packet will be queued
      axisMaster.send(packet.getRawData.toList)

      // wait until host is ready and is actively reading
      waitUntil(readingSecond)

      // ensure the host is actually reading
      sleepCycles(delayed)

      // send second packet -- host already reading and stalled
      axisMaster.send(packet2.getRawData.toList)
    }

    // wait until we have received the IRQ
    waitUntil(irqReceived)
    println("Received IRQ, ack-ing interrupt")

    // ACK interrupt -- we have now arrived in the kernel
    val (pidToSched, rxParity, txParity, killed) = ackIrq(csrMaster, coreBlock)
    assert(pidToSched == pid, "requested PID does not match what we programmed")
    assert(!rxParity, "no read happened yet, should be on CL #0")
    assert(!txParity, "no write happened yet, should be on CL #0")
    assert(!killed, "we should be preempted on IDLE, so shouldn't be killed")

    // kernel needs to poll READY to make sure that datapath preemption is done
    pollReady(dcsMaster, 1)
    println(s"Core returned to userspace")

    // ensure that packet has landed in the queue
    sleepCycles(delayed)

    {
      val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, 1, exitCS = false).result.get
      // check if decoded packet is what we sent
      val info = desc.asInstanceOf[OncRpcCallPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid, f"xid mismatch: expected $xid%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld, dcsMaster.read(overflowAddr, desc.len))
      exitCriticalSection(dcsMaster, 1)

      val curr = csrMaster.read(globalBlock("csr", "cycles"), 8).bytesToBigInt

      // we don't use the commit timestamp since commit is tied to read next
      val ts = getRxTimestamps(csrMaster, globalBlock)

      // check timestamps for first packet
      import ts._

      println(s"Current timestamp after packet 1 done: $curr")

      assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, curr))
      assert(readStart - entry >= delayed)
    }

    // we can now read second packet
    readingSecond = true

    {
      val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, 1, exitCS = false).result.get
      val info = desc.asInstanceOf[OncRpcCallPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid2, f"xid2 mismatch: expected $xid2%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld2, dcsMaster.read(overflowAddr, desc.len))
      exitCriticalSection(dcsMaster, 1)

      val curr = csrMaster.read(globalBlock("csr", "cycles"), 8).bytesToBigInt
      val ts = getRxTimestamps(csrMaster, globalBlock)
      import ts._
      println(s"Current timestamp after packet 2 done: $curr")
      assert(isSorted(readStart, entry, afterRxQueue, enqueueToHost, curr))
      assert(entry - readStart >= delayed)
    }
  }

  // checks if a core has an IRQ pending.  Checked before and after critical section
  class CoreIrqState {
    var irqReceived = false
    def set() = { irqReceived = true }
    def unset() = { irqReceived = false }
  }
  def genericIrqHandler(coreStates: Seq[CoreIrqState])(coreId: Int, intId: Int) = {
    coreStates(coreId).set()
  }

  /* Test that Lauberhorn can scale up to multiple services */
  testWithDB("rx-sched-idle-scale-many") { implicit dut =>
    // on 8 cores, install 4 processes, 2 services each
  }

  /* Test preempting a process that:
   * - is not processing a request, AND
   * - did not receive request for some time
   */
  testWithDB("rx-sched-preempt") { implicit dut =>
    val coreStates = Seq.fill(NUM_WORKER_CORES)(new CoreIrqState)

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100, genericIrqHandler(coreStates))
  }

  /* Test killing a process that did not unset BUSY */
  testWithDB("rx-sched-crit-timeout") { implicit dut =>

  }
}
