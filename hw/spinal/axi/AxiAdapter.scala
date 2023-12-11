package axi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class AxiAdapter(inConfig: Axi4Config, outDataWidth: Int, convertBurst: Boolean = true, convertNarrowBurst: Boolean = false, forwardId: Boolean = false) extends BlackBox {
  val generic = new Generic {
    val ADDR_WIDTH = inConfig.addressWidth
    val S_DATA_WIDTH = inConfig.dataWidth
    val S_STRB_WIDTH = inConfig.dataWidth / 8
    val M_DATA_WIDTH = outDataWidth
    val M_STRB_WIDTH = outDataWidth / 8
    val ID_WIDTH = if (inConfig.useId) inConfig.idWidth else 0
    val AWUSER_ENABLE = inConfig.useAwUser
    val AWUSER_WIDTH = if (inConfig.useAwUser) inConfig.awUserWidth else 0
    val WUSER_ENABLE = inConfig.useWUser
    val WUSER_WIDTH = if (inConfig.useWUser) inConfig.wUserWidth else 0
    val BUSER_ENABLE = inConfig.useBUser
    val BUSER_WIDTH = if (inConfig.useBUser) inConfig.bUserWidth else 0
    val ARUSER_ENABLE = inConfig.useArUser
    val ARUSER_WIDTH = if (inConfig.useArUser) inConfig.arUserWidth else 0
    val RUSER_ENABLE = inConfig.useRUser
    val RUSER_WIDTH = if (inConfig.useRUser) inConfig.rUserWidth else 0
    val CONVERT_BURST = convertBurst
    val CONVERT_NARROW_BURST = convertNarrowBurst
    val FORWARD_ID = forwardId
  }
  val modName = "axi_adapter"
  setBlackBoxName(modName)

  val io = new Bundle {
    val clk = in Bool()
    val rst = in Bool()

    val s_axi = slave(Axi4(inConfig))
    val m_axi = master(Axi4(inConfig.copy(dataWidth = outDataWidth)))
  }

  mapCurrentClockDomain(io.clk, io.rst)
  noIoPrefix()
  addPrePopTask { () =>
    axi.renameAxi4IO(io)
  }

  addRTLPath(axiRTLFile(modName))
  addRTLPath(axiRTLFile(s"${modName}_rd"))
  addRTLPath(axiRTLFile(s"${modName}_wr"))
}
