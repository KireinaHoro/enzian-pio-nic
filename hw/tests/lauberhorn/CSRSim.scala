package lauberhorn

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.misc.sim.IntRicherEndianAware
import spinal.core.assert
import spinal.lib.BytesRicher
import Global._

object CSRSim {
  def csrSanityChecks[B](bus: B, rxBlockCycles: Int)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    // write global config bundle
    val blockCyclesAddr = ALLOC.readBack("hostIf")("rxBlockCycles")
    asMaster.write(bus, blockCyclesAddr, rxBlockCycles.toBytesLE)

    val cycles = asMaster.read(bus, blockCyclesAddr, 8).bytesToBigInt
    assert(cycles == rxBlockCycles, s"global config bundle mismatch: $cycles vs $rxBlockCycles")
  }
}
