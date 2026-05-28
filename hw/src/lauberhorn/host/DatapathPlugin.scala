package lauberhorn.host

import lauberhorn._
import spinal.core._
import spinal.lib.Stream
import spinal.lib.misc.plugin.FiberPlugin

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends FiberPlugin with DatapathService {
  /** datapath interfaces */
  lazy val hostTx = Stream(PacketBufDesc())
  lazy val hostTxAck = Stream(HostReq())
  lazy val hostRx = Stream(HostReq())
  lazy val hostRxAck = Stream(PacketBufDesc())
  lazy val hostRxReq = Bool()

  // every core only needs one trace port
  val tp = during setup host[TracePlugin].makePort(LauberhornTraceDma.NicHostInterfaceSlr)

  during build new Area {
    // TODO: also assign request/response trace ID
    val td = Seq(CoreID(B(coreID)))

    tp.trace("RxCoreReadStart", td: _*) := hostRxReq.rise(False)
    tp.trace("RxCoreReadFinish", td: _*) := hostRx.fire
    tp.trace("RxCoreCommit", td: _*) := hostRxAck.fire

    // FIXME: this not reliable for PCIe since hostTx sits in the same 512B word as other regs
    //        so a read on other regs could also trigger this.
    //        Mitigated by allocating hostTx as read sensitive
    tp.trace("TxCoreAcquire", td: _*) := hostTx.fire
    tp.trace("TxCoreCommit", td: _*) := hostTxAck.fire
  }
}
