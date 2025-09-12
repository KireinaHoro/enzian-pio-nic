package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO
import Global._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.misc.plugin.FiberPlugin

import jsteward.blocks.misc._

import scala.language.postfixOps

class ProfilerPlugin extends FiberPlugin {
  setName("")

  def Timestamp = UInt(REG_WIDTH bits)

  /** Packet entered Lauberhorn from the CMAC. */
  val RxCmacEntry = NamedType(Timestamp)
  /** Packet popped from the CDC queue inside [[MacInterfaceService]]. */
  val RxAfterCdcQueue = NamedType(Timestamp)
  /** Packet finished DMA into [[lauberhorn.PacketBuffer]] and on its way to [[Scheduler]] or bypass
    * [[lauberhorn.host.DatapathService]] */
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
    val cycles = CycleClock(REG_WIDTH bits)
    cycles.bits := CounterFreeRun(REG_WIDTH bits)

    val profiler = Profiler(
      RxCmacEntry, RxAfterCdcQueue, RxEnqueueToHost, RxCoreReadStart, RxCoreReadFinish, RxCoreCommit,
      TxCoreAcquire, TxCoreCommit, TxAfterDmaRead, TxBeforeCdcQueue, TxCmacExit
    )(collectTimestamps = true)

    def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
      val busCtrl = AxiLite4SlaveFactory(bus)

      println(f"Git version: ${GIT_VERSION.get}%x")
      busCtrl.read(B(GIT_VERSION), alloc("gitVersion", attr = RO))

      busCtrl.read(cycles, alloc("cycles", attr = RO))

      profiler.timestamps.storage.foreach { case (namedType, data) =>
        busCtrl.read(data, alloc("lastProfile", namedType.getName(), attr = RO))
      }
    }
  }

  def profile(keycond: (NamedType[UInt], Bool)*) = {
    implicit val clock = logic.cycles

    logic.profiler.fillSlots(keycond: _*)
  }
}
