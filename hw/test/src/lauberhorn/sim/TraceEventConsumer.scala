package lauberhorn.sim

import lauberhorn.{LauberhornTraceDma, NicEngine, TracePlugin}
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.sim.FlowMonitor

import java.io.{FileOutputStream, OutputStream}
import java.nio.charset.StandardCharsets
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
      eventDef.dataKeys.zip(eventDef.dataWidths).map { case (key, width) =>
        val value = (payload >> bitOffset) & ((BigInt(1) << width) - 1)
        bitOffset += width
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

  def writePcapng(path: os.Path, cycleNs: Int = 5): Unit = {
    os.makeDir.all(path / os.up)
    val out = new FileOutputStream(path.toString)
    try {
      val traceMap = LauberhornTraceDma.traceMapJson(
        lauberhornTracePorts = tracePlugin.tracePorts.toSeq,
        lauberhornEvents = tracePlugin.traceEvents,
      )
      val sourceLayout = LauberhornTraceDma.SourceLayout(tracePlugin.tracePorts.toSeq)
      val sourceOffset = sourceLayout.appInputs.length + sourceLayout.sysInputs.length
      val writer = new TraceEventPcapngWriter(out)
      writer.writeHeader()
      writer.writePacket(TraceEventPcapngWriter.metadataPacket(traceMap), timestampNs = 0)
      snapshot.zipWithIndex.foreach { case (event, index) =>
        writer.writePacket(
          TraceEventPcapngWriter.samplePacket(
            logicalSample = index,
            physicalSample = index,
            timestamp = event.cycle,
            rawTimestamp = event.cycle,
            source = sourceOffset + event.portIndex,
            payload = event.rawPayload,
          ),
          timestampNs = event.cycle * cycleNs,
        )
      }
    } finally {
      out.close()
    }
  }

  def dump(since: Int = 0): String = eventsSince(since).map { event =>
    val fields =
      if (event.data.isEmpty) ""
      else event.data.map { case (key, value) => s"$key=$value" }.mkString(" ", " ", "")
    s"${event.cycle}:${event.portName}:${event.eventName}$fields"
  }.mkString(", ")
}

object TraceEventConsumer {
  def apply(dut: NicEngine, clockDomain: ClockDomain = null): TraceEventConsumer =
    new TraceEventConsumer(dut, Option(clockDomain).getOrElse(dut.clockDomain))
}

private object TraceEventPcapngWriter {
  val LinkTypeUser0 = 147
  private val MagicMetadata = "LHTM".getBytes(StandardCharsets.US_ASCII)
  private val MagicEvent = "LHTE".getBytes(StandardCharsets.US_ASCII)
  private val Version = 3
  private val KindMetadata = 1
  private val KindSample = 2
  private val SourceMetadata = 0xffff
  private val PayloadBytes = (LauberhornTraceDma.PayloadWidth + 7) / 8

  def metadataPacket(traceMapJson: String): Array[Byte] =
    packet(MagicMetadata, KindMetadata, source = SourceMetadata, payload = traceMapJson.getBytes(StandardCharsets.UTF_8))

  def samplePacket(
                    logicalSample: Int,
                    physicalSample: Int,
                    timestamp: BigInt,
                    rawTimestamp: BigInt,
                    source: Int,
                    payload: BigInt,
                  ): Array[Byte] =
    packet(
      MagicEvent,
      KindSample,
      logicalSample = BigInt(logicalSample),
      physicalSample = BigInt(physicalSample),
      timestamp = timestamp,
      rawTimestamp = rawTimestamp,
      source = source,
      payload = littleEndian(payload, PayloadBytes),
    )

  private def packet(
                      magic: Array[Byte],
                      kind: Int,
                      logicalSample: BigInt = 0,
                      physicalSample: BigInt = 0,
                      timestamp: BigInt = 0,
                      rawTimestamp: BigInt = 0,
                      source: Int = 0,
                      lostCount: Int = 0,
                      payload: Array[Byte] = Array.emptyByteArray,
                    ): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    out.write(magic)
    putU8(out, Version)
    putU8(out, kind)
    putU16(out, 0)
    putU64(out, logicalSample)
    putU64(out, physicalSample)
    putU64(out, timestamp)
    putU64(out, rawTimestamp)
    putU16(out, source)
    putU32(out, lostCount)
    putU32(out, payload.length)
    out.write(payload)
    out.toByteArray
  }

  private def littleEndian(value: BigInt, bytes: Int): Array[Byte] =
    Array.tabulate(bytes)(idx => ((value >> (idx * 8)) & 0xff).toByte)

  def pad4(data: Array[Byte]): Array[Byte] =
    data ++ Array.fill((4 - data.length % 4) % 4)(0.toByte)

  def putU8(out: OutputStream, value: Int): Unit =
    out.write(value & 0xff)

  def putU16(out: OutputStream, value: Int): Unit = {
    out.write(value & 0xff)
    out.write((value >> 8) & 0xff)
  }

  def putU32(out: OutputStream, value: BigInt): Unit =
    (0 until 4).foreach(idx => out.write(((value >> (idx * 8)) & 0xff).toInt))

  def putU64(out: OutputStream, value: BigInt): Unit =
    (0 until 8).foreach(idx => out.write(((value >> (idx * 8)) & 0xff).toInt))

  def option(code: Int, data: Array[Byte]): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    putU16(out, code)
    putU16(out, data.length)
    out.write(pad4(data))
    out.toByteArray
  }
}

private class TraceEventPcapngWriter(output: OutputStream) {
  import TraceEventPcapngWriter._

  def writeHeader(): Unit = {
    val section = new java.io.ByteArrayOutputStream()
    putU32(section, 0x1a2b3c4dL)
    putU16(section, 1)
    putU16(section, 0)
    putU64(section, BigInt(-1L))
    writeBlock(0x0a0d0d0a, section.toByteArray)

    val idb = new java.io.ByteArrayOutputStream()
    putU16(idb, LinkTypeUser0)
    putU16(idb, 0)
    putU32(idb, 65535)
    idb.write(option(2, "lauberhorn-trace-sim".getBytes(StandardCharsets.US_ASCII)))
    idb.write(option(9, Array(9.toByte)))
    putU16(idb, 0)
    putU16(idb, 0)
    writeBlock(1, idb.toByteArray)
  }

  def writePacket(packet: Array[Byte], timestampNs: BigInt, interfaceId: Int = 0): Unit = {
    val body = new java.io.ByteArrayOutputStream()
    putU32(body, interfaceId)
    putU32(body, timestampNs >> 32)
    putU32(body, timestampNs & 0xffffffffL)
    putU32(body, packet.length)
    putU32(body, packet.length)
    body.write(pad4(packet))
    writeBlock(6, body.toByteArray)
  }

  private def writeBlock(blockType: Int, body: Array[Byte]): Unit = {
    val totalLength = 12 + body.length
    putU32(output, blockType)
    putU32(output, totalLength)
    output.write(body)
    putU32(output, totalLength)
  }
}
