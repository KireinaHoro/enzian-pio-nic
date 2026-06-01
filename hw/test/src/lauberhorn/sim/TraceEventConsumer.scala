package lauberhorn.sim

import lauberhorn.{LauberhornTraceDma, NicEngine, TracePlugin}
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.sim.FlowMonitor

import scala.collection.mutable

case class CapturedTraceEvent(
                               cycle: BigInt,
                               portIndex: Int,
                               portName: String,
                               eventId: Int,
                               eventName: String,
                               data: Map[String, BigInt],
                               rawPayload: BigInt,
                             )

class TraceEventConsumer private (dut: NicEngine, clockDomain: ClockDomain) {
  private val tracePlugin = dut.host[TracePlugin]
  private val eventDefs = tracePlugin.traceEvents.toVector
  private val portNames = tracePlugin.tracePorts.map(_.name).toVector
  private val entries = mutable.ArrayBuffer[CapturedTraceEvent]()
  private val currentCycleEvents = mutable.ArrayBuffer[CapturedTraceEvent]()
  private var cycle: BigInt = 0

  private def formatData(data: Map[String, BigInt]): String =
    if (data.isEmpty) ""
    else data.toSeq.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString(" ", " ", "")

  private def formatEvent(event: CapturedTraceEvent): String =
    s"${event.portIndex}:${event.portName}:${event.eventName}#${event.eventId}${formatData(event.data)} raw=0x${event.rawPayload.toString(16)}"

  private def decodeEvent(portIndex: Int, payload: BigInt): CapturedTraceEvent = {
    val eventId = (payload & ((BigInt(1) << LauberhornTraceDma.EventIdSlotWidth) - 1)).toInt
    val event = eventDefs.lift(eventId)
    var bitOffset = LauberhornTraceDma.EventIdSlotWidth
    val data = event.map { eventDef =>
      eventDef.dataKeys.map { key =>
        val value = (payload >> bitOffset) & ((BigInt(1) << key.width) - 1)
        bitOffset += key.width
        key.name -> value
      }.toMap
    }.getOrElse(Map.empty[String, BigInt])

    CapturedTraceEvent(
      cycle = cycle,
      portIndex = portIndex,
      portName = portNames.lift(portIndex).getOrElse(s"trace_$portIndex"),
      eventId = eventId,
      eventName = event.map(_.name).getOrElse(s"<unknown:$eventId>"),
      data = data,
      rawPayload = payload,
    )
  }

  clockDomain.onSamplings {
    cycle += 1
    currentCycleEvents.clear()
  }

  tracePlugin.logic.trace.zipWithIndex.foreach { case (trace, portIndex) =>
    FlowMonitor(trace, clockDomain) { payload =>
      currentCycleEvents += decodeEvent(portIndex, payload.toBigInt)
    }
  }

  clockDomain.onSamplings {
    if (currentCycleEvents.nonEmpty) {
      entries ++= currentCycleEvents

      println(s"[trace cycle=$cycle] ${currentCycleEvents.map(formatEvent).mkString("; ")}")

      val samePortEvents = currentCycleEvents.groupBy(_.portIndex).collect {
        case (_, events) if events.lengthCompare(1) > 0 => events
      }
      samePortEvents.foreach { events =>
        println(s"[trace warning] multiple events captured on one trace port in cycle $cycle: ${events.map(formatEvent).mkString("; ")}")
      }
    }
  }

  def currentCycle: BigInt = cycle
  def cursor: Int = entries.length
  def snapshot: Vector[CapturedTraceEvent] = entries.toVector

  def eventsSince(cursor: Int): Vector[CapturedTraceEvent] =
    entries.drop(cursor).toVector

  def describeEventsSince(cursor: Int, limit: Int = 200): String = {
    val events = eventsSince(cursor)
    val shown = events.takeRight(limit)
    val prefix = if (events.length > shown.length) s"... ${events.length - shown.length} older events omitted, " else ""
    prefix + shown.map(event => s"${event.cycle}:${formatEvent(event)}").mkString("[", ", ", "]")
  }

  def latest(eventName: String, data: Map[String, BigInt] = Map.empty, since: Int = 0): Option[CapturedTraceEvent] =
    eventsSince(since).reverse.find { event =>
      event.eventName == eventName && data.forall { case (key, value) => event.data.get(key).contains(value) }
    }

  def first(eventName: String, data: Map[String, BigInt] = Map.empty, since: Int = 0): Option[CapturedTraceEvent] =
    eventsSince(since).find { event =>
      event.eventName == eventName && data.forall { case (key, value) => event.data.get(key).contains(value) }
    }
}

object TraceEventConsumer {
  def apply(dut: NicEngine, clockDomain: ClockDomain = null): TraceEventConsumer =
    new TraceEventConsumer(dut, Option(clockDomain).getOrElse(dut.clockDomain))
}
