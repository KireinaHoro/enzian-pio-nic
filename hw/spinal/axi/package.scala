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
      axi >> adapter.getSlave
      adapter.getMaster
    }

    def toSpinal(config: Axi4Config): Axi4 = {
      val ret = Axi4(config)
      val masterChannels: Seq[Axi4 => lib.Stream[_ <: Bundle]] = Seq(_.ar, _.aw, _.w)
      val slaveChannels: Seq[Axi4 => lib.Stream[_ <: Bundle]] = Seq(_.r, _.b)
      val driverChannels = if (axi.isMasterInterface) masterChannels else slaveChannels
      val loadChannels = if (axi.isMasterInterface) slaveChannels else masterChannels
      driverChannels.foreach { c =>
        c(ret) arbitrationFrom c(axi)
        c(ret).payload.assignSomeByName(c(axi).payload)
      }
      loadChannels.foreach { c =>
        c(axi) arbitrationFrom c(ret)
        c(axi).payload.assignSomeByName(c(ret).payload)
        c(axi).payload.assignDontCareToUnasigned()
      }
      ret
    }
  }

  implicit def augmentAxi4(axi: Axi4): RichAxi4 = new RichAxi4(axi)

  // convert a spinal lib Axi4StreamConfig to verilog-axis format
  def mapToIntf(config: Axi4StreamConfig): Axi4StreamConfig =
    config.copy(
      useId = true,
      idWidth = if (config.useId) config.idWidth else 1,
      useDest = true,
      destWidth = if (config.useDest) config.destWidth else 1,
      useUser = true,
      userWidth = if (config.useUser) config.userWidth else 1,
    )

  class RichAxi4Stream(axis: Axi4Stream) {
    def toSpinal(config: Axi4StreamConfig): Axi4Stream = {
      val ret = Axi4Stream(config)
      if (axis.isMasterInterface) {
        ret.arbitrationFrom(axis)
        ret.payload.assignSomeByName(axis.payload)
      } else {
        axis.arbitrationFrom(ret)
        axis.payload.assignSomeByName(ret.payload)
        axis.payload.assignDontCareToUnasigned()
      }
      ret
    }
  }

  implicit def augmentAxi4Stream(axis: Axi4Stream): RichAxi4Stream = new RichAxi4Stream(axis)

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
