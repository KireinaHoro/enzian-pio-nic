package pionic

import spinal.core._
import spinal.core.sim._

import scala.language.postfixOps

object Config {
  val outputDirectory = "hw/gen"

  def spinal(outDir: String = outputDirectory, blackboxPolicy: MemBlackboxingPolicy = blackboxAllWhatsYouCan) = SpinalConfig(
    targetDirectory = outDir,
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC,
    ),
    onlyStdLogicVectorAtTopLevelIo = true
  ).addStandardMemBlackboxing(blackboxPolicy)

  def sim = SimConfig.withConfig(spinal(blackboxPolicy = blackboxOnlyIfRequested))
    .withFstWave
    .withVerilator
    .allOptimisation
}
