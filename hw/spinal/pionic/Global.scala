package pionic

import spinal.core._
import spinal.lib.misc.database.Database.blocking

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

  val MTU = blocking[Int]
  val ROUNDED_MTU = blocking[Int]

  // TODO: dump global config values into header file
  // TODO: register allocator
}
