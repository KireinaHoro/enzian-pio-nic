import spinal.core._

package object axi {
  def renameAxi4IO(io: Bundle): Unit = {
    io.flattenForeach { bt =>
      val pattern = "^([sm]_.*)(aw|w|b|ar|r)_(?:payload_)?([^_]+)$".r
      for (pm <- pattern.findFirstMatchIn(bt.getName)) {
        bt.setName(pm.group(1) + pm.group(2) + pm.group(3))
      }
    }
  }

  def axiRTLFile(name: String) = s"hw/deps/verilog-axi/rtl/$name.v"

  def dmaReadDescBundle()
}
