package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core._
import spinal.core.fiber.Handle
import spinal.lib.misc.database.Database.blocking
import spinal.lib.misc.database.{Database, Element}
import spinal.lib.misc.database.Element.toValue

import scala.collection.mutable

object Global extends AreaRoot {
  val NUM_CORES = blocking[Int]
  val NUM_WORKER_CORES = blocking[Int]
  val NUM_SERVICES = blocking[Int]
  val NUM_PROCS = blocking[Int]
  val RX_PKTS_PER_PROC = blocking[Int]

  val DATAPATH_WIDTH = blocking[Int]
  val REG_WIDTH = blocking[Int]
  val PID_WIDTH = blocking[Int]
  val PKT_BUF_ADDR_WIDTH = blocking[Int]
  val PKT_BUF_LEN_WIDTH = blocking[Int]
  val PKT_BUF_ID_WIDTH = blocking[Int]

  val HOST_REQ_TY_WIDTH = blocking[Int]
  val HOST_REQ_WIDTH = blocking[Int]
  val PKT_DESC_TY_WIDTH = blocking[Int]
  val BYPASS_HDR_WIDTH = blocking[Int]
  val ONCRPC_INLINE_BYTES = blocking[Int]

  val GIT_VERSION = blocking[BigInt]

  val PKT_BUF_RX_SIZE_PER_CORE = blocking[Int]
  val PKT_BUF_TX_SIZE_PER_CORE = blocking[Int]
  val PKT_BUF_TX_OFFSET = blocking[Int]
  val PKT_BUF_ALLOC_SIZES = blocking[Seq[(Int, Double)]]

  lazy val PKT_BUF_SIZE = {
    val sz = NUM_CORES * (PKT_BUF_RX_SIZE_PER_CORE + PKT_BUF_TX_SIZE_PER_CORE)
    assert(log2Up(sz) <= PKT_BUF_ADDR_WIDTH, "not the entire packet buffer is addressable!")
    sz
  }

  val MTU = blocking[Int]
  val ROUNDED_MTU = blocking[Int]

  private val customConfigs = mutable.HashMap[String, Int]()
  def postConfig(key: String, value: Int): Unit = customConfigs.put(key, value)

  def writeConfigs(outPath: os.Path, spinalConfig: SpinalConfig): Unit = {
    val vals = Database.storage.collect {
      // ElementBlocking would store a Handle
      case (_, h: Handle[_]) if h.get.isInstanceOf[Int] =>
        h.getName -> h.get
    }

    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_CONFIG_H__
          |#define __PIONIC_CONFIG_H__
          |
          |// config generated for git hash ${GIT_VERSION.get}%x
          |
          |#define PIONIC_PKT_BUF_ADDR_MASK ((1 << PIONIC_PKT_BUF_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_BUF_LEN_MASK ((1 << PIONIC_PKT_BUF_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${spinalConfig.defaultClockDomainFrequency.getValue.toLong}
          |
            ${
        vals.map { case (k, v) =>
          s"|#define PIONIC_$k ($v)"
        }.mkString("\n")
      }
          |
            ${
        customConfigs.map { case (k, v) =>
          s"|#define PIONIC_${k.toUpperCase.replace(' ', '_')} ($v)"
        }.mkString("\n")
      }
          |
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }

  val ALLOC = blocking[RegAllocatorFactory]
}
