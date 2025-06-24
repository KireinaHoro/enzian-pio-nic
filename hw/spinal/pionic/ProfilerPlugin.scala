package pionic

import spinal.core._
import jsteward.blocks.misc._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO
import Global._

import scala.language.postfixOps

class ProfilerPlugin extends PioNicPlugin {
  setName("")

  def Timestamp = UInt(REG_WIDTH bits)

  /** Packet entered Lauberhorn from the CMAC. */
  val RxCmacEntry = NamedType(Timestamp)
  /** Packet popped from the CDC queue inside [[MacInterfaceService]]. */
  val RxAfterCdcQueue = NamedType(Timestamp)
  /** Packet finished DMA into [[pionic.PacketBuffer]] and on its way to [[Scheduler]] or bypass
    * [[pionic.host.DatapathService]] */
  val RxEnqueueToHost = NamedType(Timestamp)

  // FIXME: per-core timestamps, store separately
  val RxCoreReadStart = NamedType(Timestamp)
  val RxCoreReadFinish = NamedType(Timestamp)
  val RxCoreCommit = NamedType(Timestamp)

  val TxCoreAcquire = NamedType(Timestamp)
  val TxCoreCommit = NamedType(Timestamp)
  val TxAfterDmaRead = NamedType(Timestamp)
  val TxBeforeCdcQueue = NamedType(Timestamp) // time before packet passing through Tx CDC fifo
  val TxCmacExit = NamedType(Timestamp) // time exiting to CMAC

  val logic = during setup new Area {
    val profiler = Profiler(
      RxCmacEntry, RxAfterCdcQueue, RxEnqueueToHost, RxCoreReadStart, RxCoreReadFinish, RxCoreCommit,
      TxCoreAcquire, TxCoreCommit, TxAfterDmaRead, TxBeforeCdcQueue, TxCmacExit
    )(collectTimestamps = true)

    def reportTimestamps(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
      profiler.timestamps.storage.foreach { case (namedType, data) =>
        busCtrl.read(data, alloc("lastProfile", namedType.getName(), attr = RO))
      }
    }
  }

  def profile(keycond: (NamedType[UInt], Bool)*) = {
    implicit val clock = host[GlobalCSRPlugin].logic.get.status.cycles

    logic.profiler.fillSlots(keycond: _*)
  }
}
