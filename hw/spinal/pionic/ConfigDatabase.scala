package pionic

import spinal.core.SpinalConfig
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.reflect.runtime.universe._
import scala.reflect.runtime.{currentMirror => cm}

// FIXME: use the more holistic Database approach
class ConfigDatabase extends FiberPlugin {
  private val configs = mutable.HashMap[String, (Any, TypeTag[_], Boolean)]()

  // add default values
  postConfig("pkt buf addr width", 24)
  postConfig("pkt buf len width", 16)
  postConfig("pkt buf size per core", 64 * 1024)
  postConfig("pkt buf alloc size map", Seq(
    (128, .1),
    (1518, .3), // max Ethernet frame with MTU 1500
    (9618, .6), // max jumbo frame
  ), emitHeader = false)
  postConfig("max rx pkts in flight", 128)
  postConfig("num cores", 4)
  postConfig("collect timestamps", true, emitHeader = false)
  postConfig("timestamp width", 32)
  postConfig("reg width", 64)
  postConfig("git version", BigInt(0xffffffffL), emitHeader = false)
  postConfig("bypass header max width", 54 * 8) // ETH + IP + TCP

  def postConfig[T: TypeTag](name: String, value: T, update: Boolean = false, emitHeader: Boolean = true): Unit = {
    if (!update) {
      assert(!configs.contains(name), {
        val (v, tag, _) = configs(name)
        s"key \"$name\" previously defined with value $v (type ${tag.tpe})"
      })
    } else {
      // check type is the same
      val (_, tag, _) = configs(name)
      assert(configs.contains(name), s"key \"$name\" previously undefined; use with update = false")
      assert(tag.tpe =:= typeTag[T].tpe, s"key \"$name\" type mismatch: stored ${tag.tpe} while trying to override with ${typeTag[T].tpe}")
    }
    configs += name -> (value, typeTag[T], emitHeader)
  }

  def apply[T: TypeTag](name: String): T = {
    // TODO: automatically hook up retainer?
    assert(configs.contains(name), s"key \"$name\" undefined")
    val (v, tag, _) = configs(name)
    assert(tag.tpe =:= typeTag[T].tpe, s"key \"$name\" type mismatch: stored ${tag.tpe} while trying to access as ${typeTag[T].tpe}")
    v.asInstanceOf[T]
  }

  def writeConfigs(outPath: os.Path, spinalConfig: SpinalConfig): Unit = {
    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_CONFIG_H__
          |#define __PIONIC_CONFIG_H__
          |
          |// config generated for git hash ${apply[BigInt]("git version")}%x
          |
          |#define PIONIC_PKT_BUF_ADDR_MASK ((1 << PIONIC_PKT_BUF_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_BUF_LEN_MASK ((1 << PIONIC_PKT_BUF_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${spinalConfig.defaultClockDomainFrequency.getValue.toLong}
          |
            ${
        configs.filter(_._2._3).map { case (k, v) =>
          s"|#define PIONIC_${k.toUpperCase.replace(' ', '_')} (${v._1})"
        }.mkString("\n")
      }
          |
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }
}
