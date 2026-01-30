package lauberhorn.host.eci

import spinal.core._
import spinal.lib.misc.plugin._
import spinal.lib._
import jsteward.blocks.eci.TracePort
import jsteward.blocks.misc.TraceBuffer

import scala.language.postfixOps

/** Capture traces from DCS interfaces in a buffer and stream them out to ILA
  * on trigger from VIO.  Also records timestamps of each trace event.
  */
class DcsTraceBuffer(numSlots: Int = 1024) extends FiberPlugin {
  val logic = during build new Area {
    val tracePorts = host[EciInterfacePlugin].logic.dcsIntfs.flatMap(_.tracing)
    println(s"DCS trace buffer: ${tracePorts.length} ports, $numSlots buffer entries")

    // add delay to simplify PNR
    val delayedPorts = Vec(tracePorts.map { p =>
      DelayWithInit(p, 3) { dp => dp.valid init False }
    })

    val traceBuf = TraceBuffer(TracePort(), tracePorts.length, numSlots)

    val dump = in(Bool())
    val data = out(traceBuf.CapturedEvent())
    val sampleLost = out(Bool())

    traceBuf.dump := dump
    traceBuf.traceIn := delayedPorts
    data := traceBuf.traceOut
    sampleLost := traceBuf.sampleLost
  }.setName("trace")
}
