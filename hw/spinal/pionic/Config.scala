package pionic

import spinal.core._
import spinal.core.sim._

import scala.language.postfixOps

object Config {
  val outputDirectory = "hw/gen"

  def spinal(outDir: String = outputDirectory) = SpinalConfig(
    targetDirectory = outDir,
    defaultClockDomainFrequency = FixedFrequency(250 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC,
    ),
    onlyStdLogicVectorAtTopLevelIo = true
  )

  def sim = SimConfig.withConfig(spinal())
    .withFstWave
    .withVerilator
    .allOptimisation
}
