package pionic.host.eci

import jsteward.blocks.eci.sim.DcsAppMaster
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.isSorted
import org.pcap4j.packet.Packet
import org.scalatest.exceptions.TestFailedException
import pionic._
import pionic.sim._
import pionic.sim.PacketType.PacketType
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}

import scala.collection.mutable
import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._
import org.scalatest.tagobjects.Slow

class NicSim extends DutSimFunSuite[NicEngine] with OncRpcSuiteFactory with TimestampSuiteFactory {
  implicit val c = new ConfigDatabase
  c.post("host interface", "eci")

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("eci")
    .compile(pionic.GenEngineVerilog.engine(c))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core")

    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, csrMaster, rxBlockCycles)

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get
    for (i <- rxNextCl.indices) { rxNextCl(i) = 0 }

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)
    (csrMaster, axisMaster, dcsMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles
    for (i <- txNextCl.indices) { txNextCl(i) = 0 }

    (csrMaster, axisSlave, dcsMaster)
  }

  // we have one more core as bypass
  val rxNextCl = mutable.ArrayBuffer.fill(c[Int]("num cores") + 1)(0)
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, cid: Int, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[(EciHostCtrlInfoSim, BigInt)]] = {
    if (maxTries == 0) done(None)
    else {
      val clAddr = rxNextCl(cid) * 0x80 +
        c[Int]("eci rx base") + c[Int]("eci core offset") * cid
      val overflowAddr = clAddr + 0x40
      println(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      // XXX: we do not check if the cacheline stays idempotent (refer to EciDecoupledRxTxProtocol)
      val control = dcsMaster.read(clAddr, 64, doInvIdemptCheck = false).bytesToBigInt
      // always toggle cacheline
      rxNextCl(cid) = 1 - rxNextCl(cid)
      if ((control & 1) == 0) {
        sleepCycles(20)
        tailcall(tryReadPacketDesc(dcsMaster, cid, maxTries - 1))
      } else {
        // got packet!
        done(Some((EciHostCtrlInfoSim.fromBigInt(control >> 1), overflowAddr)))
      }
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
        c[Int]("eci rx base") +
          c[Int]("eci overflow offset"),
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
    val allocFactory = dut.host[ConfigDatabase].f
    val coreBlock = allocFactory.readBack("core", blockIdx = 0)

    // assert(tryReadPacketDesc(dcsMaster, cid, maxTries = maxRetries + 1).result.isEmpty, "should not have packet on standby yet")

    // reset packet allocator
    csrMaster.write(coreBlock("allocReset"), 1.toBytes)
    sleepCycles(200)
    csrMaster.write(coreBlock("allocReset"), 0.toBytes)

    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        import PacketType._
        val (packet, proto) = randomPacket(size, randomizeLen = false)(Ethernet, Ip, Udp)
        rxTestSimple(dcsMaster, axisMaster, packet, proto, maxRetries = maxRetries)
      }
    }

    // TODO: check DCS master cacheline state
  }

  test("rx-bypass-scan-sizes", Slow) { implicit dut =>
    // set a large enough rx block cycles, such that there shouldn't be a need to retry
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(5000000) // 20 ms @ 250 MHz
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")

    // enable promisc mode
    csrMaster.write(globalBlock("promisc"), 1.toBytes)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 9618, 64, maxRetries = 0)
  }

  test("rx-bypass-simple") { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")

    // enable promisc mode
    csrMaster.write(globalBlock("promisc"), 1.toBytes)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 256, 64, maxRetries = 5)
  }

  /** test enabling an ONCRPC service */
  test("rx-oncrpc-roundrobin") { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(1000)
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")

    val (funcPtr, getPacket) = oncRpcCallPacketFactory(csrMaster, globalBlock, dumpPacket = true)

    Seq.fill(50)(0 until c[Int]("num cores")).flatten.foreach { idx =>
      val (packet, payload) = getPacket()
      val toSend = packet.getRawData.toList
      // asynchronously send
      axisMaster.sendCB(toSend)()

      val cid = idx + 1
      val (desc, overflowAddr) = tryReadPacketDesc(dcsMaster, cid).result.get
      println(f"Received status register: $desc")

      checkOncRpcCall(desc, desc.len, funcPtr, payload, dcsMaster.read(overflowAddr, desc.len))
    }
  }

  var txNextCl = mutable.ArrayBuffer.fill(c[Int]("num cores"))(0)
  def txSimple(dcsMaster: DcsAppMaster, axisSlave: Axi4StreamSlave, toSend: List[Byte], cid: Int = 0)(implicit dut: NicEngine): Unit = {
    var received = false
    fork {
      val data = axisSlave.recv()
      check(toSend, data)
      println(s"Core $cid: packet received from TX interface and validated")
      received = true
    }

    def clAddr = txNextCl(cid) * 0x80 +
      c[Int]("eci tx base") +
      c[Int]("eci core offset") * cid

    val txDesc = ErrorCtrlInfoSim(toSend.length)
    println(f"Core $cid: sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, txDesc.toTxDesc)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      dcsMaster.write(
        c[Int]("eci tx base") +
          c[Int]("eci overflow offset") +
          c[Int]("eci core offset") * cid,
        toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    println(f"Core $cid: sent packet at $clAddr%#x, waiting validation...")

    txNextCl(cid) = 1 - txNextCl(cid)
    dcsMaster.read(clAddr, 1)

    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  // TODO: convert this to a tx bypass test
  def txTestRange(csrMaster: AxiLite4Master, axisSlave: Axi4StreamSlave, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, cid: Int)(implicit dut: NicEngine) = {
    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        txSimple(dcsMaster, axisSlave, toSend, cid)
      }
    }
  }

  def txScanOnCore(cid: Int) = test(s"tx-scan-sizes-core$cid", Slow) { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()

    txTestRange(csrMaster, axisSlave, dcsMaster, 64, 9618, 64, cid)
  }

  0 until c[Int]("num cores") foreach txScanOnCore

  test("tx-all-cores-serialized") { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()

    0 until c[Int]("num cores") foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(csrMaster, axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }

  test("tx-no-voluntary-inv") { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()
    dcsMaster.voluntaryInvProb = 0
    dcsMaster.doPartialWrite = false

    0 until c[Int]("num cores") foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(csrMaster, axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }

  test("rx-bypass-pipelined") { implicit dut =>
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)

    val numPackets = 5

    // enable promisc mode
    csrMaster.write(globalBlock("promisc"), 1.toBytes)

    val toCheck = new mutable.ArrayDeque[(Packet, PacketType)]
    val size = 128
    fork {
      0 until numPackets foreach { pid =>
        import PacketType._
        val (packet, proto) = randomPacket(size)(Ethernet, Ip, Udp)
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

  test("rx-timestamped-queued") { implicit dut =>
    // test timestamp collection with oncrpc call
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")
    // test on first non-bypass core
    val coreBlock = allocFactory.readBack("core", blockIdx = 1)
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)

    val (_, getPacket) = oncRpcCallPacketFactory(csrMaster, globalBlock)
    val (packet, _) = getPacket()
    val toSend = packet.getRawData.toList

    axisMaster.send(toSend)
    // ensure that packet has landed in the queue
    val delayed = 1000
    sleepCycles(delayed)

    val (desc, _) = tryReadPacketDesc(dcsMaster, 1).result.get
    val timestamp = csrMaster.read(globalBlock("cycles"), 8).bytesToBigInt

    // we don't use the commit timestamp since commit is tied to read next
    val timestamps = getRxTimestamps(csrMaster, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, timestamp))
    assert(readStart - entry >= delayed)
  }

  test("rx-timestamped-stalled") { implicit dut =>
    // test timestamp collection with oncrpc call
    val allocFactory = dut.host[ConfigDatabase].f
    val globalBlock = allocFactory.readBack("global")
    // test on first non-bypass core
    val coreBlock = allocFactory.readBack("core", blockIdx = 1)
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(2000)

    val (_, getPacket) = oncRpcCallPacketFactory(csrMaster, globalBlock)
    val (packet, _) = getPacket()
    val toSend = packet.getRawData.toList

    // we test only one iteration -- no retries
    val delayed = 1000

    fork {
      sleepCycles(delayed)
      axisMaster.send(toSend)
    }

    val (desc, _) = tryReadPacketDesc(dcsMaster, 1, maxTries = 1).result.get
    val timestamp = csrMaster.read(globalBlock("cycles"), 8).bytesToBigInt

    val timestamps = getRxTimestamps(csrMaster, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(readStart, entry, afterRxQueue, enqueueToHost, timestamp))
    assert(entry - readStart >= delayed)
  }
}
