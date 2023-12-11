import spinal.core._
import spinal.lib.bus.amba4.axi._

package object axi {
  def renameAxi4IO(io: Bundle): Unit = {
    io.flattenForeach { bt =>
      val pattern = "^([sm]_axi.*)(aw|w|b|ar|r)_(?:payload_)?([^_]+)$".r
      for (pm <- pattern.findFirstMatchIn(bt.getName)) {
        bt.setName(pm.group(1) + pm.group(2) + pm.group(3))
      }
    }
  }

  def renameAxi4StreamIO(io: Bundle): Unit = {
    io.flattenForeach { bt =>
      val pattern = "^([sm]_axis.*?)(?:payload_)+([^_]+)$".r
      for (pm <- pattern.findFirstMatchIn(bt.getName)) {
        bt.setName(pm.group(1) + pm.group(2))
      }
    }
  }

  def axiRTLFile(name: String) = s"hw/deps/verilog-axi/rtl/$name.v"

  class RichAxi4(axi: Axi4) {
    def resize(newWidth: Int): Axi4 = {
      val adapter = new AxiAdapter(axi.config, newWidth)
      axi >> adapter.io.s_axi
      adapter.io.m_axi
    }
  }

  implicit def augmentAxi4(axi: Axi4): RichAxi4 = new RichAxi4(axi)
}
