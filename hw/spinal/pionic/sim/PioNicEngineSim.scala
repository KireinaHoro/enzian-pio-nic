package pionic.sim

import pionic.{Config, PioNicConfig, PioNicEngine}

import spinal.core._
import spinal.core.sim.{SimBigIntPimper => _, _}
import spinal.lib._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axis.sim._

import jsteward.blocks.misc.RegBlockReadBack

import scala.util._

object PioNicEngineSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig(
    numCores = 8, // to test for dispatching
    pktBufAddrWidth = 32,
  )

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .compile(PioNicEngine())

  def cyc(c: Int)(implicit dut: PioNicEngine): TimeNumber = dut.clockDomain.frequency.getValue.toTime * c / 1000

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: PioNicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    SimTimeout(cyc(2000))
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz
    dut.cmacRxClock.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.cmacRxArea.s_axis_rx, dut.clockDomain)
    // write global config bundle

    // the tx interface should never be active!
    dut.clockDomain.onSamplings {
      assert(!dut.io.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    // reset value of dispatch mask should be all 1
    val dispatchMask = master.read(globalBlock("dispatchMask"), 8).bytesToBigInt
    assert(dispatchMask == ((1 << nicConfig.numCores) - 1), f"dispatch mask should be all 1 on reset; got $dispatchMask%#x")

    // reset value of rx alloc reset should be 0
    val allocReset = master.read(coreBlock("allocReset"), 8).bytesToBigInt
    assert(allocReset == 0, "rx alloc reset should be low at boot")

    master.write(0, rxBlockCycles.toBytes)

    var data = master.read(globalBlock("ctrl"), 8)
    assert(data.bytesToBigInt == rxBlockCycles, "global config bundle mismatch")

    (master, axisMaster)
  }

  // TODO: test for various failures
  dut.doSim("rx-regular") { implicit dut =>
    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    val rxBlockCycles = 100
    val (master, axisMaster) = rxDutSetup(rxBlockCycles)

    var data = master.read(coreBlock("hostRxNext"), 8)
    assert(data.toRxPacketDesc.isEmpty, "should not have packet on standby yet")

    val toSend = Random.nextBytes(256).toList
    fork {
      sleep(cyc(20))
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // test for actually receiving a packet
    data = master.read(coreBlock("hostRxNext"), 8)

    val desc = data.toRxPacketDesc.get
    println(s"Received status register: $desc")
    assert(desc.size == toSend.length, s"packet length mismatch: expected ${toSend.length}, got ${desc.size}")
    assert(desc.addr % implicitly[PioNicConfig].axisConfig.dataWidth == 0, "rx buffer not aligned!")

    // read memory and check data
    data = master.read(pktBufAddr + desc.addr, desc.size)
    assert(data == toSend,
      s"""data mismatch:
         |expected: "${toSend.bytesToHex}"
         |got:      "${data.bytesToHex}"
         |""".stripMargin)

    // free packet buffer
    println(s"desc $desc to bytes: ${desc.toBigInt.hexString}")
    master.write(coreBlock("hostRxNextAck"), desc.toBigInt.toBytes)
    data = master.read(coreBlock("rxPacketCount"), 8)
    val counter = data.bytesToBigInt
    assert(counter == 1, s"retired packet count mismatch: expected 1, got $counter")

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  dut.doSim("tx-regular") { implicit dut =>
    SimTimeout(cyc(2000))
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val coreBlock = nicConfig.allocFactory.readBack("control")
    val pktBufAddr = nicConfig.allocFactory.readBack("pkt")("buffer")

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisSlave = Axi4StreamSlave(dut.io.m_axis_tx, dut.clockDomain)

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
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz
    dut.cmacRxClock.forkStimulus(period = 4) // 250 MHz

    val globalBlock = nicConfig.allocFactory.readBack("global")

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.cmacRxArea.s_axis_rx, dut.cmacRxClock)

    // the tx interface should never be active!
    dut.clockDomain.onSamplings {
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

  def getTimestamps(master: Axi4Master, coreBlock: RegBlockReadBack) = {
    new {
      val entry = master.read(coreBlock("hostRxLastProfile", "Entry"), 8).bytesToBigInt
      val afterRxQueue = master.read(coreBlock("hostRxLastProfile", "AfterRxQueue"), 8).bytesToBigInt
      val readStart = master.read(coreBlock("hostRxLastProfile", "ReadStart"), 8).bytesToBigInt
      val afterRead = master.read(coreBlock("hostRxLastProfile", "AfterRead"), 8).bytesToBigInt
      val afterDmaWrite = master.read(coreBlock("hostRxLastProfile", "AfterDmaWrite"), 8).bytesToBigInt
      val afterCommit = master.read(coreBlock("hostRxLastProfile", "AfterCommit"), 8).bytesToBigInt

      println(s"Entry: $entry")
      println(s"AfterRxQueue: $afterRxQueue")
      println(s"ReadStart: $readStart")
      println(s"AfterRead: $afterRead")
      println(s"AfterDmaWrite: $afterDmaWrite")
      println(s"AfterCommit: $afterCommit")
    }
  }

  dut.doSim("rx-timestamped-queued") { implicit dut =>
    val rxBlockCycles = 100
    val (master, axisMaster) = rxDutSetup(rxBlockCycles)

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

    val timestamps = getTimestamps(master, coreBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(Seq(entry, afterRxQueue, afterDmaWrite, readStart, afterRead, timestamp, afterCommit)))
    assert(readStart - entry >= delayed - 2)
  }

  dut.doSim("rx-timestamped-stalled") { implicit dut =>
    val rxBlockCycles = 1000
    val (master, axisMaster) = rxDutSetup(rxBlockCycles)

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

    val timestamps = getTimestamps(master, coreBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(Seq(readStart, entry, afterRxQueue, afterDmaWrite, afterRead, timestamp, afterCommit)))
    assert(entry - readStart >= delayed)
  }
}
