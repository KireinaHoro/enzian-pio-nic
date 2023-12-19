import axi.Axi4StreamCustom.Axi4StreamCustom
import spinal.core._
import spinal.lib
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis.{Axi4Stream, Axi4StreamConfig}
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

import scala.collection.mutable

package object axi {
  def renameAxi4IO: Unit = {
    Component.current.getAllIo.foreach { bt =>
      val pattern = "^([sm]_axi.*)(aw|w|b|ar|r)_(?:payload_)?([^_]+)$".r
      for (pm <- pattern.findFirstMatchIn(bt.getName)) {
        bt.setName(pm.group(1) + pm.group(2) + pm.group(3))
      }
    }
  }

  def renameAxi4StreamIO: Unit = {
    Component.current.getAllIo.foreach { bt =>
      val pattern = "^([sm]_axis.*?)(?:payload_)*([^_]+)$".r
      for (pm <- pattern.findFirstMatchIn(bt.getName)) {
        val busName = pm.group(1)
        val signalName = if (busName.endsWith("data_")) s"t${pm.group(2)}" else pm.group(2)
        bt.setName(busName + signalName)
      }
    }
  }

  def axiRTLFile(name: String) = s"hw/deps/verilog-axi/rtl/$name.v"
  def axisRTLFile(name: String) = s"hw/deps/verilog-axis/rtl/$name.v"

  implicit class RichBundle(b: Bundle) {
    def <<?(that: Bundle): Unit = {
      b.assignSomeByName(that)
      b.assignDontCareToUnasigned()
    }

    def >>?(that: Bundle): Unit = {
      that <<? b
    }
  }

  implicit class RichAxi4(axi: Axi4) {
    def resize(newWidth: Int): Axi4 = {
      val adapter = new AxiAdapter(axi.config, newWidth)
      axi >> adapter.getSlave
      adapter.getMaster
    }

    def toSpinal(config: Axi4Config): Axi4 = {
      val ret = Axi4(config).setCompositeName(axi, "toSpinal", true)
      val masterChannels: Seq[Axi4 => lib.Stream[_ <: Bundle]] = Seq(_.ar, _.aw, _.w)
      val slaveChannels: Seq[Axi4 => lib.Stream[_ <: Bundle]] = Seq(_.r, _.b)
      val driverChannels = if (axi.isMasterInterface) masterChannels else slaveChannels
      val loadChannels = if (axi.isMasterInterface) slaveChannels else masterChannels
      driverChannels.foreach { c =>
        c(ret).translateFrom(c(axi))(_ <<? _)
      }
      loadChannels.foreach { c =>
        c(axi).translateFrom(c(ret))(_ <<? _)
      }
      ret
    }
  }

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

  implicit class RichAxi4Stream(axis: Axi4Stream) {
    def toSpinal(config: Axi4StreamConfig): Axi4Stream = {
      val ret = Axi4Stream(config).setCompositeName(axis, "toSpinal", true)
      if (axis.isMasterInterface) {
        ret.translateFrom(axis)(_ <<? _)
      } else {
        axis.translateFrom(ret)(_ <<? _)
      }
      ret
    }

    def length: Flow[UInt] = {
      val monitor = AxiStreamFrameLen(axis.config)
      monitor.driveFrom(axis)
      monitor.io.frame_len
    }
  }

  implicit class RichAxi4StreamCustom[T <: Data](axis: Axi4StreamCustom[T]) {
    def toSpinal(config: Axi4StreamCustomConfig[T]): Axi4StreamCustom[T] = {
      val ret = Axi4StreamCustom(config).setCompositeName(axis, "toSpinal", true)
      if (axis.isMasterInterface) {
        ret.translateFrom(axis)(_ <<? _)
      } else {
        axis.translateFrom(ret)(_ <<? _)
      }
      ret
    }
  }

  // remove once https://github.com/SpinalHDL/SpinalHDL/commit/68b6158700fc2440ea7980406f927262c004faca is into stable
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
