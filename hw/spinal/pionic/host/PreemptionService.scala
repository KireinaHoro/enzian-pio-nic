package pionic.host

import pionic.{PID, Scheduler}
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/**
  * Stub to allow different implementations of preemption control.  Takes request from [[Scheduler]] in [[preemptReq]].
  *
  * Preemption control should implement the READY/BUSY protocol, before sending the interrupt -- unless the flag is
  * not honored by the CPU, in which case the process should be killed.
  *
  * The implementation defines what interfaces it needs to talk to the host.
  */
trait PreemptionService extends FiberPlugin {
  /** Driven by [[Scheduler]] to deliver a preemption request.
    *
    * When valid is high, the scheduler guarantees that it had already blocked new requests from entering into the
    * staging buffer (refer to paper).  The preemption control implementation can only assert ready, when the kernel
    * has signaled that the whole sequence is finished (right before returning to user space) -- this might be a
    * successful switch, or the process might be killed by the kernel.
    */
  def preemptReq: Stream[PID]

  during build preemptReq.assertPersistence()
}