package pionic

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.misc.sim.IntRicherEndianAware
import spinal.core.assert
import spinal.lib.BytesRicher

object CSRSim {
  def csrSanityChecks[B](globalBlock: RegBlockReadBack, bus: B, rxBlockCycles: Int)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    // reset value of rx alloc reset should be 0
    val allocReset = asMaster.read(bus, globalBlock("dmaCtrl", "allocReset"), 8).bytesToBigInt
    assert(allocReset == 0, "rx alloc reset should be low at boot")

    // write global config bundle
    asMaster.write(bus, 0, rxBlockCycles.toBytesLE)

    val cycles = asMaster.read(bus, globalBlock("csr", "rxBlockCycles"), 8).bytesToBigInt
    assert(cycles == rxBlockCycles, s"global config bundle mismatch: $cycles vs $rxBlockCycles")
  }
}
