package pionic

import pionic.net.OncRpcCallMetadata
import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutVecToBits

/** A schedule decision issued by [[Scheduler]] to [[CoreControlPlugin]]. */
case class DescWithSched()(implicit c: ConfigDatabase) extends Bundle {
  /** Host-agnostic description, to be translated and sent by [[pionic.CoreControlPlugin]] */
  val desc = OncRpcCallMetadata()
  /** Thread ID to schedule on the core. */
  val tid = Bits(c[Int]("thread id width") bits)
}

case class PidTidMapping()(implicit c: ConfigDatabase) extends Bundle {
  val pid = Bits(c[Int]("process id width") bits)
  val tid = Bits(c[Int]("thread id width") bits)
}

/**
  * Top class of Lauberhorn's thread scheduler, to be instantiated inside [[RxPacketDispatch]].  This is a blackbox --
  * the actual implementation is provided by Adam in VHDL.
  *
  * Takes a [[OncRpcCallMetadata]] from the decoding pipeline and extracts the (PID, funcPtr) pair.  See
  * [[https://unlimited.ethz.ch/spaces/sgnetoswiki/pages/216442761/Lauberhorn+Fast+RPC+on+Enzian#Lauberhorn(FastRPConEnzian)-(FPGA-side)schedulerarchitecture wiki page]]
  * for the detailed architecture.
  *
  * Note: to allow accurate decisions based on queue capacity, the [[Scheduler]] has to be the only component in the
  * system that queues packets -- all existing queuing e.g. inside [[CoreControlPlugin]] needs to be eliminated.
  */
case class Scheduler()(implicit c: ConfigDatabase) extends BlackBox {
  val numCores = c[Int]("num cores")
  val generic = new Generic {
    val NUM_CORES = numCores
    val PID_QUEUE_DEPTH = c[Int]("max rx pkts in flight")
  }

  val clk = in Bool()
  val rst = in Bool()

  /** Packet metadata to accept from the decoding pipeline.  Must be a [[pionic.net.OncRpcCallMetadata]] */
  val rxMeta = slave(Stream(OncRpcCallMetadata()))

  /** Packet metadata with schedule decision issued to the downstream [[CoreControlPlugin]]. */
  val coreMeta = Vec(master(Stream(DescWithSched())), numCores)


  /**
    * Requests to cancel a packet that is being processed.  The [[CoreControlPlugin]] will issue an interrupt
    * to the corresponding core and assert coreMeta(i).ready eventually.  Ignored by the [[CoreControlPlugin]] when
    * the corresponding coreMeta.ready is high.
    */
  val coreCancel = out(Bits(numCores bits))

  // config interfaces
  val createThread = slave(Stream(PidTidMapping()))

  mapCurrentClockDomain(clk, rst)
}

/** Dummy driver to emit interface definitions */
object Scheduler extends App {
  implicit val c = new ConfigDatabase
  c.post("max onc rpc inline bytes", 4 * 12, action = ConfigDatabase.Unique)
  SpinalVhdl(new Component {
    val sched = Scheduler()
    sched.rxMeta.setIdle()
    sched.coreMeta.foreach(_.setBlocked())
    sched.createThread.setIdle()
  })
}