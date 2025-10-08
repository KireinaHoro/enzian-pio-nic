package lauberhorn.host.eci

import jsteward.blocks.eci.sim.{DcsAppMaster, IpiSlave}
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.{BigIntParser, IntRicherEndianAware, isSorted}
import org.pcap4j.core.{PcapDumper, Pcaps}
import org.pcap4j.packet.{EthernetPacket, IpV4Packet, Packet, UdpPacket}
import org.pcap4j.packet.namednumber.DataLinkType
import org.scalatest.exceptions.TestFailedException
import lauberhorn._
import lauberhorn.Global._
import lauberhorn.sim._
import lauberhorn.sim.PacketType._
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

import java.net.InetAddress

class EciThreadData(val td: ThreadDef) {
  /** 2F2F protocol parity bits */
  var rxNextCl, txNextCl = 0

  def flipRx() = rxNextCl = 1 - rxNextCl
  def flipTx() = txNextCl = 1 - txNextCl

  def baseAddr = td.prefix * ECI_CORE_OFFSET
}

class NicSim extends DutSimFunSuite[NicEngine]
  with DbFactory
  with OncRpcSuiteFactory
  with TimestampSuiteFactory
  with GenericHostCPUModel {
  // NUM_CORES in Database only available inside test context
  def numWorkerCores = 4

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("eci")
    .compile(lauberhorn.GenEngineVerilog.engine(numWorkerCores, "eci"))

  /** Extra data for each [[ThreadDef]] with ECI-related states, notably CL parity bits */
  val eciThreadDataMap = mutable.HashMap[ThreadDef, EciThreadData]()
  def getEciThreadData(tid: Int) = {
    val td = threads(tid)
    eciThreadDataMap.getOrElseUpdate(td, new EciThreadData(td))
  }

  /** Set up the DUT and create the DCS and AXI-Lite simulation bus masters and arm an IRQ handler.
    * Calls into [[GenericHostCPUModel]] to simulate software state changes in the kernel.
    */
  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    IpiSlave(eciIf.ipiToIntc, dut.clockDomain) { case (coreId, intId) =>
      println(s"Received IRQ #$intId for core $coreId")
      genericIrqCb(csrMaster, cid = coreId, irq = intId)
    }

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    // enable IRQ for all cores
    0 until NUM_CORES foreach { cid =>
      csrMaster.write(ALLOC.readBack("preempt", cid)("irqEn"), 1.toBytesLE)
    }
    CSRSim.csrSanityChecks(csrMaster, rxBlockCycles)

    val bypassThread = ThreadDef(-1, 0xdead)
    threads(-1) = bypassThread
    bypassCore.switchToThread(bypassThread, csrMaster)

    0 until NUM_WORKER_CORES foreach { wcid =>
      setWorkerCore(wcid, new EciWorkerCoreState(wcid+1, csrMaster))
    }

    eciThreadDataMap.clear()

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  /** In addition to [[commonDutSetup]], assert that the TX interface is inactive during RX-only tests.  */
  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)
    (csrMaster, axisMaster, dcsMaster)
  }

  /** Enter the critical-section for preemption for a specific worker thread.
    *
    * This function is called directly inside RX/TX datapath functions ([[tryReadPacketDesc]]
    * and [[txSendSingle]]), also for bypass.  [[tid]] will be -1 for bypass accesses.
    */
  def enterCriticalSection(dcsMaster: DcsAppMaster, tid: Int, maxAttempts: Int = 20): Unit = {
    if (tid != -1) {
      println(s"[thread $tid] Entering critical section...")

      val etd = getEciThreadData(tid)
      val coreBase = etd.baseAddr
      val preemptCtrlAddr = coreBase + ECI_PREEMPT_CTRL_OFFSET

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

      println(s"[thread $tid] in critical section")

      etd.td.runningOn.get.asInstanceOf[EciWorkerCoreState].inCriticalRegion = true
    }
  }

  def exitCriticalSection(dcsMaster: DcsAppMaster, tid: Int): Unit = {
    if (tid != -1) {
      println(s"[thread $tid] Exiting critical section...")
      val etd = getEciThreadData(tid)
      val coreBase = etd.baseAddr
      val preemptCtrlAddr = coreBase + ECI_PREEMPT_CTRL_OFFSET

      // CAS unset BUSY to exit critical region
      // need CAS, otherwise might overwrite READY that the FPGA might have just cleared
      var done = false
      while (!done) {
        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        assert((busyReady & 0x1) != 0, "BUSY not high!")
        done = dcsMaster.casByte(preemptCtrlAddr, busyReady, busyReady & ~0x1)
      }

      println(s"[thread $tid] out of critical section")

      etd.td.runningOn.get.asInstanceOf[EciWorkerCoreState].inCriticalRegion = false
    }
  }

  class EciWorkerCoreState(val cid: Int, csrMaster: AxiLite4Master) extends WorkerCoreState {
    var inCriticalRegion = false

    def switchToThreadImpl(threadDef: ThreadDef): Unit = {
      assert(!inCriticalRegion, s"core $cid in critical region of thread ${currThread.get.tid}, cannot switch!")

      val etd = getEciThreadData(threadDef.tid)

      // write our CL parity bits into hardware
      csrMaster.write(ALLOC.readBack("worker", blockIdx = cid)("rxCurrClIdx"), etd.rxNextCl.toBytesLE)
      csrMaster.write(ALLOC.readBack("worker", blockIdx = cid)("txCurrClIdx"), etd.txNextCl.toBytesLE)
    }
  }

  def tryReadPacketDesc(dcsMaster: DcsAppMaster, tid: Int, maxTries: Int = 20, exitCS: Boolean = true)(implicit dut: NicEngine): TailRec[Option[(EciHostCtrlInfoSim, BigInt)]] = {
    if (maxTries == 0) done(None)
    else {
      val etd = getEciThreadData(tid)
      val coreBase = etd.baseAddr

      enterCriticalSection(dcsMaster, tid)

      val clAddr = etd.rxNextCl * 0x80 + coreBase
      val overflowAddr: BigInt = clAddr + 0x40
      println(f"[thread $tid] Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      // XXX: we do not check if the cacheline stays idempotent (refer to EciDecoupledRxTxProtocol)
      val control = dcsMaster.read(clAddr, 64, doInvIdemptCheck = false).bytesToBigInt
      // always toggle cacheline
      etd.flipRx()
      val ret = if ((control & 1) == 0) {
        sleepCycles(20)
        // we didn't get a packet, exit critical section here irrespective of exitCS
        exitCriticalSection(dcsMaster, tid)

        // pass exitCS so the retry can skip exitCS if needed
        tailcall(tryReadPacketDesc(dcsMaster, tid, maxTries - 1, exitCS))
      } else {
        // got packet!
        if (exitCS) {
          // should only be used, when there's no intention to check the packet data in overflow
          exitCriticalSection(dcsMaster, tid)
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
    val (info, addr) = tryReadPacketDesc(dcsMaster, tid = -1, maxTries = maxRetries + 1).result.get
    println(s"Received status register: $info")
    assert(info.isInstanceOf[BypassCtrlInfoSim], "should only receive bypass packet!")

    val bypassDesc = info.asInstanceOf[BypassCtrlInfoSim]

    // read payload back and check data
    val firstReadSize = Math.min(info.len, 64)
    println(s"Reading payload in control CL: $firstReadSize bytes")
    var data = dcsMaster.read(addr, firstReadSize)
    if (info.len > 64) {
      val overflowLen = info.len - 64
      println(s"Reading payload in overflow CL: $overflowLen bytes")
      data ++= dcsMaster.read(ECI_RX_BASE.get + ECI_OVERFLOW_OFFSET, overflowLen)
    }

    (bypassDesc, data)
  }

  /** test reading one bypass packet; when called multiple times, this checks in a blocking fashion */
  def rxTestSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, packet: Packet, proto: PacketType, maxRetries: Int)(implicit dut: NicEngine): Unit = {
    // set bypass handler
    var checked = false

    setBypassCore(() => {
      // read memory and check data
      val (desc, data) = rxSingle(dcsMaster, maxRetries)
      assert(checkSingle(packet, proto, data, desc), "failed to receive single packet")

      // TODO: check performance counters

      println(s"Successfully received packet")
      checked = true

      // packet will be acknowledged by reading next packet
    })

    fork {
      sleepCycles(Random.nextInt(200))

      val toSend = packet.getRawData.toList
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    waitUntil(checked)
  }

  /** test scanning a range of lengths of packets to send and check */
  def rxTestRange(csrMaster: AxiLite4Master, axisMaster: Axi4StreamMaster, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, maxRetries: Int)(implicit dut: NicEngine) = {
    // assert(tryReadPacketDesc(dcsMaster, cid, maxTries = maxRetries + 1).result.isEmpty, "should not have packet on standby yet")

    // reset packet allocator
    csrMaster.write(ALLOC.readBack("dma")("ctrl", "allocReset"), 1.toBytesLE)
    sleepCycles(200)
    csrMaster.write(ALLOC.readBack("dma")("ctrl", "allocReset"), 0.toBytesLE)

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

  testWithDB("rx-bypass-scan-sizes", Slow, Rx) { implicit dut =>
    // set a large enough rx block cycles, such that there shouldn't be a need to retry
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(5000000) // 20 ms @ 250 MHz
    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 9618, 64, maxRetries = 0)
  }

  testWithDB("rx-bypass-simple", Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 256, 64, maxRetries = 5)
  }

  testWithDB("rx-oncrpc-allcores", Rx) { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores,
    //   creating a thread for every worker core
    // - send 50 * numWorkerCores requests
    // - cores are preempted as requests come in, they start to read
    // - eventually all packets are received through all cores

    val totalToSend = 50 * NUM_WORKER_CORES

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)

    // test one service on one process on all cores
    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("rx-oncrpc-allcores")
    ).head
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

    0 until NUM_WORKER_CORES foreach { wcid =>
      fork {
        val cs = workerCore(wcid)

        cs.log("Wait until a user thread is scheduled")
        cs.waitUser()

        val tid = cs.currThread.get.tid
        cs.log("returned to userspace")

        while (packetsReceived != totalToSend) {
          // read and check packet against sent
          val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
          val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
          cs.log(f"Received status register: $desc")

          // do not process packets too fast, or other cores will never get invoked
          sleepCycles(Random.between(50, 100))
          cs.log("Sleep finished, checking packet data...")

          // packet generator return little endian xid but sends in big endian
          // HW does not change (i.e. we get big endian back)
          val xid = Integer.reverseBytes(info.xid.toInt)
          cs.log(f"Received XID $xid%x")

          // find the payload that we sent
          val (pkt, pld) = sentPackets(xid)
          cs.log(f"Expecting packet: $pkt")

          checkOncRpcCall(desc, desc.len, funcPtr, pld, dcsMaster.read(overflowAddr, desc.len))
          cs.log(f"Received packet #$packetsReceived (XID $xid%x)")
          packetsReceived += 1

          exitCriticalSection(dcsMaster, tid)
        }
      }
    }

    waitUntil(packetsReceived == totalToSend)
  }

  /** Send one descriptor, optionally with a tail payload. */
  def txSendSingle(dcsMaster: DcsAppMaster, txDesc: EciHostCtrlInfoSim, toSend: List[Byte], tid: Int): Unit = {
    val etd = getEciThreadData(tid)
    val coreBase = etd.baseAddr
    def clAddr = etd.txNextCl * 0x80 + ECI_TX_BASE.get + coreBase

    enterCriticalSection(dcsMaster, tid)

    println(f"[thread $tid] sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, txDesc.toTxDesc)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      val overflowAddr = ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET + coreBase
      dcsMaster.write(overflowAddr, toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    println(f"[thread $tid] sent packet at $clAddr%#x")

    etd.flipTx()
    dcsMaster.read(clAddr, 1)

    exitCriticalSection(dcsMaster, tid)
  }

  /** Test sending one single packet as bypass on a specific thread.  Also checks if the expected packet appears on the
   * outgoing AXI-Stream interface.
   *
   * Sends packet through the bypass interface, which only takes destination addresses.  Checks the output against
   * the full packet.
   *
   * This function pre-programs the neighbor table entry!  Test "tx-neighbor-resolve-request" checks if a bypass
   * request for neighbor resolving is correctly sent to the host, when a neighbor entry is missing.
   *
   * This function assumes the thread routing has been set up correctly!  This is the case for the bypass thread (done
   * in [[commonDutSetup]]) but not for worker cores.
   */
  def txTestSingle(dcsMaster: DcsAppMaster, csrMaster: AxiLite4Master, axisSlave: Axi4StreamSlave, packet: EthernetPacket, tid: Int)
                  (implicit dut: NicEngine): Unit = {
    var received = false
    val ty = pcap4jPacketToType(packet)
    val (pld, desc) = ty match {
      case Ethernet =>
        val pld = packet.getPayload.getRawData.toList
        val hdr = packet.getHeader
        val desc = TxEthernetCmdSim(pld.length, hdr.getDstAddr, hdr.getType.value.toInt)
        (pld, desc)

      case Ip =>
        val ipPkt = packet.get(classOf[IpV4Packet])
        val pld = ipPkt.getPayload.getRawData.toList
        val hdr = ipPkt.getHeader
        val ipDst = hdr.getDstAddr
        val desc = TxIpCmdSim(pld.length, ipDst, hdr.getProtocol.value.toInt)

        // program the correct neighbor entry
        // XXX: this always use the first neighbor entry
        val ethDst = packet.getHeader.getDstAddr
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), ipDst.getAddress.toList)
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), ethDst.getAddress.toList)
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
        csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)

        (pld, desc)
    }

    fork {
      val data = axisSlave.recv()
      val expected = packet.getRawData.toList

      check(expected, data)
      println(s"Thread $tid: packet received from TX interface and validated")
      received = true
    }

    txSendSingle(dcsMaster, desc, pld, tid)

    println(s"Thread $tid: waiting for packet")
    fork {
      sleepCycles(5000)
      assert(received, s"Thread $tid: packet receive timeout!")
    }
    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  /** Tests sending a range of sizes of packets over the bypass channel of a specific thread. */
  def txTestRange(axisSlave: Axi4StreamSlave, dcsMaster: DcsAppMaster, csrMaster: AxiLite4Master, startSize: Int, endSize: Int, step: Int, tid: Int)
                 (implicit d: PcapDumper, dut: NicEngine) = {
    // Sweep at given range and step, send IP packets over bypass
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until Random.between(25, 50) foreach { _ =>
        txTestSingle(dcsMaster, csrMaster, axisSlave, getIpPacketFromEnzian(1, size), tid)
      }
    }
  }

  def txScanOnCore(cid: Int) = {
    val testName = s"tx-scan-sizes-core$cid"
    testWithDB(testName, Slow, Tx) { implicit dut =>
      implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets-expecting.pcap").toString)

      val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

      val tid = if (cid == 0) -1 else {
        // create one process with one thread, enable that thread on the core under test
        val proc = mkRandomProc(1)
        val thr = proc.threads.head
        workerCore(cid - 1).switchToThread(thr, csrMaster)

        thr.tid
      }

      txTestRange(axisSlave, dcsMaster, csrMaster, 64, 9618, 64, tid)
    }
  }

  0 until numCores foreach txScanOnCore

  def txAllCores(doVoluntaryInv: Boolean) = {
    val testName = s"tx-all-cores-${if (!doVoluntaryInv) "no-" else ""}inv"
    testWithDB(testName, Tx) { implicit dut =>
      val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles

      implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(testName) / "packets-expecting.pcap").toString)

      // create one process with threads on all worker cores
      val proc = mkRandomProc(NUM_WORKER_CORES)

      if (!doVoluntaryInv) {
        dcsMaster.voluntaryInvProb = 0
        dcsMaster.doPartialWrite = false
      }

      0 until NUM_CORES foreach { idx =>
        println(s"====> Testing core $idx")

        val tid = if (idx == 0) -1 else {
          val wcid = idx - 1
          val thr = proc.threads(wcid)
          workerCore(wcid).switchToThread(thr, csrMaster)

          thr.tid
        }

        txTestRange(axisSlave, dcsMaster, csrMaster, 64, 256, 64, tid)
      }
    }
  }

  Seq(false, true) foreach txAllCores

  testWithDB("tx-neighbor-resolve-request", Tx) { implicit dut =>
    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("tx-neighbor-resolve-request") / "packets-expecting.pcap").toString)

    val pkt = getIpPacketFromEnzian(1, 512)
    val ipPkt = pkt.get(classOf[IpV4Packet])
    val ipDst = ipPkt.getHeader.getDstAddr
    val macDst = pkt.getHeader.getDstAddr

    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(1000)

    // serve ARP resolve request in kernel
    var reqServed = false
    setBypassCore(() => {
      val (info, _) = tryReadPacketDesc(dcsMaster, tid = -1, maxTries = 1).result.get
      val arpReq = info.asInstanceOf[TxArpReqSim]
      val addr = InetAddress.getByAddress(arpReq.ipAddr.toBytesLE.toArray)
      println(s"Received ARP request to $addr on table entry #${arpReq.neighTblIdx}")
      assert(addr == ipDst, "received ARP request for wrong IP address")

      // check if neighbor entry is in `incomplete`
      csrMaster.write(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_idx"), arpReq.neighTblIdx.toBytesLE)
      val addrInTbl = csrMaster.read(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_ipAddr"), 4)
      assert(addrInTbl.toArray sameElements addr.getAddress, "entry waiting for ARP does not have the same address")
      assert(csrMaster.read(ALLOC.readBack("IpEncoder")("stat", "neigh_readback_state"), 1).bytesToBigInt == 1, "entry waiting for ARP is not in `incomplete` state")

      // update entry and resend packet
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), ipDst.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), macDst.getAddress.toList)
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
      csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), arpReq.neighTblIdx.toBytesLE)
      reqServed = true
    })

    // enable one thread on worker 0
    val thr = mkRandomProc(1).threads.head
    workerCore(0).switchToThread(thr, csrMaster)
    val tid = thr.tid

    // send one IP packet without programming the neighbor table first
    val pld = ipPkt.getPayload.getRawData.toList
    val desc = TxIpCmdSim(pld.length, ipDst, ipPkt.getHeader.getProtocol.value.toInt)
    txSendSingle(dcsMaster, desc, pld, tid)

    waitUntil(reqServed)

    var checked = false
    fork {
      val data = axisSlave.recv()
      val expected = pkt.getRawData.toList
      check(expected, data)
      println(s"Packet received from TX interface and validated")
      checked = true
    }

    // send packet again, receive on AXIS
    txSendSingle(dcsMaster, desc, pld, tid)
    waitUntil(checked)
  }

  testWithDB("rx-bypass-pipelined", Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)

    val numPackets = 5

    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    val toCheck = new mutable.ArrayDeque[(Packet, PacketType)]
    val size = 128

    val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("rx-bypass-pipelined") / "packets.pcap").toString)

    var received = 0
    setBypassCore(() => {
      // when bypass interrupt happens, there must be a descriptor to fetch
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
      println(s"Received packet #$received")

      received += 1
    })

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

    waitUntil(received == numPackets)
  }

  testWithDB("rx-bypass-no-repeat", Rx) { implicit dut =>
    // send one packet, receive twice -- no second packet should arrive
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)
    val maxTries = 5

    // enable promisc mode
    csrMaster.write(ALLOC.readBack("decoderSink")("ctrl", "promisc"), 1.toBytesLE)

    assert(tryReadPacketDesc(dcsMaster, -1, maxTries).result.isEmpty, "should not have packet on standby yet")

    import PacketType._
    val (packet, proto) = randomPacket(512, randomizeLen = false)(Ethernet, Ip, Udp)
    rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxTries + 1)

    assert(tryReadPacketDesc(dcsMaster, -1, maxTries).result.isEmpty, "packet should not be duplicated")
  }

  testWithDB("rx-no-promisc", Rx) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(500)

    implicit val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("rx-no-promisc") / "packets.pcap").toString)

    // NOT enabling promisc

    // default values is for zuestoll01
    rxTestSimple(dcsMaster, axisMaster, getIpPacketToEnzian(1, 512), PacketType.Ip, maxRetries = 1)

    // change host ID: address regs are in big endian
    csrMaster.write(ALLOC.readBack("IpDecoder")("ctrl", "ipAddress"), 0xc0_a8_80_48.toBytesBE)
    csrMaster.write(ALLOC.readBack("EthernetDecoder")("ctrl", "macAddress"), 0x0c_53_31_03_00_48L.toBytesBE.drop(2))

    rxTestSimple(dcsMaster, axisMaster, getIpPacketToEnzian(2, 512), PacketType.Ip, maxRetries = 1)
  }

  testWithDB("roundtrip-oncrpc-timestamped", Rx, Tx) { implicit dut =>
    // test routine:
    // - all cores start in PID 0 (IDLE)
    // - enable one RPC process with one service that can run on all cores
    // - install neighbor table entry for reply
    // - send first packet before core is scheduled
    // - core is preempted to run proc
    // - read first packet, check timestamps (queued)
    // - write first response, receive response and check
    // - read second packet (stalled)
    // - send second packet, check timestamps
    // - write second reponse, receive response and check
    var readingSecond = false

    // test timestamp collection with oncrpc call
    val delayed = 1000

    val (csrMaster, axisMaster, axisSlave, dcsMaster) = commonDutSetup(100)

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(csrMaster,
      procSrvMap = Seq(mkRandomProc(NUM_WORKER_CORES) -> Seq(RpcSrvDef.mkRandom)),
      packetDumpWorkspace = Some("roundtrip-oncrpc-timestamped")).head

    // first request packet
    val (packet, pld, xid) = getPacket()

    // first response data (longer than inline bytes)
    val respData = Random.nextBytes(512).toList
    val clientIp = packet.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac = packet.getHeader.getSrcAddr

    // second request packet
    val (packet2, pld2, xid2) = getPacket()

    // second response data (shorter than inline bytes)
    val respData2 = Random.nextBytes(16).toList
    val clientIp2 = packet2.get(classOf[IpV4Packet]).getHeader.getSrcAddr
    val clientMac2 = packet2.getHeader.getSrcAddr

    val (serverIp, serverMac) = enzianIpMacAddrs(1)

    // set up neighbor table for replies
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 0.toBytesLE)

    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_ipAddr"), clientIp2.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_macAddr"), clientMac2.getAddress.toList)
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_state"), 2.toBytesLE) // reachable
    csrMaster.write(ALLOC.readBack("IpEncoder")("ctrl", "neigh_idx"), 1.toBytesLE)

    var allDone = false
    // network-side thread
    fork {
      // send first packet -- host not ready yet, packet will be queued
      axisMaster.send(packet.getRawData.toList)
      println("Sent first request packet")

      // receive first response: check headers and data body
      val data = axisSlave.recv()
      val parsed = EthernetPacket.newPacket(data.toArray, 0, data.length)
      assert(parsed.getHeader.getDstAddr == clientMac, "received packet has wrong destination MAC address")
      assert(parsed.getHeader.getSrcAddr == serverMac, "received packet has wrong source MAC address")
      assert(parsed.get(classOf[IpV4Packet]).getHeader.getDstAddr == clientIp, "received packet has wrong destination IP address")
      assert(parsed.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "received packet has wrong source IP address")
      assert(parsed.get(classOf[UdpPacket]).getHeader.getSrcPort == packet.get(classOf[UdpPacket]).getHeader.getDstPort, "received packet has wrong source port")
      assert(parsed.get(classOf[UdpPacket]).getHeader.getDstPort == packet.get(classOf[UdpPacket]).getHeader.getSrcPort, "received packet has wrong destination port")

      val udpPayload = parsed.get(classOf[UdpPacket]).getPayload.getRawData.toList
      val (rpcHdr, rpcPayload) = udpPayload.splitAt(24) // XID + msgType + replyStat + verifier + acceptStat
      assert(rpcHdr.take(4) == xid.toBytesBE, "XID mismatch")

      check(respData, rpcPayload)
      println("Received and checked first response")

      // wait until host is ready and is actively reading,
      // to generate timestamps for a core that's waiting
      waitUntil(readingSecond)

      // ensure the host is actually reading
      sleepCycles(delayed)

      // send second packet -- host already reading and stalled
      axisMaster.send(packet2.getRawData.toList)
      println("Sent second request packet")

      val data2 = axisSlave.recv()
      val parsed2 = EthernetPacket.newPacket(data2.toArray, 0, data2.length)
      assert(parsed2.getHeader.getDstAddr == clientMac2, "received packet has wrong destination MAC address")
      assert(parsed2.getHeader.getSrcAddr == serverMac, "received packet has wrong source MAC address")
      assert(parsed2.get(classOf[IpV4Packet]).getHeader.getDstAddr == clientIp2, "received packet has wrong destination IP address")
      assert(parsed2.get(classOf[IpV4Packet]).getHeader.getSrcAddr == serverIp, "received packet has wrong source IP address")
      assert(parsed2.get(classOf[UdpPacket]).getHeader.getSrcPort == packet2.get(classOf[UdpPacket]).getHeader.getDstPort, "received packet has wrong source port")
      assert(parsed2.get(classOf[UdpPacket]).getHeader.getDstPort == packet2.get(classOf[UdpPacket]).getHeader.getSrcPort, "received packet has wrong destination port")

      val udpPayload2 = parsed2.get(classOf[UdpPacket]).getPayload.getRawData.toList
      val (rpcHdr2, rpcPayload2) = udpPayload2.splitAt(24)
      assert(rpcHdr2.take(4) == xid2.toBytesBE, "XID mismatch")
      check(respData2, rpcPayload2)
      println("Received and checked second response")

      allDone = true
    }

    // we should receive the request on worker core #0
    val cs = workerCore(0)

    cs.log("Wait until a user thread is scheduled")
    cs.waitUser()

    val tid = cs.currThread.get.tid
    cs.log("Entered user thread")

    // for checking timestamps
    sleepCycles(delayed)

    // read first request
    val firstXid = {
      val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      // check if decoded packet is what we sent
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid, f"xid mismatch: expected $xid%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld, dcsMaster.read(overflowAddr, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = csrMaster.read(ALLOC.readBack("profiler")("cycles"), 8).bytesToBigInt

      // we don't use the commit timestamp since commit is tied to read next
      val ts = getRxTimestamps(csrMaster)

      // check timestamps for first packet
      import ts._

      println(s"Current timestamp after packet 1 done: $curr")

      assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, curr))
      assert(readStart - entry >= delayed)

      info.xid // host will see XID in big endian; should be sent back as is
    }

    // send first response
    {
      val (respInlineData, respTail) = respData.splitAt(ONCRPC_INLINE_BYTES)
      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData.length, funcPtr, firstXid, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    // we can now read second packet
    readingSecond = true

    val secondXid = {
      val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result.get
      val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
      val receivedXid = Integer.reverseBytes(info.xid.toInt)
      assert(receivedXid == xid2, f"xid2 mismatch: expected $xid2%#x, got $receivedXid%x")

      checkOncRpcCall(desc, desc.len, funcPtr, pld2, dcsMaster.read(overflowAddr, desc.len))
      exitCriticalSection(dcsMaster, tid)

      val curr = csrMaster.read(ALLOC.readBack("profiler")("cycles"), 8).bytesToBigInt
      val ts = getRxTimestamps(csrMaster)
      import ts._
      println(s"Current timestamp after packet 2 done: $curr")
      assert(isSorted(readStart, entry, afterRxQueue, enqueueToHost, curr))
      assert(entry - readStart >= delayed)

      info.xid
    }

    // send second response
    {
      val (respInlineData, respTail) = respData2.splitAt(ONCRPC_INLINE_BYTES)
      assert(respTail.isEmpty, "should not have any tail data")

      // XXX: Reply TX descriptor length is the entire response message length (INCLUDES inlined bytes)
      val desc = TxOncRpcReplySim(respData2.length, funcPtr, secondXid, respInlineData.bytesToBigInt)

      txSendSingle(dcsMaster, desc, respTail, tid)
      println("Written response")
    }

    waitUntil(allDone)
  }

  /* Test that Lauberhorn can scale up to multiple services */
  testWithDB("rx-sched-idle-scale-many", Rx) { implicit dut =>
    // do not use every core for every service
    // three procs: A (2 thr); B (3 thr); C (3 thr)

    val srvDefs = Seq(
      mkRandomProc(2) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq(RpcSrvDef.mkRandom),
      mkRandomProc(3) -> Seq.fill(2)(RpcSrvDef.mkRandom),
    )
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)

    val srvs = oncRpcCallPacketFactory(csrMaster, srvDefs,
      Some("rx-sched-idle-scale-many"))
    // sending packets is based on per service
    val pktsToSendStructured = srvDefs.map { case (_, ss) =>
      ss.map(_ => Random.between(50, 100))
    }
    val pktsToSend = pktsToSendStructured.flatten
    val pktsSent = mutable.ArrayBuffer.fill(srvs.length)(0)

    // receiving packets is based on per proc
    val pktsExpecting = pktsToSendStructured.map(_.sum)
    val pktsReceived = mutable.ArrayBuffer.fill(srvDefs.length)(0)

    // (PID, XID) => (packet, payload)
    val pktsToReceive = mutable.Map[(Int, Int), (EthernetPacket, List[Byte], Long)]()

    def pidToTblIdx(pid: Int) = srvDefs.indexWhere { case (pdef, _) => pdef.pid == pid } + 1

    // each thread has a max number of retries
    val threadRetryMap = mutable.HashMap[Int, Int]()

    fork {
      while (pktsToSend.sum > pktsSent.sum) {
        val srvToSend = Random.nextInt(srvs.length)
        if (pktsToSend(srvToSend) > pktsSent(srvToSend)) {
          val (funcPtr, getPacket, pid) = srvs(srvToSend)

          // This test is designed to allow all cores to receive all packets sent; we need to wait if the queue for the
          // corresponding PID is about to overflow.  If the scheduler failed to preempt some core to handle a non-empty
          // queue, the core reads will eventually run out of retries.
          val pidIdx = pidToTblIdx(pid)
          println(f"Checking queue capacity for PID $pid%#x (index $pidIdx)")
          csrMaster.write(ALLOC.readBack("sched")("stat", "readback_idx"), pidIdx.toBytesLE)
          val queueFill = csrMaster.read(ALLOC.readBack("sched")("stat", "readback_queueFill"), 8).bytesToBigInt
          println(f"PID $pid%#x has $queueFill elements queued in scheduler")

          if (queueFill >= RX_PKTS_PER_PROC.get - 5) {
            println(f"Trying to send for srvId $srvToSend: PID $pid%#x's queue is almost full, skipping sending and throttling")
            sleepCycles(200)
          } else {
            val (pkt, pld, xid) = getPacket()
            println(f"Sending packet for PID $pid%#x with XID $xid%#x")
            val toSend = pkt.getRawData.toList
            axisMaster.send(toSend)

            assert(!pktsToReceive.contains((pid, xid)), "random packet generation collision")
            pktsToReceive((pid, xid)) = (pkt, pld, funcPtr)
            pktsSent(srvToSend) += 1
          }
        }
      }
    }

    0 until NUM_WORKER_CORES foreach { wcid =>
      fork {
        val cs = workerCore(wcid)

        cs.log("Wait until a user thread is scheduled")
        cs.waitUser()

        def tid = cs.currThread.get.tid
        def pid = cs.currThread.get.proc.pid

        def procLog(msg: String) = cs.log(f"<pid $pid%#x> $msg")
        def currIdx = pidToTblIdx(pid) - 1

        while (pktsExpecting.sum != pktsReceived.sum) {
          if (pktsExpecting(currIdx) > pktsReceived(currIdx)) {
            cs.waitUser()
            procLog("try receive one")

            // this read might be launched before the queue was empty
            val descOption = tryReadPacketDesc(dcsMaster, tid, exitCS = false).result
            if (descOption.nonEmpty) {
              // reset retry count for this thread
              threadRetryMap(tid) = 0

              val (desc, overflowAddr) = descOption.get
              val info = desc.asInstanceOf[OncRpcCallRxPacketDescSim]
              procLog(s"received status $desc")
              val tail = dcsMaster.read(overflowAddr, desc.len)
              procLog(s"received trailing payload ${tail.bytesToHex} (len ${desc.len})")

              exitCriticalSection(dcsMaster, tid)
              procLog("finished receiving")
              sleepCycles(Random.between(50, 100))

              val xid = Integer.reverseBytes(info.xid.toInt)
              if (!pktsToReceive.contains((pid, xid))) {
                procLog(f"!!! XID $xid%#x not found!  Following XIDs have been sent for us:")
                pktsToReceive.view.filterKeys(_._1 == pid).foreach { case ((_, x), _) =>
                  println(f"XID $x%#x")
                }
                simFailure("XID not found")
              }
              val (pkt, pld, funcPtr) = pktsToReceive((pid, xid))
              procLog(f"received xid $xid%x, expecting packet $pkt")
              checkOncRpcCall(desc, desc.len, funcPtr, pld, tail)

              procLog(s"finished (simulated) processing packet #${pktsReceived.sum}")
              pktsReceived(currIdx) += 1
            } else {
              procLog(s"try receive timed out, checking if process is finished...")

              val retries = threadRetryMap.getOrElseUpdate(tid, 0)
              assert(retries <= 5, "ran out of retries for thread")
              threadRetryMap(tid) += 1
            }
          } else {
            procLog("process finished receiving, waiting for preemption...")
            waitUntil(cs.inISR)
          }
        }
      }
    }

    waitUntil(pktsExpecting.sum == pktsReceived.sum)
  }

  /** After preemption, no CLs should be Shared -- otherwise we leak a descriptor from the previous
    * application on this core */
  testWithDB("rx-preempt-no-leaking", Rx) { implicit dut =>
    // Spawn two services.  Send a few requests for the first one, drain all of them, then
    // send a request to the second service.  After the core finishes preemption, check if
    // no info is leaked (the new process only sees a NACK in the opposite CL)
    // TODO
  }

  /* Test killing a process that did not unset BUSY */
  testWithDB("rx-sched-crit-timeout", Rx) { implicit dut =>

  }
}
