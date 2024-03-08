package pionic.sim

import spinal.core.sim._
import pionic._
import pionic.pcie.PcieBridgeInterfacePlugin
import spinal.lib.bus.amba4.axis.sim._

object XilinxCmacSim {
  def cmacDutSetup(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    cmacIf.cmacRxClock.forkStimulus(period = 4) // 250 MHz
    cmacIf.cmacTxClock.forkStimulus(period = 4) // 250 MHz

    (Axi4StreamMaster(cmacIf.s_axis_rx, cmacIf.cmacRxClock), Axi4StreamSlave(cmacIf.m_axis_tx, cmacIf.cmacTxClock))
  }
}
