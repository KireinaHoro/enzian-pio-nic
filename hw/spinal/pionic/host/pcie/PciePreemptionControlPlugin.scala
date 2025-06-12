package pionic.host.pcie

import pionic.PID
import pionic.host.PreemptionService

import spinal.lib._
import spinal.core._

class PciePreemptionControlPlugin(val coreID: Int) extends PreemptionService {
  val logic = during build new Area {
    val preemptReq = Stream(PID())
    preemptReq.setBlocked()
  }
  def preemptReq: Stream[PID] = logic.preemptReq
}

