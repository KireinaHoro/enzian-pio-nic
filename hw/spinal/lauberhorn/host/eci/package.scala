package lauberhorn.host

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4
import lauberhorn.Global._

import scala.language.postfixOps

package object eci {
  def checkEciAxiCmd(axi: Axi4): Unit = {
    when (axi.ar.valid) {
      // must match that of desc_to_axi/axi_rd_cl.sv
      // 128B INCR bursts (2 beats on the 512b bus)
      assert(axi.ar.size  === U("111"),     "only support 128B in each beat")
      assert(axi.ar.burst === B("01"),      "only support INCR bursts")
      assert(axi.ar.len   === 0,            "only support 1 beat in each burst")
      assert(axi.ar.addr(6 downto 0) === 0, "only support 128B-aligned addresses")
    }

    when (axi.aw.valid) {
      // must match that of desc_to_axi/axi_wr_cl.sv
      // 128B INCR bursts (2 beats on the 512b bus)
      assert(axi.aw.size  === U("111"),     "only support 128B in each beat")
      assert(axi.aw.burst === B("01"),      "only support INCR bursts")
      assert(axi.aw.len   === 0,            "only support 1 beat in each burst")
      assert(axi.aw.addr(6 downto 0) === 0, "only support 128B-aligned addresses")
    }
  }
}
