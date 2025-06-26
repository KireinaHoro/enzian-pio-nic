package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core._
import spinal.core.fiber.Handle
import spinal.lib.misc.database.Database.{blocking, value}
import spinal.lib.misc.database.{Database, Element, ElementValue}
import spinal.lib.misc.database.Element.toValue

import scala.collection.mutable

object Global extends AreaRoot {
  val NUM_CORES = value[Int]
  val NUM_WORKER_CORES = value[Int]
  val NUM_SERVICES = value[Int]
  val NUM_PROCS = value[Int]
  val RX_PKTS_PER_PROC = value[Int]

  val DATAPATH_WIDTH = value[Int]
  val REG_WIDTH = value[Int]
  val PID_WIDTH = value[Int]
  val PKT_BUF_ADDR_WIDTH = value[Int]
  val PKT_BUF_LEN_WIDTH = value[Int]
  val PKT_BUF_ID_WIDTH = blocking[Int]

  val HOST_REQ_TY_WIDTH = blocking[Int]
  val HOST_REQ_WIDTH = blocking[Int]
  val PKT_DESC_TY_WIDTH = blocking[Int]
  val BYPASS_HDR_WIDTH = value[Int]
  val ONCRPC_INLINE_BYTES = blocking[Int]

  val GIT_VERSION = value[BigInt]

  val PKT_BUF_RX_SIZE_PER_CORE = value[Int]
  val PKT_BUF_TX_SIZE_PER_CORE = value[Int]
  val PKT_BUF_SIZE = value[Int]

  val PKT_BUF_TX_OFFSET = blocking[Int]
  val PKT_BUF_ALLOC_SIZES = value[Seq[(Int, Double)]]

  val MTU = blocking[Int]
  val ROUNDED_MTU = blocking[Int]

  val ECI_RX_BASE = value[Int]
  val ECI_TX_BASE = value[Int]
  val ECI_OVERFLOW_OFFSET = value[Int]
  val ECI_NUM_OVERFLOW_CL = value[Int]

  def writeConfigs(outPath: os.Path, spinalConfig: SpinalConfig): Unit = {
    val vals = Database.storage.collect {
      // ElementBlocking would store a Handle
      case (_, h: Handle[_]) if h.get.isInstanceOf[Int] =>
        h.getName -> h.get
      case (e: ElementValue[_], v: Int) =>
        e.getName() -> v
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
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }

  val ALLOC = blocking[RegAllocatorFactory]

  def initDatabase(nc: Int, nw: Int) = {
    // set enough parameters to get us rolling
    NUM_CORES.set(nc)
    NUM_WORKER_CORES.set(nw)
    
    val bufSizeMap = Seq(
      128  -> .1,
      1518 -> .3, // max Ethernet frame with MTU 1500
      9618 -> .6, // max jumbo frame
    )
    PKT_BUF_ALLOC_SIZES.set(bufSizeMap)
    PKT_BUF_ADDR_WIDTH.set(24)
    PKT_BUF_LEN_WIDTH.set(16)
    PKT_BUF_RX_SIZE_PER_CORE.set(64 * 1024)
    PKT_BUF_TX_SIZE_PER_CORE.set(1024)
    PKT_BUF_SIZE.set {
      val sz = NUM_CORES * (PKT_BUF_RX_SIZE_PER_CORE + PKT_BUF_TX_SIZE_PER_CORE)
      assert(log2Up(sz) <= PKT_BUF_ADDR_WIDTH, "not the entire packet buffer is addressable!")
      sz
    }

    DATAPATH_WIDTH.set(64)
    MTU.set(bufSizeMap.map(_._1).max)
    ROUNDED_MTU.set(roundUp(MTU.get, DATAPATH_WIDTH.get).toInt)

    REG_WIDTH.set(64)
    PID_WIDTH.set(16)
    NUM_SERVICES.set(256)
    NUM_PROCS.set(32)
    RX_PKTS_PER_PROC.set(32)
    BYPASS_HDR_WIDTH.set(54 * 8) // ETH + IP + TCP

    ALLOC.set(new RegAllocatorFactory)
  }
}
