import spinal.core._
import spinal.lib.bus.amba4.axi._

import scala.collection.mutable

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

  // tracked by: https://github.com/SpinalHDL/SpinalHDL/issues/1258
  class Arrayer[T <: Data](dataType: => T, count: Int) extends Area {
    def apply(i: Int) = accesses(i)

    val mapping = mutable.LinkedHashMap[BaseType, Bits]()
    val template = dataType.setName("")
    val accesses = Component.current.parent.rework {
      Vec.fill(count)(dataType.setAsDirectionLess())
    }
    val keys = template.flatten
    // Generate the Bits array from the template
    for (e <- keys) {
      val array = Bits(widthOf(e) * count bits).setCompositeName(this, e.getName())
      e.getDirection match {
        case in => in(array)
        case out => out(array)
      }
      mapping(e) = array
      e.removeStatement()
    }
    // Generate the parent component access points
    Component.current.parent.rework {
      val accessesKeys = accesses.map(_.flatten)
      for (i <- 0 until keys.size) {
        val key = mapping(keys(i))
        val slices = key.subdivideIn(count slices)
        for (sliceId <- 0 until count) {
          key.getDirection match {
            case `in` => slices(sliceId) := accessesKeys(sliceId)(i).asBits
            case `out` => accessesKeys(sliceId)(i).assignFromBits(slices(sliceId))
          }
        }
      }
    }
  }
}
