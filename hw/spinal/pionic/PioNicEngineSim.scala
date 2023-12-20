package pionic

import axi.sim.{Axi4Master, Axi4StreamMaster}
import spinal.core._
import spinal.core.sim._

import scala.util._

case class PacketDescSim(addr: Int, size: Int)

object PioNicEngineSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig()

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .compile(PioNicEngine())

  def decodeStatus(data: Array[Byte]) = {
    val d = BigInt(data.reverse).toLong
    if ((d & 1) == 0) {
      None
    } else {
      val desc = (d >> 1).toInt
      Some(PacketDescSim(desc & 0xffff, (desc >> 16) & 0xffff))
    }
  }

  dut.doSim { dut =>
    SimTimeout(1000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    val axisMaster = Axi4StreamMaster(dut.io.s_axis_rx, dut.clockDomain)
    // write global config bundle
    val rxBlockCycles = 100

    master.write(0, BigInt(rxBlockCycles).toByteArray) {
      master.read(0, 8) { data =>
        assert(BigInt(data.reverse).toInt == rxBlockCycles, "global config bundle mismatch")

        master.read(0x1000, 8) { data =>
          val status = decodeStatus(data)
          println(s"Received status register: $status")
          assert(status.isEmpty, "should not have packet on standby yet")

          val toSend = Array.fill(256)(Random.nextInt.toByte)
          // test for actually receiving a packet
          master.read(0x1000, 8) { data =>
            val status = decodeStatus(data)
            println(s"Received status register: $status")
            assert(status.get.size == toSend.length, s"packet length mismatch: expected ${toSend.length}, got ${status.size}")

            // read memory and check data
            master.read(0x100000 + status.get.addr, status.get.size) { data =>
              assert(data sameElements toSend,
                s"""data mismatch:
                   |expected: "${toSend.toByteString}"
                   |got:      "${data.toByteString}"""".stripMargin)

              // TODO: free packet buffer
            }
          }

          delayed(20) {
            axisMaster.send(toSend) {
              println(s"Sent packet of length ${toSend.length}")
            }
          }
        }
      }
    }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }
}
