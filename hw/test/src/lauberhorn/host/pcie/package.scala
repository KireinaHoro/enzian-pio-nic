package lauberhorn.host

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.misc.sim.{BigIntParser, BigIntRicher}
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import lauberhorn._
import lauberhorn.sim._
import spinal.lib.BytesRicher
import lauberhorn.Global._

package object pcie {
  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack): Option[PcieHostPacketDescSim] = {
    val data = master.read(coreBlock("hostRx"), HOST_REQ_WIDTH.get).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(PcieHostPacketDescSim.fromBigInt(data >> 1))
    }
  }

  def readTxBufDesc(master: Axi4Master, coreBlock: RegBlockReadBack): Option[ErrorPacketDescSimPcie] = {
    val data = master.read(coreBlock("hostTx"), 8).bytesToBigInt
    if (data(0)) {
      val dp = new BigIntParser(data)
      Some(ErrorPacketDescSimPcie(
        dp.pop(PKT_BUF_ADDR_WIDTH, skip = 1),
        dp.pop(PKT_BUF_LEN_WIDTH)))
    } else {
      None
    }
  }
}
