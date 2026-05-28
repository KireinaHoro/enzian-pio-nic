package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.language.postfixOps

sealed abstract class TraceDataKey {
  def name: String = getClass.getSimpleName.stripSuffix("$")
  def width: Int
  def apply[T <: Data](data: T): TraceData = TraceData(this, data.asBits.resize(width))
}
case class TraceData(ty: TraceDataKey, data: Bits)
case object PacketDropped extends TraceDataKey { def width = 1 }
case object PacketID      extends TraceDataKey { def width = 8 }
case object ReqID         extends TraceDataKey { def width = 8 }
case object RespID        extends TraceDataKey { def width = 8 }
case object ThreadID      extends TraceDataKey { def width = 8 }
case object CoreID        extends TraceDataKey { def width = 3 }

case class TraceEvent(name: String, dataKeys: Seq[TraceDataKey])

class TracePlugin extends FiberPlugin {
  private val eventBuffer = mutable.ArrayBuffer[TraceEvent]()
  def traceEvents: Seq[TraceEvent] = eventBuffer.toSeq
  def eventNames: Seq[String] = traceEvents.map(_.name)

  private def eventSignature(name: String, dataKeys: Seq[TraceDataKey]): (String, Seq[String]) =
    (name, dataKeys.map(_.name))

  def allocEventID(name: String, dataKeys: Seq[TraceDataKey]): Int = {
    val signature = eventSignature(name, dataKeys)
    eventBuffer.indexWhere(event => eventSignature(event.name, event.dataKeys) == signature) match {
      case existing if existing >= 0 => existing
      case _ =>
        require(!eventBuffer.exists(_.name == name),
          s"trace event $name was already declared with a different trace-data layout")
        require(eventBuffer.length < (1 << LauberhornTraceDma.EventIdSlotWidth),
          s"too many trace events for ${LauberhornTraceDma.EventIdSlotWidth}-bit event IDs")
        val ret = eventBuffer.length
        eventBuffer.append(TraceEvent(name, dataKeys))
        ret
    }
  }

  class TracePort(val sourceSlr: Int) {
    val pipelineStages: Int = LauberhornTraceDma.pipelineStagesToTraceBufferDma(sourceSlr)

    /** Trace a given event.  On every cycle this port can emit at most
     * one event; if multiple `trace` return values have been assigned to True,
     * only the last one will survive due to the last when statement having priority.
     *
     * @param name      Name of the event
     * @param td        Structured data to embed into the emitted trace frame
     * @return Trigger condition for emitting the event; assign to this
     */
    def trace(name: String, td: TraceData*): Bool = {
      val myID = allocEventID(name, td.map(_.ty))
      val cond = Bool()
      when (cond) {
        out.valid := True

        // data will be padded with zero after resize
        val allData = td.reverse.map(_.data).foldLeft(B(0, 0 bits))(_ ## _)
        out.payload := (allData ## B(myID, LauberhornTraceDma.EventIdSlotWidth bits)).resized
      }
      cond
    }

    val out = Flow(Bits(LauberhornTraceDma.PayloadWidth bits))
    out.valid := False
    out.payload.assignDontCare()
  }

  def makePort(sourceSlr: Int = LauberhornTraceDma.TraceBufferDmaSlr): TracePort = {
    val ret = new TracePort(sourceSlr)
    tracePorts.append(ret)
    ret
  }
  val tracePorts = mutable.ArrayBuffer[TracePort]()
  def tracePortCount: Int = tracePorts.length
  def tracePipelineStages: Seq[Int] = tracePorts.map(_.pipelineStages).toSeq

  val logic = during build new Area {
    val trace = Vec(master(Flow(Bits(LauberhornTraceDma.PayloadWidth bits))), tracePorts.length)
    trace zip tracePorts foreach { case (to, tp) => to := tp.out }
  }
}
