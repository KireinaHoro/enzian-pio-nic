package lauberhorn.host

import lauberhorn._
import spinal.core._
import spinal.lib.Stream
import spinal.lib.misc.plugin.FiberPlugin

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends FiberPlugin with DatapathService {
  lazy val p = host[ProfilerPlugin]

  /** datapath interfaces */
  lazy val hostTx = Stream(PacketBufDesc())
  lazy val hostTxAck = Stream(HostReq())
  lazy val hostRx = Stream(HostReq())
  lazy val hostRxAck = Stream(PacketBufDesc())
  lazy val hostRxReq = Bool()

  during build new Area {
    import p._

    profile(
      RxCoreReadStart -> hostRxReq.rise(False),
      RxCoreReadFinish -> hostRx.fire,
      RxCoreCommit -> hostRxAck.fire,

      // FIXME: this not reliable for PCIe since hostTx sits in the same 512B word as other regs
      //        so a read on other regs could also trigger this.
      //        Mitigated by allocating hostTx as read sensitive
      TxCoreAcquire -> hostTx.fire,
      TxCoreCommit -> hostTxAck.fire,
    )
  }
}
