package pionic.host

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.misc.sim.BigIntRicher
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import pionic._
import pionic.sim._
import spinal.lib.BytesRicher

package object pcie {
  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PcieHostPacketDescSim] = {
    import Widths._
    val numBytes = roundUp(aw+lw+tw+dw, 8) / 8
    val data = master.read(coreBlock("hostRx"), numBytes).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(PcieHostPacketDescSim.fromBigInt(data >> 1))
    }
  }

  def readTxBufDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PacketBufDescSimPcie] = {
    import Widths._
    val data = master.read(coreBlock("hostTx"), 8).bytesToBigInt
    if (data(0)) {
      Some(PacketBufDescSimPcie(data(aw downto 1), data(aw + lw downto aw + 1)))
    } else {
      None
    }
  }
}
