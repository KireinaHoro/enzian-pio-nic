package lauberhorn

import jsteward.blocks.misc.TraceBufferDMA
import jsteward.blocks.axi.SimpleAsyncFifo
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import scala.language.postfixOps

case class LauberhornTraceDma(
                               payloadWidth: Int = 128,
                               appSources: Int = 16,
                               sysSources: Int = 12,
                               sysCdcFifoDepth: Int = 64,
                               axiBufferBase: BigInt = 0,
                               axiBufferSize: BigInt = BigInt("400000000", 16),
                             ) extends Component {
  // 128-bit payload format:
  // [127:124] version
  // [123:120] kind (0 = DCS event, 1 = ECI frame, later values are reserved for Lauberhorn profiler traces)
  // [119:112] local source within the kind-specific producer
  // [111:0]   kind-specific payload
  //
  // TraceBufferDMA wraps this with the global source index, timestamp, and lost-frame marker.
  //
  // appTraceIn is sampled in the app clock domain. Current source allocation:
  //   0..3   DCS event traces
  //   4..9   even-DCS ECI frames after crossing into app
  //   10..15 odd-DCS ECI frames after crossing into app
  //
  // sysTraceIn is sampled in the system clock domain and crossed into app here.
  // Current source allocation:
  //   0..5   even-DCS ECI frames before app CDC
  //   6..11  odd-DCS ECI frames before app CDC
  val totalSources = appSources + sysSources
  val axiConfig = Axi4Config(
    addressWidth = 34,
    dataWidth = 512,
    idWidth = 7,
    useQos = false,
    useRegion = false,
  )

  val sys_clk = in Bool()
  val sys_reset = in Bool()
  val appTraceIn = Vec(slave(Flow(Bits(payloadWidth bits))), appSources)
  val sysTraceIn = Vec(slave(Flow(Bits(payloadWidth bits))), sysSources)
  val axi = master(Axi4(axiConfig))
  val sampleLost = out(Bool())
  val dmaError = out(Bool())
  val writeSlot = out(UInt(28 bits))

  val traceDma = TraceBufferDMA(
    Bits(payloadWidth bits),
    numInputs = totalSources,
    axiConfig = axiConfig,
    axiBufferBase = axiBufferBase,
    axiBufferSize = axiBufferSize,
    axiMaxBurstLen = 256,
  )

  traceDma.axi >> axi
  sampleLost := traceDma.sampleLost
  dmaError := traceDma.dmaError
  writeSlot := traceDma.writeSlot.resized

  for (idx <- 0 until appSources) {
    traceDma.traceIn(idx) := appTraceIn(idx)
  }

  val sysClockDomain = ClockDomain(
    clock = sys_clk,
    reset = sys_reset,
    config = ClockDomain.current.config,
  )

  for (idx <- 0 until sysSources) {
    val fifo = SimpleAsyncFifo(Bits(payloadWidth bits), depthWords = sysCdcFifoDepth)()(sysClockDomain, ClockDomain.current)
    new ClockingArea(sysClockDomain) {
      fifo.slavePort.valid := sysTraceIn(idx).valid
      fifo.slavePort.payload := sysTraceIn(idx).payload
    }

    traceDma.traceIn(appSources + idx).valid := fifo.masterPort.valid
    traceDma.traceIn(appSources + idx).payload := fifo.masterPort.payload
    fifo.masterPort.ready := True
  }
}
