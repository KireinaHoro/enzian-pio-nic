package lauberhorn.host

import lauberhorn._
import spinal.core._
import spinal.lib.Stream
import spinal.lib.misc.plugin.FiberPlugin

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends FiberPlugin with DatapathService {
  require(log2Up(coreID) <= CoreID.width, s"datapath trace CoreID $coreID does not fit in ${CoreID.width} bits")

  /** datapath interfaces */
  lazy val hostTx = Stream(PacketBufDesc())
  lazy val hostTxAck = Stream(HostReq())
  lazy val hostRx = Stream(HostReq())
  lazy val hostRxAck = Stream(PacketBufDesc())
  lazy val hostRxReq = Bool()

  val rxReadStartTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx_read_start", LauberhornTraceDma.NicHostInterfaceSlr)
  val rxReadFinishTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx_read_finish", LauberhornTraceDma.NicHostInterfaceSlr)
  val rxCommitTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx_commit", LauberhornTraceDma.NicHostInterfaceSlr)
  val txAcquireTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx_acquire", LauberhornTraceDma.NicHostInterfaceSlr)
  val txCommitTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx_commit", LauberhornTraceDma.NicHostInterfaceSlr)

  during build new Area {
    // TODO: also assign request/response trace ID
    val td = Seq(CoreID(B(coreID)))

    rxReadStartTp.trace("RxCoreReadStart", td: _*) := hostRxReq.rise(False)
    rxReadFinishTp.trace("RxCoreReadFinish", td: _*) := hostRx.fire
    rxCommitTp.trace("RxCoreCommit", td: _*) := hostRxAck.fire

    // FIXME: this not reliable for PCIe since hostTx sits in the same 512B word as other regs
    //        so a read on other regs could also trigger this.
    //        Mitigated by allocating hostTx as read sensitive
    txAcquireTp.trace("TxCoreAcquire", td: _*) := hostTx.fire
    txCommitTp.trace("TxCoreCommit", td: _*) := hostTxAck.fire
  }
}
