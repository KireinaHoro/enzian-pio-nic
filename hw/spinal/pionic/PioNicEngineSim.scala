package pionic

import axi.sim.{Axi4Master, Axi4StreamMaster, Axi4StreamSlave}
import spinal.core._
import spinal.core.sim._

import scala.util._

case class PacketDescSim(addr: Int, size: Int) {
  def toBigInt: BigInt = BigInt(((size & 0xffff) << 16) | (addr & 0xffff))

  def toByteArray: Array[Byte] = toBigInt.toByteArray.reverse.padTo(8, 0.toByte)
}

object PioNicEngineSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig()

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .compile(PioNicEngine())

  // TODO: test for various failures
  dut.doSim("rx-regular") { dut =>
    SimTimeout(2000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.io.s_axis_rx, dut.clockDomain)
    // write global config bundle
    val rxBlockCycles = 100

    // the tx interface should never be active!
    dut.clockDomain.onSamplings {
      assert(!dut.io.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(0, BigInt(rxBlockCycles).toByteArray.padTo(8, 0.toByte))
    var data = master.read(0, 8)
    assert(BigInt(data.reverse).toInt == rxBlockCycles, "global config bundle mismatch")

    data = master.read(0x1000, 8)
    assert(data.toRxPacketDesc.isEmpty, "should not have packet on standby yet")

    val toSend = new Array[Byte](256)
    Random.nextBytes(toSend)
    fork {
      sleep(20)
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // test for actually receiving a packet
    data = master.read(0x1000, 8)

    val desc = data.toRxPacketDesc.get
    println(s"Received status register: $desc")
    assert(desc.size == toSend.length, s"packet length mismatch: expected ${toSend.length}, got ${desc.size}")
    assert(desc.addr % implicitly[PioNicConfig].axisConfig.dataWidth == 0, "rx buffer not aligned!")

    // read memory and check data
    data = master.read(0x100000 + desc.addr, desc.size)
    assert(data sameElements toSend,
      s"""data mismatch:
         |expected: "${toSend.toByteString}"
         |got:      "${data.toByteString}"
         |""".stripMargin)

    // free packet buffer
    println(s"desc $desc to bytes: ${desc.toByteArray.toByteString}")
    master.write(0x1008, desc.toByteArray)
    data = master.read(0x1020, 8)
    val counter = BigInt(data.reverse).toInt
    assert(counter == 1, s"retired packet count mismatch: expected 1, got $counter")

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  dut.doSim("tx-regular") { dut =>
    SimTimeout(2000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisSlave = Axi4StreamSlave(dut.io.m_axis_tx, dut.clockDomain)

    // get tx buffer address
    var data = master.read(0x1010, 8)
    val desc = data.toTxPacketDesc
    assert(desc.addr % implicitly[PioNicConfig].axisConfig.dataWidth == 0, "tx buffer not aligned!")
    println(s"Tx packet desc: $desc")

    val toSend = new Array[Byte](256)
    Random.nextBytes(toSend)
    assert(toSend.length <= desc.size, s"packet to send too big: requested ${toSend.length}, actual: ${desc.size}")

    // write packet data
    master.write(0x100000 + desc.addr, toSend)
    // write tx commit
    master.write(0x1018, BigInt(toSend.length).toByteArray.reverse.padTo(8, 0.toByte))
    // receive from axis
    data = axisSlave.recv()
    assert(data sameElements toSend,
      s"""data mismatch:
         |expected: "${toSend.toByteString}"
         |got:      "${data.toByteString}"
         |""".stripMargin)

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
    }

    dut.clockDomain.waitActiveEdgeWhere(master.idle && doneIssuing)
  }
}
