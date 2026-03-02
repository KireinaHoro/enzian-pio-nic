package lauberhorn.host.eci

import jsteward.blocks.eci.EciChannel
import jsteward.blocks.misc.TraceBuffer
import lauberhorn.Config

object GenEciTraceBuffer extends App {
  Config.spinal("hw/gen/eci").generateVerilog {
    // 6 channels:
    // req_wod_slave, rsp_wod_slave, rsp_wd_slave
    // rsp_wod_master, rsp_wd_master, fwd_wod_master
    // we only log the header (no CL contents)
    TraceBuffer(EciChannel(), 6, 512).setDefinitionName("dcs_eci_buf")
  }
}
