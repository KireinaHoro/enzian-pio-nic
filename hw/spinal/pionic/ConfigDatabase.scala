package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core.SpinalConfig
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.reflect.runtime.universe._

object ConfigDatabase {
  sealed trait PostAction
  object Unique extends PostAction
  object OneShot extends PostAction
  object Override extends PostAction
}

// FIXME: use the more holistic Database approach
class ConfigDatabase extends FiberPlugin {
  private val configs = mutable.HashMap[String, (Any, TypeTag[_], Boolean)]()

  // add default values
  post("pkt buf addr width", 24)
  post("pkt buf len width", 16)
  post("pkt buf size per core", 64 * 1024)
  post("pkt buf alloc size map", Seq(
    (128, .1),
    (1518, .3), // max Ethernet frame with MTU 1500
    (9618, .6), // max jumbo frame
  ), emitHeader = false)
  post("max rx pkts in flight", 128)
  post("num cores", 4)
  post("collect timestamps", true, emitHeader = false)
  post("timestamp width", 32)
  post("reg width", 64)
  post("git version", BigInt(0xffffffffL), emitHeader = false)
  post("bypass header max width", 54 * 8) // ETH + IP + TCP

  import ConfigDatabase._

  def post[T: TypeTag](name: String, value: => T, action: PostAction = Unique, emitHeader: Boolean = true): Unit = {
    action match {
      case Unique =>
        assert(!configs.contains(name), {
          val (v, tag, _) = configs(name)
          s"key \"$name\" previously defined with value $v (type ${tag.tpe})"
        })
      case OneShot =>
        if (configs.contains(name)) {
          // if existing, should be the same value
          val (v, tag, _) = configs(name)
          assert(tag.tpe =:= typeTag[T].tpe, s"key \"$name\" defined with oneshot type ${tag.tpe} but redefined with different type ${typeTag[T].tpe}")
          assert(v == value, s"key \"$name\" defined with oneshot value $v but redefined with different value $value")
          return
        }
      case Override =>
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

  val f = new RegAllocatorFactory
}
