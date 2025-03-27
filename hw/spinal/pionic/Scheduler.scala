package pionic

import pionic.net.OncRpcCallMetadata
import spinal.core._
import spinal.lib._

/** Type for PIDs. */
case class PID()(implicit c: ConfigDatabase) extends Bundle {
  override def clone = PID()

  val bits = UInt(Widths.pidw bits)
}

/** Command from host to create a process in the scheduler. */
case class CreateProcCmd()(implicit c: ConfigDatabase) extends Bundle {
  /** PID of process on the CPU -- not necessarily corresponding to actual process IDs on Linux */
  val pid = PID()
  /** maximum number of cores that the process is allowed to run on */
  val maxCores = Bits(log2Up(c[Int]("num cores")) bits)
}

/**
  * Top class of Lauberhorn's scheduler, to be instantiated inside [[RxPacketDispatch]].  This is a blackbox --
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
    val PID_WIDTH = Widths.pidw
  }

  val clk = in Bool()
  val rst = in Bool()

  /** Packet metadata to accept from the decoding pipeline.  Must be a [[pionic.net.OncRpcCallMetadata]] */
  val rxMeta = slave(Stream(OncRpcCallMetadata()))

  /** Packet metadata issued to the downstream [[CoreControlPlugin]].  Note that this does not contain any scheduling
    * information -- switching processes on a core is requested through the [[corePreempt]] interfaces. */
  val coreMeta = Vec(master(Stream(OncRpcCallMetadata())), numCores)

  /**
    * Request a core to switch to a different process.  Interaction with [[coreMeta]] happens in the following order:
    *  - hold requests in [[coreMeta]] (valid === False)
    *  - issue request on [[corePreempt]] (wait until ready && valid === True)
    *  - re-assign PID queue, allow requests to continue on [[coreMeta]]
    *
    * Will be stalled (ready === False) when a preemption is in progress.
    */
  val corePreempt = Vec(master(Stream(PID())), numCores)

  /** Create a process.
    *
    * Note that all requests that make it to the scheduler should have a process created
    * -- otherwise they should've been filtered out by [[pionic.net.OncRpcCallDecoder]].  The SW should keep
    * these states consistent.
    */
  val createProcess = slave(Stream(CreateProcCmd()))

  /** Destroy a process due to either it exiting on the host, or being killed by [[CoreControlPlugin]] due to a timeout
    * in the CL critical region.
    *
    * This interface should be driven from the CPU in kernel space -- SW should also keep the decoder state consistent
    * with this.
    */
  val destroyProcess = slave(Stream(PID()))

  mapCurrentClockDomain(clk, rst)
}

/** Dummy driver to emit interface definitions */
object Scheduler extends App {
  implicit val c = new ConfigDatabase
  c.post("max onc rpc inline bytes", 4 * 12, action = ConfigDatabase.Unique)

  case class SchedTmpl() extends Component {
    val sched = Scheduler()
    sched.rxMeta.setIdle()
    sched.coreMeta.foreach(_.setBlocked())
    sched.createProcess.setIdle()
    sched.destroyProcess.setIdle()
    sched.corePreempt.foreach(_.setBlocked())
  }

  SpinalVhdl(SpinalConfig(targetDirectory = "hw/vhdl"))(SchedTmpl())
}