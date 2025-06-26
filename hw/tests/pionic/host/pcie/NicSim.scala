package pionic.host.pcie

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.isSorted
import pionic._
import pionic.Global._
import pionic.sim._
import spinal.core._
import spinal.core.sim.{SimBigIntPimper => _, _}
import spinal.lib.{io => _, _}
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axis.sim._

import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._

class NicSim extends DutSimFunSuite[NicEngine] with DbFactory with OncRpcSuiteFactory with TimestampSuiteFactory {
  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("pcie")
    .compile(pionic.GenEngineVerilog.engine(8, "pcie"))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val globalBlock = ALLOC.readBack("global")
    val coreBlock = ALLOC.readBack("core")

    SimTimeout(1e10.toLong)

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    val pcieIf = dut.host[PcieBridgeInterfacePlugin].logic.get
    val master = Axi4Master(pcieIf.s_axi, dut.clockDomain)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, master, rxBlockCycles)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    (master, axisMaster, axisSlave)
  }

  def tryReadRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[PcieHostPacketDescSim]] = {
    if (maxTries == 0) done(None)
    else {
      println(s"Reading packet desc, $maxTries tries left...")
      readRxPacketDesc(master, coreBlock) match {
        case Some(x) => done(Some(x))
        case None =>
          sleepCycles(200)
          tailcall(tryReadRxPacketDesc(master, coreBlock, maxTries - 1))
      }
    }
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (axiMaster, axisMaster, _) = commonDutSetup(rxBlockCycles)
    (axiMaster, axisMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (axiMaster, _, axisSlave) = commonDutSetup(10000) // arbitrary rxBlockCycles
    (axiMaster, axisSlave)
  }

  /** test sending a ethernet frame to trigger bypass */
  def rxTestBypass(master: Axi4Master, axisMaster: Axi4StreamMaster)(implicit dut: NicEngine) = {
    val globalBlock = ALLOC.readBack("global")
    val coreBlock = ALLOC.readBack("core", blockIdx = 0)
    val pktBufAddr = ALLOC.readBack("pkt")("buffer")

    import PacketType._
    val (packet, proto) = randomPacket(MTU)(Ethernet, Ip, Udp)

    fork {
      sleepCycles(20)

      val toSend = packet.getRawData.toList
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    val counterBefore = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt

    // set promisc mode
    // TODO: also test non promisc mode
    master.write(globalBlock("promisc"), 1.toBytes)

    // test for actually receiving a packet
    // since we didn't arm any ONCRPC services, it should always be bypass
    val desc = tryReadRxPacketDesc(master, coreBlock).result.get
    println(s"Received status register: $desc")

    assert(desc.addr % DATAPATH_WIDTH.get == 0, "rx buffer not aligned!")
    assert(desc.addr < PKT_BUF_RX_SIZE_PER_CORE.get + PKT_BUF_TX_SIZE_PER_CORE, f"packet address out of bounds: ${desc.addr}%#x")

    // check received protocol headers
    assert(desc.isInstanceOf[BypassPacketDescSimPcie], "should only receive bypass packet!")
    val bypassDesc = desc.asInstanceOf[BypassPacketDescSimPcie]
    assert(proto.id == bypassDesc.packetType, s"proto mismatch: expected $proto, got ${PacketType(bypassDesc.packetType.toInt)}")
    checkHeader(proto, packet, bypassDesc.hdrP4jPacket)

    val payload = getPayloadAndCheckLen(packet, proto, desc.size.toInt)

    // read memory and check payload data
    val data = master.read(pktBufAddr + desc.addr, payload.length)
    check(payload, data)

    // free packet buffer
    master.write(coreBlock("hostRxAck"), desc.toRxAck.toBytes)

    val counter = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt
    assert(counter == 1 + counterBefore, s"retired packet count mismatch: expected ${counterBefore + 1}, got $counter")
  }

  // TODO: test for various failures
  testWithDB("rx-regular") { implicit dut =>
    val coreBlock = ALLOC.readBack("core")
    val (master, axisMaster) = rxDutSetup(10000)

    assert(readRxPacketDesc(master, coreBlock).isEmpty, "should not have packet on standby yet")

    // reset packet allocator
    master.write(coreBlock("allocReset"), 1.toBytes);
    sleepCycles(200)
    master.write(coreBlock("allocReset"), 0.toBytes);

    // test for 200 runs
    0 until 200 foreach { _ => rxTestBypass(master, axisMaster) }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  testWithDB("tx-regular") { implicit dut =>
    // test sending with bypass core
    val coreBlock = ALLOC.readBack("core")
    val pktBufAddr = ALLOC.readBack("pkt")("buffer")

    val (master, axisSlave) = txDutSetup()

    // get tx buffer address
    val desc = readTxBufDesc(master, coreBlock).get
    assert(desc.addr % DATAPATH_WIDTH.get == 0, "tx buffer not aligned!")
    println(s"Tx packet desc: $desc")

    val toSend = Random.nextBytes(256).toList
    assert(toSend.length <= desc.size, s"packet to send too big: requested ${toSend.length}, actual: ${desc.size}")

    // write packet data
    master.write(pktBufAddr + desc.addr, toSend)
    // receive from axis

    var checkDone = false
    axisSlave.recvCB() { data =>
      check(toSend, data)
      checkDone = true
    }
    // write tx commit -- make sure axis have a chance to catch the first beat
    println(s"Sending ${toSend.length} bytes")
    master.write(coreBlock("hostTxAck"), desc.copy(size = toSend.length).toTxDesc)

    dut.clockDomain.waitActiveEdgeWhere(checkDone)
  }

  /** test enabling a ONCRPC service */
  testWithDB("rx-oncrpc-roundrobin") { implicit dut =>
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    val globalBlock = ALLOC.readBack("global")
    val pktBufAddr = ALLOC.readBack("pkt")("buffer")

    val (master, axisMaster) = rxDutSetup(10000)

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(globalBlock("rxBlockCycles"), 100.toBytes) // rxBlockCycles

    val (funcPtr, getPacket, pid) = oncRpcCallPacketFactory(master, globalBlock,
      packetDumpWorkspace = Some("rx-oncrpc-roundrobin")
    )

    val mask = b"01101110"
    master.write(globalBlock("workerCoreMask"), mask.toBytes) // mask

    // test round-robin
    Seq.fill(50)(0 until NUM_CORES).flatten
      .filter(idx => ((1 << idx) & mask) != 0).foreach { idx =>
        val (packet, payload, xid) = getPacket()
        val toSend = packet.getRawData.toList
        axisMaster.sendCB(toSend)()

        // we skip the bypass core
        val coreBlock = ALLOC.readBack("core", idx + 1)
        val desc = tryReadRxPacketDesc(master, coreBlock).result.get
        println(f"Received status register: $desc")

        assert(desc.isInstanceOf[OncRpcCallPacketDescSimPcie], s"desc type unexpected")
        checkOncRpcCall(desc, desc.size.toInt, funcPtr, payload, master.read(pktBufAddr + desc.addr, desc.size))

        // free packet
        master.write(coreBlock("hostRxAck"), desc.toRxAck.toBytes)
      }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  testWithDB("rx-timestamped-queued") { implicit dut =>
    // test timestamp collection with oncrpc call
    val (master, axisMaster) = rxDutSetup(10000)

    val globalBlock = ALLOC.readBack("global")
    val coreBlock = ALLOC.readBack("core", blockIdx = 1)

    val (_, getPacket, pid) = oncRpcCallPacketFactory(master, globalBlock)
    val (packet, _, _) = getPacket()
    val toSend = packet.getRawData.toList

    axisMaster.send(toSend)
    // ensure that the packet has landed
    val delayed = 1000
    sleepCycles(delayed)

    val desc = readRxPacketDesc(master, coreBlock).get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxAck"), desc.toRxAck.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(entry, afterRxQueue, enqueueToHost, readStart, afterRead, timestamp, afterRxCommit))
    assert(readStart - entry >= delayed - 2)
  }

  testWithDB("rx-timestamped-stalled") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(10000)

    val globalBlock = ALLOC.readBack("global")
    val coreBlock = ALLOC.readBack("core", blockIdx = 1)

    val (_, getPacket, pid) = oncRpcCallPacketFactory(master, globalBlock)
    val (packet, _, _) = getPacket()
    val toSend = packet.getRawData.toList
    val delayed = 500

    fork {
      sleepCycles(delayed)

      axisMaster.send(toSend)
    }

    val desc = readRxPacketDesc(master, coreBlock).get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxAck"), desc.toRxAck.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(readStart, entry, afterRxQueue, enqueueToHost, afterRead, timestamp, afterRxCommit))
    assert(entry - readStart >= delayed)
  }

  testWithDB("tx-timestamped") { implicit dut =>
    val (master, axisSlave) = txDutSetup()

    val globalBlock = ALLOC.readBack("global")
    val coreBlock = ALLOC.readBack("core")
    val pktBufAddr = ALLOC.readBack("pkt")("buffer")

    val toSend = Random.nextBytes(256).toList
    val desc = readTxBufDesc(master, coreBlock).get
    val delayed = 500

    master.write(pktBufAddr + desc.addr, toSend)

    // insert delay
    fork {
      sleepCycles(delayed)
      master.write(coreBlock("hostTxAck"), desc.copy(size = toSend.length).toTxDesc)
    }

    // receive packet, check timestamps
    axisSlave.recv()

    val timestamps = getTxTimestamps(master, globalBlock)
    import timestamps._

    assert(isSorted(acquire, afterTxCommit, afterDmaRead, exit))
    assert(afterTxCommit - acquire >= delayed)
  }
}
