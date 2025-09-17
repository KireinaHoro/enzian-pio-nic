package lauberhorn

import spinal.core._
import spinal.core.internals.Phase
import spinal.core.sim._

import jsteward.blocks.mem.xpm.PhaseMemBlackBoxingXpm

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

object Config {
  val outputDirectory = "hw/gen"

  def spinal(outDir: String = outputDirectory, blackboxPolicy: MemBlackboxingPolicy = blackboxAll) = SpinalConfig(
    targetDirectory = outDir,
    memBlackBoxers = ArrayBuffer[Phase](new PhaseMemBlackBoxingXpm(blackboxPolicy)),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC,
    ),
    onlyStdLogicVectorAtTopLevelIo = true,
    // FIXME: this is here due to DMA tag very big at the descriptor mux; is this problematic?
    bitVectorWidthMax = 16384,
  )

  def sim = SimConfig.withConfig(spinal(blackboxPolicy = blackboxOnlyIfRequested).includeSimulation)
    .withFstWave
    .withVerilator
    .allOptimisation
}
