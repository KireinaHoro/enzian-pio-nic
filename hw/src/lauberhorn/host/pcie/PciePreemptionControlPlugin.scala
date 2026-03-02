package lauberhorn.host.pcie

import lauberhorn.PID
import lauberhorn.host.PreemptionService

import spinal.lib._
import spinal.core._

class PciePreemptionControlPlugin(val coreID: Int) extends PreemptionService {
  def preemptReq = ???
}

