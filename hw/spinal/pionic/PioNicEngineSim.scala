package pionic

import axi.sim.{Axi4Master, Axi4StreamMaster, Axi4StreamSlave}
import spinal.core._
import spinal.core.sim._
import spinal.lib.BinaryBuilder

import scala.util._

case class PacketDescSim(addr: BigInt, size: BigInt)(implicit config: PioNicConfig) {
  def toBigInt: BigInt = ((size & config.pktBufLenMask) << config.pktBufAddrWidth) | (addr & config.pktBufAddrMask)

  def toByteArray: Array[Byte] = toBigInt.toByteArray.reverse.padTo(8, 0.toByte)
}

object PacketDescSim {
  def fromBigInt(v: BigInt)(implicit config: PioNicConfig) = PacketDescSim(v & config.pktBufAddrMask, (v >> config.pktBufAddrWidth) & config.pktBufLenMask)
}

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

    master.write(0, rxBlockCycles.toSimPayload)
    var data = master.read(0, 8)
    assert(data.toInt == rxBlockCycles, "global config bundle mismatch")

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
    val counter = data.toInt
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
    // receive from axis
    axisSlave.recvCB() { data =>
      assert(data sameElements toSend,
        s"""data mismatch:
           |expected: "${toSend.toByteString}"
           |got:      "${data.toByteString}"
           |""".stripMargin)
    }
    // write tx commit -- make sure axis have a chance to catch the first beat
    master.write(0x1018, toSend.length.toSimPayload)

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  dut.doSim("rx-roundrobin-with-mask") { dut =>
    SimTimeout(4000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.io.s_axis_rx, dut.clockDomain)

    // the tx interface should never be active!
    dut.clockDomain.onSamplings {
      assert(!dut.io.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(0x0, 100.toSimPayload) // rxBlockCycles

    val mask = b"01100111"
    master.write(0x8, mask.toSimPayload) // mask

    val toSend = new Array[Byte](256)
    Random.nextBytes(toSend)

    // test round robin
    Seq.fill(3)(0 until nicConfig.numCores).flatten
      .filter(idx => ((1 << idx) & mask) != 0).foreach { idx =>
        axisMaster.sendCB(toSend)()

        val coreBase = 0x1000 * (idx + 1)
        var descOption: Option[PacketDescSim] = None
        var tries = 5
        while (descOption.isEmpty && tries > 0) {
          descOption = master.read(coreBase + 0, 8).toRxPacketDesc
          tries -= 1
        }
        val desc = descOption.get
        println(f"Received packet @ ${desc.addr}%#x, ${desc.size} B")
        assert(desc.size == toSend.length, s"packet length mismatch: got ${desc.size}, expected ${toSend.length}")
        // we don't need to check data
      }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }
}
