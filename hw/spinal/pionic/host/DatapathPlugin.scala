package pionic.host

import pionic._
import spinal.core._
import spinal.lib.Stream
import spinal.lib.misc.plugin.FiberPlugin

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends FiberPlugin with DatapathService {
  lazy val p = host[ProfilerPlugin]

  /** datapath interfaces */
  val hostTx = Stream(PacketBufDesc())
  val hostTxAck = Stream(HostReq())
  val hostRx = Stream(HostReq())
  val hostRxAck = Stream(PacketBufDesc())
  val hostRxReq = Bool()

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
