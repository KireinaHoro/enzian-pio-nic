package pionic.host

import pionic.{PioNicPlugin, ProfilerPlugin}
import spinal.core._

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends PioNicPlugin with DatapathService {
  lazy val p = host[ProfilerPlugin]

  during setup new Area {
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
