package lauberhorn.sim

import lauberhorn.{LauberhornTraceDma, NicEngine, TracePlugin}
import spinal.core.ClockDomain
import spinal.core.sim._

import scala.collection.mutable

case class CapturedTraceEvent(
                               cycle: BigInt,
                               portIndex: Int,
                               portName: String,
                               eventId: Int,
                               eventName: String,
                               data: Map[String, BigInt],
                             )

class TraceEventConsumer private (dut: NicEngine, clockDomain: ClockDomain) {
  private val tracePlugin = dut.host[TracePlugin]
  private val eventDefs = tracePlugin.traceEvents.toVector
  private val portNames = tracePlugin.tracePorts.map(_.name).toVector
  private val entries = mutable.ArrayBuffer[CapturedTraceEvent]()
  private var cycle: BigInt = 0

  clockDomain.onSamplings {
    cycle += 1

    tracePlugin.logic.trace.zipWithIndex.foreach { case (trace, portIndex) =>
      if (trace.valid.toBoolean) {
        val payload = trace.payload.toBigInt
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

        entries.synchronized {
          entries += CapturedTraceEvent(
            cycle = cycle,
            portIndex = portIndex,
            portName = portNames.lift(portIndex).getOrElse(s"trace_$portIndex"),
            eventId = eventId,
            eventName = event.map(_.name).getOrElse(s"<unknown:$eventId>"),
            data = data,
          )
        }
      }
    }
  }

  def currentCycle: BigInt = cycle
  def cursor: Int = entries.synchronized(entries.length)
  def snapshot: Vector[CapturedTraceEvent] = entries.synchronized(entries.toVector)

  def eventsSince(cursor: Int): Vector[CapturedTraceEvent] =
    entries.synchronized(entries.drop(cursor).toVector)

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
