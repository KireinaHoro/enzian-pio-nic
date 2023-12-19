package pionic

import axi.sim.Axi4Master
import spinal.core._
import spinal.core.sim._

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext
import scala.util._

object PioNicEngineSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig()
  val spinalConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(250 MHz))

  val dut = SimConfig
    .withConfig(spinalConfig)
    .withFstWave
    .withVerilator
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .allOptimisation
    .compile(PioNicEngine())

  dut.doSim { dut =>
    SimTimeout(1000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    val master = Axi4Master(dut.io.s_axi, dut.clockDomain)
    // write global config bundle
    val rxBlockCycles = 100

    master.write(0, BigInt(rxBlockCycles).toByteArray) { () =>
      master.read(0, 8) { data =>
        assert(BigInt(data.reverse).toInt == rxBlockCycles, "global config bundle mismatch")

        master.read(0x1000, 8) { data =>
          val received = BigInt(data.reverse)
          println(f"Received status register: $received%#x")

          // ...
        }
      }
    }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }
}
