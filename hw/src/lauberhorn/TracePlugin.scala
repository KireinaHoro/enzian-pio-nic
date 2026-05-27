package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

class TracePlugin extends FiberPlugin {
  setName("")

  case class TraceEvent(id: Int, name: String)

  /** Packet entered Lauberhorn from the CMAC. */
  val RxCmacEntry = TraceEvent(0, "RxCmacEntry")
  /** Packet popped from the CDC queue inside [[MacInterfaceService]]. */
  val RxAfterCdcQueue = TraceEvent(1, "RxAfterCdcQueue")
  /** Packet finished DMA into [[lauberhorn.PacketBuffer]] and on its way to [[Scheduler]] or bypass
    * [[lauberhorn.host.DatapathService]] */
  val RxEnqueueToHost = TraceEvent(2, "RxEnqueueToHost")

  val RxCoreReadStart = TraceEvent(3, "RxCoreReadStart")
  val RxCoreReadFinish = TraceEvent(4, "RxCoreReadFinish")
  val RxCoreCommit = TraceEvent(5, "RxCoreCommit")

  val TxCoreAcquire = TraceEvent(6, "TxCoreAcquire")
  val TxCoreCommit = TraceEvent(7, "TxCoreCommit")
  val TxAfterDmaRead = TraceEvent(8, "TxAfterDmaRead")
  val TxBeforeCdcQueue = TraceEvent(9, "TxBeforeCdcQueue") // time before packet passing through Tx CDC fifo
  val TxCmacExit = TraceEvent(10, "TxCmacExit") // time exiting to CMAC

  val logic = during setup new Area {
    val traceValid = Bool().setName("lauberhorn_trace_valid").asOutput()
    val tracePayload = Bits(LauberhornTraceDma.PayloadWidth bits).setName("lauberhorn_trace_payload").asOutput()

    traceValid := False
    tracePayload := 0
  }

  private def payload(event: TraceEvent, coreId: Int): Bits = {
    val encoded = (BigInt(coreId & 0xf) << 8) | BigInt(event.id & 0xff)
    B(encoded, LauberhornTraceDma.PayloadWidth bits)
  }

  def trace(keycond: (TraceEvent, Bool)*): Unit =
    traceCore(0, keycond: _*)

  def traceCore(coreId: Int, keycond: (TraceEvent, Bool)*): Unit = {
    keycond.foreach { case (event, cond) =>
      // FIXME: this will collide when multiple trace points fire at the same time;
      //        use a bitmap to capture all trace events to fit inside a trace sample;
      //        this way we can handle up to 64 trace points, without worrying about collision.
      //        limit the per-core trace events as they will be times #numCores
      when(cond) {
        logic.traceValid := True
        logic.tracePayload := payload(event, coreId)
      }
    }
  }
}
