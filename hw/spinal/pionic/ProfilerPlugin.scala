package pionic

import spinal.core._
import spinal.lib.misc.plugin._
import jsteward.blocks.misc._
import spinal.lib.bus.misc.BusSlaveFactory

class ProfilerPlugin(implicit config: PioNicConfig) extends FiberPlugin {
  setName("")
  def Timestamp(implicit config: PioNicConfig) = UInt(config.timestampWidth bits)

  val RxCmacEntry = NamedType(Timestamp)
  val RxAfterCdcQueue = NamedType(Timestamp)
  val RxAfterDmaWrite = NamedType(Timestamp)
  val RxCoreReadStart = NamedType(Timestamp)
  val RxCoreReadFinish = NamedType(Timestamp)
  val RxCoreCommit = NamedType(Timestamp)

  val TxCoreAcquire = NamedType(Timestamp)
  val TxCoreCommit = NamedType(Timestamp)
  val TxAfterDmaRead = NamedType(Timestamp)
  val TxBeforeCdcQueue = NamedType(Timestamp) // time before packet passing through Tx CDC fifo
  val TxCmacExit = NamedType(Timestamp) // time exiting to CMAC

  val logic = during setup new Area {
    val profiler = Profiler(RxCmacEntry, RxAfterCdcQueue, RxAfterDmaWrite, RxCoreReadStart, RxCoreReadFinish, RxCoreCommit,
      TxCoreAcquire, TxCoreCommit, TxAfterDmaRead, TxBeforeCdcQueue, TxCmacExit)(config.collectTimestamps)

    def reportTimestamps(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit = {
      if (config.collectTimestamps) {
        profiler.timestamps.storage.foreach { case (namedType, data) =>
          busCtrl.read(data, alloc("lastProfile", namedType.getName()))
        }
      }
    }
  }

  def profile(keycond: (NamedType[UInt], Bool)*) = {
    implicit val clock = host[GlobalCSRPlugin].logic.get.status.cycles

    logic.profiler.fillSlots(keycond: _*)
  }
}
