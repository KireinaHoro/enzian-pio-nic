package axi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

case class AxiDmaCmd(dmaConfig: AxiDmaConfig) extends Bundle {
  import dmaConfig._
  val addr = UInt(axiConfig.addressWidth bits)
  val len = UInt(lenWidth bits)
  val tag = UInt(tagWidth bits)
}

case class AxiDmaReadDescStatus(dmaConfig: AxiDmaConfig) extends Bundle {
  import dmaConfig._
  val tag = UInt(tagWidth bits)
  val error = Bits(4 bits)
}

case class AxiDmaWriteDescStatus(dmaConfig: AxiDmaConfig) extends Bundle {
  import dmaConfig._
  val len = UInt(lenWidth bits)
  val tag = UInt(tagWidth bits)
  val id = UInt((if (axisConfig.useId) axisConfig.idWidth else 0) bits)
  val dest = UInt((if (axisConfig.useDest) axisConfig.destWidth else 0) bits)
  val user = UInt((if (axisConfig.useUser) axisConfig.userWidth else 0) bits)
  val error = Bits(4 bits)
}

case class AxiDmaConfig(axiConfig: Axi4Config,
                        axisConfig: Axi4StreamConfig,
                        axiMaxBurstLen: Int = 16,
                        lenWidth: Int = 20,
                        tagWidth: Int = 8) {
  val readDescConfig = Axi4StreamCustomConfig(
    payloadType = AxiDmaCmd(this),
    useId = axisConfig.useId,
    idWidth = axisConfig.idWidth,
    useDest = axisConfig.useDest,
    destWidth = axisConfig.destWidth,
    useUser = axisConfig.useUser,
    userWidth = axisConfig.userWidth,
  )
  val readDescBus = Axi4StreamCustom(readDescConfig)
  val readDescStatusBus = Flow(AxiDmaReadDescStatus(this))

  val writeDescConfig = Axi4StreamCustomConfig(
    payloadType = AxiDmaCmd(this),
  )
  val writeDescBus = Axi4StreamCustom(writeDescConfig)
  val writeDescStatusBus = Flow(AxiDmaWriteDescStatus(this))
}

class AxiDma(dmaConfig: AxiDmaConfig,
             enableSG: Boolean = false,
             enableUnaligned: Boolean = false,
            ) extends BlackBox {
  private val axiConfig = dmaConfig.axiConfig
  private val axisConfig = dmaConfig.axisConfig

  val generic = new Generic {
    val AXI_DATA_WIDTH = axiConfig.dataWidth
    val AXI_ADDR_WIDTH = axiConfig.addressWidth
    val AXI_STRB_WIDTH = axiConfig.dataWidth / 8
    val AXI_ID_WIDTH = axiConfig.idWidth
    val AXI_MAX_BURST_LEN = dmaConfig.axiMaxBurstLen
    val AXIS_DATA_WIDTH = axisConfig.dataWidth
    val AXIS_KEEP_ENABLE = axisConfig.useKeep
    val AXIS_KEEP_WIDTH = axisConfig.dataWidth / 8
    val AXIS_LAST_ENABLE = axisConfig.useLast
    val AXIS_ID_ENABLE = axisConfig.useId
    val AXIS_ID_WIDTH = axisConfig.idWidth
    val AXIS_DEST_ENABLE = axisConfig.useDest
    val AXIS_DEST_WIDTH = axisConfig.destWidth
    val AXIS_USER_ENABLE = axisConfig.useUser
    val AXIS_USER_WIDTH = axisConfig.userWidth
    val LEN_WIDTH = dmaConfig.lenWidth
    val TAG_WIDTH = dmaConfig.tagWidth
    val ENABLE_SG = enableSG
    val ENABLE_UNALIGNED = enableUnaligned
  }
  val modName = "axi_dma"
  setBlackBoxName(modName)

  val trimmedAxiConfig = axiConfig.copy(useQos = false, useRegion = false)

  val io = new Bundle {
    val clk = in Bool()
    val rst = in Bool()

    val s_axis_read_desc = slave(dmaConfig.readDescBus)
    val m_axis_read_desc_status = master(dmaConfig.readDescStatusBus)
    val m_axis_read_data = master(Axi4Stream(axisConfig))

    val s_axis_write_desc = slave(dmaConfig.writeDescBus)
    val m_axis_write_desc_status = master(dmaConfig.writeDescStatusBus)
    val s_axis_write_data = slave(Axi4Stream(axisConfig))

    val m_axi = master(Axi4(trimmedAxiConfig))
  }

  mapCurrentClockDomain(io.clk, io.rst)

  noIoPrefix()

  addPrePopTask { () =>
    axi.renameAxi4IO(io)
    // axi.renameAxi4StreamIO(io)
  }

  addRTLPath(axiRTLFile(modName))
  addRTLPath(axiRTLFile("axi_dma_rd"))
  addRTLPath(axiRTLFile("axi_dma_wr"))
}
