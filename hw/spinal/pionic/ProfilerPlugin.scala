package pionic

import spinal.core._
import jsteward.blocks.misc._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO

class ProfilerPlugin extends PioNicPlugin {
  setName("")

  def Timestamp = UInt(c[Int]("timestamp width") bits)

  val RxCmacEntry = NamedType(Timestamp)
  val RxAfterCdcQueue = NamedType(Timestamp)
  val RxEnqueueToHost = NamedType(Timestamp)
  val RxCoreReadStart = NamedType(Timestamp)
  val RxCoreReadFinish = NamedType(Timestamp)
  val RxCoreCommit = NamedType(Timestamp)

  val TxCoreAcquire = NamedType(Timestamp)
  val TxCoreCommit = NamedType(Timestamp)
  val TxAfterDmaRead = NamedType(Timestamp)
  val TxBeforeCdcQueue = NamedType(Timestamp) // time before packet passing through Tx CDC fifo
  val TxCmacExit = NamedType(Timestamp) // time exiting to CMAC

  val logic = during setup new Area {
    val profiler = Profiler(RxCmacEntry, RxAfterCdcQueue, RxEnqueueToHost, RxCoreReadStart, RxCoreReadFinish, RxCoreCommit,
      TxCoreAcquire, TxCoreCommit, TxAfterDmaRead, TxBeforeCdcQueue, TxCmacExit)(c[Boolean]("collect timestamps"))

    def reportTimestamps(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      if (c[Boolean]("collect timestamps")) {
        profiler.timestamps.storage.foreach { case (namedType, data) =>
          busCtrl.read(data, alloc("lastProfile", namedType.getName(), attr = RO))
        }
      }
    }
  }

  def profile(keycond: (NamedType[UInt], Bool)*) = {
    implicit val clock = host[GlobalCSRPlugin].logic.get.status.cycles

    logic.profiler.fillSlots(keycond: _*)
  }
}
