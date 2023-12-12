package pionic

import spinal.core._
import spinal.core.sim._

object PacketAllocSim extends App {
  // TODO: test on multiple configs
  implicit val nicConfig = PioNicConfig()
  val spinalConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(250 MHz))

  SimConfig
    .withConfig(spinalConfig)
    .withFstWave
    .allOptimisation
    .compile(new PacketAlloc(0, 0x1000))
    .doSim { dut =>
      SimTimeout(1000)

      dut.clockDomain.forkStimulus(period = 10)
      simThread.suspend
    }
}
