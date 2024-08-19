package pionic.host.pcie

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.DutSimFunSuite
import pionic._
import spinal.core._
import spinal.core.sim.{SimBigIntPimper => _, _}
import spinal.lib._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axis.sim._

import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._

class NicSim extends DutSimFunSuite[NicEngine] {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig(
    numCores = 8, // to test for dispatching
  )

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .workspaceName("pcie")
    .compile(pionic.GenEngineVerilog.engineFromName("pcie"))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("core")

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    val pcieIf = dut.host[PcieBridgeInterfacePlugin].logic.get
    val master = Axi4Master(pcieIf.s_axi, dut.clockDomain)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, master, rxBlockCycles)(nicConfig)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    (master, axisMaster, axisSlave)
  }

  def tryReadPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[PacketDescSim]] = {
    if (maxTries == 0) done(None)
    else {
      println(s"Reading packet desc, $maxTries tries left...")
      master.read(coreBlock("hostRx"), 8).toRxPacketDesc match {
        case Some(x) => done(Some(x))
        case None =>
          sleepCycles(200)
          tailcall(tryReadPacketDesc(master, coreBlock, maxTries - 1))
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

  def rxTestBypass(master: Axi4Master, axisMaster: Axi4StreamMaster, toSend: List[Byte])(implicit dut: NicEngine) = {
    val coreBlock = nicConfig.allocFactory.readBack("core", blockIdx = 0)
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    fork {
      sleepCycles(20)
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    val counterBefore = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt

    // test for actually receiving a packet
    val desc = tryReadPacketDesc(master, coreBlock).result.get
    println(s"Received status register: $desc")
    assert(desc.size == toSend.length, s"packet length mismatch: expected ${toSend.length}, got ${desc.size}")
    assert(desc.addr % implicitly[PioNicConfig].axisDataWidth == 0, "rx buffer not aligned!")
    assert(desc.addr < nicConfig.pktBufSize, f"packet address out of bounds: ${desc.addr}%#x")

    // read memory and check data
    val data = master.read(pktBufAddr + desc.addr, desc.size)
    check(toSend, data)

    // free packet buffer
    println(s"desc $desc to bytes: ${desc.toBigInt.hexString}")
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val counter = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt
    assert(counter == 1 + counterBefore, s"retired packet count mismatch: expected ${counterBefore + 1}, got $counter")
  }

  // TODO: test for various failures
  test("rx-regular") { implicit dut =>
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val (master, axisMaster) = rxDutSetup(10000)

    var data = master.read(coreBlock("hostRxNext"), 8)
    assert(data.toRxPacketDesc.isEmpty, "should not have packet on standby yet")

    // set core mask to only schedule to core 0
    val mask = b"00000001"
    master.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // reset packet allocator
    master.write(coreBlock("allocReset"), 1.toBytes);
    sleepCycles(200)
    master.write(coreBlock("allocReset"), 0.toBytes);

    // test for 200 runs
    for (size <- Iterator.from(1).map(_ * 64).takeWhile(_ <= 512)) {
      val toSend = Random.nextBytes(size).toList
      0 until 50 foreach { _ => rxTestSimple(master, axisMaster, toSend) }
    }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  test("tx-regular") { implicit dut =>
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    val (master, axisSlave) = txDutSetup()

    // get tx buffer address
    var data = master.read(coreBlock("hostTx"), 8)
    val desc = data.toTxPacketDesc
    assert(desc.addr % implicitly[PioNicConfig].axisDataWidth == 0, "tx buffer not aligned!")
    println(s"Tx packet desc: $desc")

    val toSend = Random.nextBytes(256).toList
    assert(toSend.length <= desc.size, s"packet to send too big: requested ${toSend.length}, actual: ${desc.size}")

    // write packet data
    master.write(pktBufAddr + desc.addr, toSend)
    // receive from axis
    axisSlave.recvCB() { data =>
      check(toSend, data)
    }
    // write tx commit -- make sure axis have a chance to catch the first beat
    master.write(coreBlock("hostTxAck"), toSend.length.toBytes)

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  test("rx-roundrobin-with-mask") { implicit dut =>
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    val globalBlock = nicConfig.allocFactory.readBack("global")

    val (master, axisMaster) = rxDutSetup(10000)

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(globalBlock("rxBlockCycles"), 100.toBytes) // rxBlockCycles

    val mask = b"01101110"
    master.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // wait for the mask to take effect
    sleepCycles(20)

    val toSend = Random.nextBytes(256).toList

    // test round robin
    Seq.fill(3)(0 until nicConfig.numCores).flatten
      .filter(idx => ((1 << idx) & mask) != 0).foreach { idx =>
        axisMaster.sendCB(toSend)()

        val coreBlock = nicConfig.allocFactory.readBack("control", idx)
        var descOption: Option[PacketDescSim] = None
        var tries = 5
        while (descOption.isEmpty && tries > 0) {
          descOption = master.read(coreBlock("hostRxNext"), 8).toRxPacketDesc
          tries -= 1
        }
        val desc = descOption.get
        println(f"Received packet @ ${desc.addr}%#x, ${desc.size} B")
        assert(desc.size == toSend.length, s"packet length mismatch: got ${desc.size}, expected ${toSend.length}")
        // we don't need to check data
      }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  def getRxTimestamps(master: Axi4Master, globalBlock: RegBlockReadBack) = {
    new {
      val entry = master.read(globalBlock("lastProfile", "RxCmacEntry"), 8).bytesToBigInt
      val afterRxQueue = master.read(globalBlock("lastProfile", "RxAfterCdcQueue"), 8).bytesToBigInt
      val readStart = master.read(globalBlock("lastProfile", "RxCoreReadStart"), 8).bytesToBigInt
      val afterRead = master.read(globalBlock("lastProfile", "RxCoreReadFinish"), 8).bytesToBigInt
      val afterDmaWrite = master.read(globalBlock("lastProfile", "RxAfterDmaWrite"), 8).bytesToBigInt
      val afterRxCommit = master.read(globalBlock("lastProfile", "RxCoreCommit"), 8).bytesToBigInt

      println(s"RxCmacEntry: $entry")
      println(s"RxAfterCdcQueue: $afterRxQueue")
      println(s"RxCoreReadStart: $readStart")
      println(s"RxCoreReadFinish: $afterRead")
      println(s"RxAfterDmaWrite: $afterDmaWrite")
      println(s"RxCoreCommit: $afterRxCommit")
    }
  }

  def getTxTimestamps(master: Axi4Master, globalBlock: RegBlockReadBack) = {
    new {
      val acquire = master.read(globalBlock("lastProfile", "TxCoreAcquire"), 8).bytesToBigInt
      val afterTxCommit = master.read(globalBlock("lastProfile", "TxCoreCommit"), 8).bytesToBigInt
      val afterDmaRead = master.read(globalBlock("lastProfile", "TxAfterDmaRead"), 8).bytesToBigInt
      val exit = master.read(globalBlock("lastProfile", "TxCmacExit"), 8).bytesToBigInt

      println(s"TxCoreAcquire: $acquire")
      println(s"TxCoreCommit: $afterTxCommit")
      println(s"TxAfterDmaRead: $afterDmaRead")
      println(s"TxCmacExit: $exit")
    }
  }

  test("rx-timestamped-queued") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(10000)

    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    val toSend = Random.nextBytes(256).toList

    axisMaster.send(toSend)
    // ensure that the packet has landed
    val delayed = 1000
    sleepCycles(delayed)

    var data = master.read(coreBlock("hostRxNext"), 8)
    val desc = data.toRxPacketDesc.get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(entry, afterRxQueue, afterDmaWrite, readStart, afterRead, timestamp, afterRxCommit))
    assert(readStart - entry >= delayed - 2)
  }

  test("rx-timestamped-stalled") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(10000)

    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    val toSend = Random.nextBytes(256).toList
    val delayed = 500

    fork {
      sleepCycles(delayed)

      axisMaster.send(toSend)
    }

    var data = master.read(coreBlock("hostRxNext"), 8)
    val desc = data.toRxPacketDesc.get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(readStart, entry, afterRxQueue, afterDmaWrite, afterRead, timestamp, afterRxCommit))
    assert(entry - readStart >= delayed)
  }

  test("tx-timestamped") { implicit dut =>
    val (master, axisSlave) = txDutSetup()

    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    val toSend = Random.nextBytes(256).toList
    val desc = master.read(coreBlock("hostTx"), 8).toTxPacketDesc
    val delayed = 500

    master.write(pktBufAddr + desc.addr, toSend)

    // insert delay
    fork {
      sleepCycles(delayed)
      master.write(coreBlock("hostTxAck"), toSend.length.toBytes)
    }

    // receive packet, check timestamps
    axisSlave.recv()

    val timestamps = getTxTimestamps(master, globalBlock)
    import timestamps._

    assert(isSorted(acquire, afterTxCommit, afterDmaRead, exit))
    assert(afterTxCommit - acquire >= delayed)
  }
}
