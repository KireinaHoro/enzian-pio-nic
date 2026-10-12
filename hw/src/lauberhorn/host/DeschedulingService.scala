package lauberhorn.host

import lauberhorn.{PID, Scheduler}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Scheduler reservation interface. ECI delivers DESCHED through idle RX and
  * acknowledges a cooperative handoff after the serialized info read's transport
  * cleanup boundary. Idle activation alone sends a scheduling interrupt.
  * PCIe supplies its own implementation and is outside the cooperative ABI.
  */
trait DeschedulingService extends FiberPlugin {
  case class DeschedReq() extends Bundle {
    val pid = PID()
    val outOfIdle = Bool()
    val kill = Bool()
  }

  /** The scheduler fences packet delivery before asserting valid. ECI asserts
    * ready after final transport invalidation/completion and owner installation,
    * before the kernel claims a thread and returns its activation snapshot.
    */
  def deschedReq: Stream[DeschedReq]

  during build deschedReq.assertPersistence()
}
