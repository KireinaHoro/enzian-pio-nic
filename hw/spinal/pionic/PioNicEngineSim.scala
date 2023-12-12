package pionic

import spinal.core._
import spinal.core.sim._

object PioNicEngineSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig()
  val spinalConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(250 MHz))

  SimConfig
    .withConfig(spinalConfig)
    .withFstWave
    .withVerilator
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE")
    .addSimulatorFlag("-Wwarn-ZEROREP -Wno-ZEROREP")
    .allOptimisation
    .compile(new PioNicEngine())
    .doSim { dut =>
      SimTimeout(1000)

      dut.clockDomain.forkStimulus(period = 10)
      simThread.suspend
    }
}
