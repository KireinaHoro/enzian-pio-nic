package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.language.postfixOps

class TracePlugin extends FiberPlugin {
  var nextEventID = 0
  val eventNames = mutable.ArrayBuffer[String]()

  def allocEventID(name: String): Int = {
    val ret = nextEventID
    require(ret < (1 << LauberhornTraceDma.EventIdSlotWidth), s"too many trace events for ${LauberhornTraceDma.EventIdSlotWidth}-bit event IDs")
    nextEventID += 1
    eventNames.append(name)
    ret
  }

  class TracePort(val sourceSlr: Int) {
    val pipelineStages: Int = LauberhornTraceDma.pipelineStagesToTraceBufferDma(sourceSlr)

    /** Trace a given event.  On every cycle this port can emit at most
     * one event; if multiple `trace` return values have been assigned to True,
     * only the last one will survive due to the last when statement having priority.
     *
     * @param name      Name of the event
     * @param extraData Extra data to embed into the emitted trace frame
     * @return Trigger condition for emitting the event; assign to this
     */
    def trace(name: String, extraData: Bits = B(0)): Bool = {
      val myID = allocEventID(name)
      val cond = Bool()
      when (cond) {
        out.valid := True

        // extraData will be padded with zero after resize
        out.payload := (extraData ## B(myID, LauberhornTraceDma.EventIdSlotWidth bits)).resized
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
