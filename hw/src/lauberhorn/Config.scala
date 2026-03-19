package lauberhorn

import spinal.core._
import spinal.core.internals.Phase
import spinal.core.sim._

import jsteward.blocks.mem.xpm.PhaseMemBlackBoxingXpm

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

object Config {
  def spinal(outDir: String, blackboxPolicy: MemBlackboxingPolicy = blackboxAll, prefix: String = "") = SpinalConfig(
    targetDirectory = outDir,
    memBlackBoxers = ArrayBuffer[Phase](new PhaseMemBlackBoxingXpm(blackboxPolicy)),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC,
    ),
    onlyStdLogicVectorAtTopLevelIo = true,
    // FIXME: this is here due to DMA tag very big at the descriptor mux; is this problematic?
    bitVectorWidthMax = 16384,
    defaultClockDomainFrequency = FixedFrequency(200 MHz),
    globalPrefix = prefix,
  )

  def sim = SimConfig
    .withConfig(spinal(
      outDir = ".",  // relative to sim target in out/
      blackboxPolicy = blackboxOnlyIfRequested
    ).includeSimulation)
    .cachePath("verilator-cache") // relative to sim target in out/
    .withFstWave
    .withVerilator
    .allOptimisation
}
