package lauberhorn.host

import lauberhorn.{PID, Scheduler}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Scheduler reservation interface. ECI delivers PREEMPT through idle RX and
  * acknowledges a cooperative handoff after the serialized info read's transport
  * cleanup boundary. Idle activation alone sends a scheduling interrupt.
  * PCIe supplies its own implementation and is outside the cooperative ABI.
  */
trait PreemptionService extends FiberPlugin {
  case class PreemptReq() extends Bundle {
    val pid = PID()
    val outOfIdle = Bool()
    val kill = Bool()
  }

  /** The scheduler fences packet delivery before asserting valid. ECI asserts
    * ready after final transport invalidation/completion and owner installation,
    * before the kernel claims a thread and returns its activation snapshot.
    */
  def preemptReq: Stream[PreemptReq]

  during build preemptReq.assertPersistence()
}
