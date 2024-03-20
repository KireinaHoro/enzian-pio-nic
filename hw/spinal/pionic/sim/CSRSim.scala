package pionic.sim

import jsteward.blocks.misc.RegBlockReadBack
import pionic.PioNicConfig
import spinal.core.assert
import spinal.lib._

trait AsSimBusMaster[B] {
  def read(b: B, addr: BigInt, totalBytes: BigInt): List[Byte]
  def write(b: B, addr: BigInt, data: List[Byte]): Unit
}

object CSRSim {
  def csrSanityChecks[B](globalBlock: RegBlockReadBack, coreBlock: RegBlockReadBack, bus: B, rxBlockCycles: Int)(nicConfig: PioNicConfig)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    // reset value of dispatch mask should be all 1
    val dispatchMask = asMaster.read(bus, globalBlock("dispatchMask"), 8).bytesToBigInt
    assert(dispatchMask == ((1 << nicConfig.numCores) - 1), f"dispatch mask should be all 1 on reset; got $dispatchMask%#x")

    // reset value of rx alloc reset should be 0
    val allocReset = asMaster.read(bus, coreBlock("allocReset"), 8).bytesToBigInt
    assert(allocReset == 0, "rx alloc reset should be low at boot")

    // write global config bundle
    asMaster.write(bus, 0, rxBlockCycles.toBytes)

    val cycles = asMaster.read(bus, globalBlock("rxBlockCycles"), 8).bytesToBigInt
    assert(cycles == rxBlockCycles, s"global config bundle mismatch: $cycles vs $rxBlockCycles")
  }
}
