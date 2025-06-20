package pionic.host

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4

package object eci {
  def checkEciAxiCmd(axi: Axi4): Unit = {
    when (axi.ar.valid) {
      // must match that of desc_to_axi/axi_rd_cl.sv
      // 128B INCR bursts (2 beats on the 512b bus)
      assert(axi.ar.size  === U("110"),     "only support 64B in each beat")
      assert(axi.ar.burst === B("01"),      "only support INCR bursts")
      assert(axi.ar.len   === 1,            "only support 2 beats in each burst")
      assert(axi.ar.addr(6 downto 0) === 0, "only support 128B-aligned addresses")
    }

    when (axi.aw.valid) {
      // must match that of desc_to_axi/axi_wr_cl.sv
      // 128B INCR bursts (2 beats on the 512b bus)
      assert(axi.aw.size  === U("110"),     "only support 64B in each beat")
      assert(axi.aw.burst === B("01"),      "only support INCR bursts")
      assert(axi.aw.len   === 1,            "only support 2 beats in each burst")
      assert(axi.aw.addr(6 downto 0) === 0, "only support 128B-aligned addresses")
    }
  }
}
