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
    // TODO: assign request/response trace ID
    tp.trace("RxCoreReadStart", B(coreID)) := hostRxReq.rise(False)
    tp.trace("RxCoreReadFinish", B(coreID)) := hostRx.fire
    tp.trace("RxCoreCommit", B(coreID)) := hostRxAck.fire

    // FIXME: this not reliable for PCIe since hostTx sits in the same 512B word as other regs
    //        so a read on other regs could also trigger this.
    //        Mitigated by allocating hostTx as read sensitive
    tp.trace("TxCoreAcquire", B(coreID)) := hostTx.fire
    tp.trace("TxCoreCommit", B(coreID)) := hostTxAck.fire
  }
}
