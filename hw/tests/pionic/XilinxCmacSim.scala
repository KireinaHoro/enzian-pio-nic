package pionic

import spinal.core.IntToBuilder
import spinal.core.sim._
import spinal.lib.bus.amba4.axis.sim._

import scala.language.postfixOps

object XilinxCmacSim {
  def cmacDutSetup(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    cmacIf.cmacRxClock.forkStimulus(frequency = 250 MHz)
    cmacIf.cmacTxClock.forkStimulus(frequency = 250 MHz)

    (Axi4StreamMaster(cmacIf.s_axis_rx, cmacIf.cmacRxClock), Axi4StreamSlave(cmacIf.m_axis_tx, cmacIf.cmacTxClock))
  }
}
