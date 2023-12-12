package axi

import spinal.core._
import spinal.lib._

class AxiDmaDescMux(
                     clientDmaConfig: AxiDmaConfig,
                     numPorts: Int = 2,
                     arbRoundRobin: Boolean = true,
                     arbLsbHighPriority: Boolean = true,
                   ) extends BlackBox {

  import clientDmaConfig._

  private val mTagWidth: Int = tagWidth + log2Up(numPorts)
  val masterDmaConfig: AxiDmaConfig = clientDmaConfig.copy(tagWidth = mTagWidth)

  val generic = new Generic {
    val PORTS = numPorts
    val AXI_ADDR_WIDTH = axiConfig.addressWidth
    val AXIS_ID_ENABLE = axisConfig.useId
    val AXIS_ID_WIDTH = masterDmaConfig.intfAxisConfig.idWidth
    val AXIS_DEST_ENABLE = axisConfig.useDest
    val AXIS_DEST_WIDTH = masterDmaConfig.intfAxisConfig.destWidth
    val AXIS_USER_ENABLE = axisConfig.useUser
    val AXIS_USER_WIDTH = masterDmaConfig.intfAxisConfig.userWidth
    val LEN_WIDTH = lenWidth
    val S_TAG_WIDTH = tagWidth
    val M_TAG_WIDTH = mTagWidth
    val ARB_TYPE_ROUND_ROBIN = arbRoundRobin
    val ARB_LSB_HIGH_PRIORITY = arbLsbHighPriority
  }
  val modName = "axi_dma_desc_mux"
  setBlackBoxName(modName)

  val io = new Bundle {
    val clk = in Bool()
    val rst = in Bool()

    // to get all the ports for both read and write
    val m_axis_desc = master(masterDmaConfig.readDescBus)
    val s_axis_desc_status = slave(masterDmaConfig.writeDescStatusBus)
  }

  val s_axis_desc = new Arrayer(slave(readDescBus), numPorts)
  val m_axis_desc_status = new Arrayer(master(writeDescStatusBus), numPorts)

  mapCurrentClockDomain(io.clk, io.rst)
  noIoPrefix()

  addPrePopTask { () =>
    axi.renameAxi4StreamIO(io)
  }

  addRTLPath(axiRTLFile(modName))
  addRTLPath(axiRTLFile("arbiter"))

  def connectRead(dma: AxiDma) {
    dma.io.s_axis_read_desc <> io.m_axis_desc
    io.s_axis_desc_status.assignSomeByName(dma.io.m_axis_read_desc_status)
    io.s_axis_desc_status.assignDontCareToUnasigned()
  }

  def connectWrite(dma: AxiDma) {
    dma.io.s_axis_write_desc <> io.m_axis_desc
    io.s_axis_desc_status << dma.io.m_axis_write_desc_status
  }
}
