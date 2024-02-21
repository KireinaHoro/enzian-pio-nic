package pionic.pcie.sim

import pionic.{Config, PioNicConfig}
import pionic.pcie.PcieEngine
import spinal.core._
import spinal.core.sim.{SimBigIntPimper => _, _}
import spinal.lib._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axis.sim._
import jsteward.blocks.misc.RegBlockReadBack

import scala.util._
import scala.util.control.TailCalls._

object NicSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig(
    numCores = 8, // to test for dispatching
  )

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .compile(PcieEngine())

  def cyc(c: Int)(implicit dut: PcieEngine): TimeNumber = dut.clockDomain.frequency.getValue.toTime * c / 1000

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: PcieEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    dut.clockDomain.forkStimulus(period = 4) // 250 MHz
    dut.cmacRxClock.forkStimulus(period = 4) // 250 MHz
    dut.cmacTxClock.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.io.s_axis_rx, dut.cmacRxClock)
    val axisSlave = Axi4StreamSlave(dut.io.m_axis_tx, dut.cmacTxClock)

    // reset value of dispatch mask should be all 1
    val dispatchMask = master.read(globalBlock("dispatchMask"), 8).bytesToBigInt
    assert(dispatchMask == ((1 << nicConfig.numCores) - 1), f"dispatch mask should be all 1 on reset; got $dispatchMask%#x")

    // reset value of rx alloc reset should be 0
    val allocReset = master.read(coreBlock("allocReset"), 8).bytesToBigInt
    assert(allocReset == 0, "rx alloc reset should be low at boot")

    // write global config bundle
    master.write(0, rxBlockCycles.toBytes)

    var data = master.read(globalBlock("ctrl"), 8)
    assert(data.bytesToBigInt == rxBlockCycles, "global config bundle mismatch")

    (master, axisMaster, axisSlave)
  }

  def tryReadPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack, maxTries: Int = 20)(implicit dut: PcieEngine): TailRec[Option[PacketDescSim]] = {
    if (maxTries == 0) done(None)
    else {
      println(s"Reading packet desc, $maxTries tries left...")
      master.read(coreBlock("hostRxNext"), 8).toRxPacketDesc match {
        case Some(x) => done(Some(x))
        case None =>
          sleep(cyc(200))
          tailcall(tryReadPacketDesc(master, coreBlock, maxTries - 1))
      }
    }
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: PcieEngine) = {
    // the tx interface should never be active!
    dut.cmacTxClock.onSamplings {
      assert(!dut.io.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (axiMaster, axisMaster, _) = commonDutSetup(rxBlockCycles)
    (axiMaster, axisMaster)
  }

  def txDutSetup()(implicit dut: PcieEngine) = {
    val (axiMaster, _, axisSlave) = commonDutSetup(10000) // arbitrary rxBlockCycles
    (axiMaster, axisSlave)
  }

  def rxSimple(master: Axi4Master, axisMaster: Axi4StreamMaster, toSend: List[Byte])(implicit dut: PcieEngine) = {
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    fork {
      sleep(cyc(20))
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    val counterBefore = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt

    // test for actually receiving a packet
    val desc = tryReadPacketDesc(master, coreBlock).result.get
    println(s"Received status register: $desc")
    assert(desc.size == toSend.length, s"packet length mismatch: expected ${toSend.length}, got ${desc.size}")
    assert(desc.addr % implicitly[PioNicConfig].axisConfig.dataWidth == 0, "rx buffer not aligned!")
    assert(desc.addr < nicConfig.pktBufSize, f"packet address out of bounds: ${desc.addr}%#x")

    // read memory and check data
    val data = master.read(pktBufAddr + desc.addr, desc.size)
    assert(data == toSend,
      s"""data mismatch:
         |expected: "${toSend.bytesToHex}"
         |got:      "${data.bytesToHex}"
         |""".stripMargin)

    // free packet buffer
    println(s"desc $desc to bytes: ${desc.toBigInt.hexString}")
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val counter = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt
    assert(counter == 1 + counterBefore, s"retired packet count mismatch: expected ${counterBefore + 1}, got $counter")
  }

  // TODO: test for various failures
  dut.doSim("rx-regular") { implicit dut =>
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val (master, axisMaster) = rxDutSetup(100)

    var data = master.read(coreBlock("hostRxNext"), 8)
    assert(data.toRxPacketDesc.isEmpty, "should not have packet on standby yet")

    // set core mask to only schedule to core 0
    val mask = b"00000001"
    master.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // reset packet allocator
    master.write(coreBlock("allocReset"), 1.toBytes);
    sleep(cyc(200))
    master.write(coreBlock("allocReset"), 0.toBytes);

    // test for 200 runs
    for (size <- Iterator.from(1).map(_ * 64).takeWhile(_ <= 512)) {
      val toSend = Random.nextBytes(size).toList
      0 until 50 foreach { _ => rxSimple(master, axisMaster, toSend) }
    }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  dut.doSim("tx-regular") { implicit dut =>
    SimTimeout(cyc(2000))

    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    val (master, axisSlave) = txDutSetup()

    // get tx buffer address
    var data = master.read(coreBlock("hostTx"), 8)
    val desc = data.toTxPacketDesc
    assert(desc.addr % implicitly[PioNicConfig].axisConfig.dataWidth == 0, "tx buffer not aligned!")
    println(s"Tx packet desc: $desc")

    val toSend = Random.nextBytes(256).toList
    assert(toSend.length <= desc.size, s"packet to send too big: requested ${toSend.length}, actual: ${desc.size}")

    // write packet data
    master.write(pktBufAddr + desc.addr, toSend)
    // receive from axis
    axisSlave.recvCB() { data =>
      assert(data == toSend,
        s"""data mismatch:
           |expected: "${toSend.bytesToHex}"
           |got:      "${data.bytesToHex}"
           |""".stripMargin)
    }
    // write tx commit -- make sure axis have a chance to catch the first beat
    master.write(coreBlock("hostTxAck"), toSend.length.toBytes)

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  dut.doSim("rx-roundrobin-with-mask") { implicit dut =>
    SimTimeout(cyc(4000))

    val globalBlock = nicConfig.allocFactory.readBack("global")

    val (master, axisMaster) = rxDutSetup(10000)

    // the tx interface should never be active!
    dut.cmacTxClock.onSamplings {
      assert(!dut.io.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(globalBlock("ctrl"), 100.toBytes) // rxBlockCycles

    val mask = b"01100111"
    master.write(globalBlock("dispatchMask"), mask.toBytes) // mask

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

  def getRxTimestamps(master: Axi4Master, coreBlock: RegBlockReadBack) = {
    new {
      val entry = master.read(coreBlock("hostRxLastProfile", "Entry"), 8).bytesToBigInt
      val afterRxQueue = master.read(coreBlock("hostRxLastProfile", "AfterRxQueue"), 8).bytesToBigInt
      val readStart = master.read(coreBlock("hostRxLastProfile", "ReadStart"), 8).bytesToBigInt
      val afterRead = master.read(coreBlock("hostRxLastProfile", "AfterRead"), 8).bytesToBigInt
      val afterDmaWrite = master.read(coreBlock("hostRxLastProfile", "AfterDmaWrite"), 8).bytesToBigInt
      val afterRxCommit = master.read(coreBlock("hostRxLastProfile", "AfterRxCommit"), 8).bytesToBigInt

      println(s"Entry: $entry")
      println(s"AfterRxQueue: $afterRxQueue")
      println(s"ReadStart: $readStart")
      println(s"AfterRead: $afterRead")
      println(s"AfterDmaWrite: $afterDmaWrite")
      println(s"AfterCommit: $afterRxCommit")
    }
  }

  def getTxTimestamps(master: Axi4Master, coreBlock: RegBlockReadBack) = {
    new {
      val acquire = master.read(coreBlock("hostTxLastProfile", "Acquire"), 8).bytesToBigInt
      val afterTxCommit = master.read(coreBlock("hostTxLastProfile", "AfterTxCommit"), 8).bytesToBigInt
      val afterDmaRead = master.read(coreBlock("hostTxLastProfile", "AfterDmaRead"), 8).bytesToBigInt
      val exit = master.read(coreBlock("hostTxLastProfile", "Exit"), 8).bytesToBigInt

      println(s"Acquire: $acquire")
      println(s"AfterTxCommit: $afterTxCommit")
      println(s"AfterDmaRead: $afterDmaRead")
      println(s"Exit: $exit")
    }
  }

  dut.doSim("rx-timestamped-queued") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(100)

    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    val toSend = Random.nextBytes(256).toList

    axisMaster.send(toSend)
    // ensure that the packet has landed
    val delayed = 1000
    sleep(cyc(delayed))

    var data = master.read(coreBlock("hostRxNext"), 8)
    val desc = data.toRxPacketDesc.get
    val timestamp = master.read(globalBlock("cyclesCount"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val timestamps = getRxTimestamps(master, coreBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(entry, afterRxQueue, afterDmaWrite, readStart, afterRead, timestamp, afterRxCommit))
    assert(readStart - entry >= delayed - 2)
  }

  dut.doSim("rx-timestamped-stalled") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(1000)

    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    val toSend = Random.nextBytes(256).toList
    val delayed = 500

    fork {
      sleep(cyc(delayed))

      axisMaster.send(toSend)
    }

    var data = master.read(coreBlock("hostRxNext"), 8)
    val desc = data.toRxPacketDesc.get
    val timestamp = master.read(globalBlock("cyclesCount"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)

    val timestamps = getRxTimestamps(master, coreBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(readStart, entry, afterRxQueue, afterDmaWrite, afterRead, timestamp, afterRxCommit))
    assert(entry - readStart >= delayed)
  }

  dut.doSim("tx-timestamped") { implicit dut =>
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
      sleep(cyc(delayed))
      master.write(coreBlock("hostTxAck"), toSend.length.toBytes)
    }

    // receive packet, check timestamps
    axisSlave.recv()

    val timestamps = getTxTimestamps(master, coreBlock)
    import timestamps._

    assert(isSorted(acquire, afterTxCommit, afterDmaRead, exit))
    assert(afterTxCommit - acquire >= delayed)
  }
}
