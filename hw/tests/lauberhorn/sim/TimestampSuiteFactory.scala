package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.RegBlockReadBack
import lauberhorn.{AsSimBusMaster, NicEngine}
import spinal.lib.BytesRicher

trait TimestampSuiteFactory { this: DutSimFunSuite[NicEngine] =>
  def getRxTimestamps[B](master: B, globalBlock: RegBlockReadBack)(implicit asMaster: AsSimBusMaster[B]) = {
    new {
      val entry = asMaster.read(master, globalBlock("lastProfile", "RxCmacEntry"), 8).bytesToBigInt
      val afterRxQueue = asMaster.read(master, globalBlock("lastProfile", "RxAfterCdcQueue"), 8).bytesToBigInt
      val readStart = asMaster.read(master, globalBlock("lastProfile", "RxCoreReadStart"), 8).bytesToBigInt
      val afterRead = asMaster.read(master, globalBlock("lastProfile", "RxCoreReadFinish"), 8).bytesToBigInt
      val enqueueToHost = asMaster.read(master, globalBlock("lastProfile", "RxEnqueueToHost"), 8).bytesToBigInt
      val afterRxCommit = asMaster.read(master, globalBlock("lastProfile", "RxCoreCommit"), 8).bytesToBigInt

      println(s"RxCmacEntry: $entry")
      println(s"RxAfterCdcQueue: $afterRxQueue")
      println(s"RxCoreReadStart: $readStart")
      println(s"RxCoreReadFinish: $afterRead")
      println(s"RxEnqueueToHost: $enqueueToHost")
      println(s"RxCoreCommit: $afterRxCommit")
    }
  }

  def getTxTimestamps[B](master: B, globalBlock: RegBlockReadBack)(implicit asMaster: AsSimBusMaster[B]) = {
    new {
      val acquire = asMaster.read(master, globalBlock("lastProfile", "TxCoreAcquire"), 8).bytesToBigInt
      val afterTxCommit = asMaster.read(master, globalBlock("lastProfile", "TxCoreCommit"), 8).bytesToBigInt
      val afterDmaRead = asMaster.read(master, globalBlock("lastProfile", "TxAfterDmaRead"), 8).bytesToBigInt
      val exit = asMaster.read(master, globalBlock("lastProfile", "TxCmacExit"), 8).bytesToBigInt

      println(s"TxCoreAcquire: $acquire")
      println(s"TxCoreCommit: $afterTxCommit")
      println(s"TxAfterDmaRead: $afterDmaRead")
      println(s"TxCmacExit: $exit")
    }
  }
}
