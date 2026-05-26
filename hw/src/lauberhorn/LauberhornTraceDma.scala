package lauberhorn

import jsteward.blocks.misc.TraceBufferDMA
import jsteward.blocks.axi.SimpleAsyncFifo
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import scala.language.postfixOps

object LauberhornTraceDma {
  val AppSources = 16
  val SysSources = 12
  val TotalSources = AppSources + SysSources
  val SourceWidth = log2Up(TotalSources + 1)
  val TimestampWidth = 48
  val PayloadWidth = 128 - SourceWidth - TimestampWidth
  val LostCountWidth = 32
  val LostSource = (BigInt(1) << SourceWidth) - 1

  val TraceMapJson: String =
    """{
      |  "sample": {
      |    "payload_width": 75,
      |    "source_width": 5,
      |    "timestamp_width": 48,
      |    "sample_width": 128,
      |    "lost_source": 31,
      |    "lost_count_width": 32,
      |    "eci_stall_counter_shift": 16,
      |    "axi_data_width": 512,
      |    "byte_order": "little"
      |  },
      |  "payload_formats": {
      |    "dcs_event": {
      |      "fields": {
      |        "error": {"offset": 0, "width": 1},
      |        "cli": {"offset": 1, "width": 40, "format": "hex"},
      |        "state": {"offset": 41, "width": 7},
      |        "action": {"offset": 48, "width": 4},
      |        "request": {"offset": 52, "width": 5}
      |      }
      |    },
      |    "eci": {
      |      "fields": {
      |        "eci_header": {"offset": 0, "width": 64, "format": "hex"},
      |        "vc": {"offset": 64, "width": 4},
      |        "stall_count": {"offset": 68, "width": 6},
      |        "accepted": {"offset": 74, "width": 1}
      |      }
      |    }
      |  },
      |  "sources": [
      |    {"source": 0, "port": "appTraceIn_0", "type": "dcs_event", "clock_domain": "app", "dcs": "even", "local_source": 0},
      |    {"source": 1, "port": "appTraceIn_1", "type": "dcs_event", "clock_domain": "app", "dcs": "even", "local_source": 1},
      |    {"source": 2, "port": "appTraceIn_2", "type": "dcs_event", "clock_domain": "app", "dcs": "odd", "local_source": 0},
      |    {"source": 3, "port": "appTraceIn_3", "type": "dcs_event", "clock_domain": "app", "dcs": "odd", "local_source": 1},
      |    {"source": 4, "port": "appTraceIn_4", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 0, "channel": "req_wod_i"},
      |    {"source": 5, "port": "appTraceIn_5", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 1, "channel": "rsp_wod_i"},
      |    {"source": 6, "port": "appTraceIn_6", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 2, "channel": "rsp_wd_i"},
      |    {"source": 7, "port": "appTraceIn_7", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 3, "channel": "rsp_wod_o"},
      |    {"source": 8, "port": "appTraceIn_8", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 4, "channel": "rsp_wd_o"},
      |    {"source": 9, "port": "appTraceIn_9", "type": "eci", "clock_domain": "app", "dcs": "even", "local_source": 5, "channel": "fwd_wod_o"},
      |    {"source": 10, "port": "appTraceIn_10", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 0, "channel": "req_wod_i"},
      |    {"source": 11, "port": "appTraceIn_11", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 1, "channel": "rsp_wod_i"},
      |    {"source": 12, "port": "appTraceIn_12", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 2, "channel": "rsp_wd_i"},
      |    {"source": 13, "port": "appTraceIn_13", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 3, "channel": "rsp_wod_o"},
      |    {"source": 14, "port": "appTraceIn_14", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 4, "channel": "rsp_wd_o"},
      |    {"source": 15, "port": "appTraceIn_15", "type": "eci", "clock_domain": "app", "dcs": "odd", "local_source": 5, "channel": "fwd_wod_o"},
      |    {"source": 16, "port": "sysTraceIn_0", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 0, "channel": "req_wod_i"},
      |    {"source": 17, "port": "sysTraceIn_1", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 1, "channel": "rsp_wod_i"},
      |    {"source": 18, "port": "sysTraceIn_2", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 2, "channel": "rsp_wd_i"},
      |    {"source": 19, "port": "sysTraceIn_3", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 3, "channel": "rsp_wod_o"},
      |    {"source": 20, "port": "sysTraceIn_4", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 4, "channel": "rsp_wd_o"},
      |    {"source": 21, "port": "sysTraceIn_5", "type": "eci", "clock_domain": "sys", "dcs": "even", "local_source": 5, "channel": "fwd_wod_o"},
      |    {"source": 22, "port": "sysTraceIn_6", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 0, "channel": "req_wod_i"},
      |    {"source": 23, "port": "sysTraceIn_7", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 1, "channel": "rsp_wod_i"},
      |    {"source": 24, "port": "sysTraceIn_8", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 2, "channel": "rsp_wd_i"},
      |    {"source": 25, "port": "sysTraceIn_9", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 3, "channel": "rsp_wod_o"},
      |    {"source": 26, "port": "sysTraceIn_10", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 4, "channel": "rsp_wd_o"},
      |    {"source": 27, "port": "sysTraceIn_11", "type": "eci", "clock_domain": "sys", "dcs": "odd", "local_source": 5, "channel": "fwd_wod_o"},
      |    {"source": 31, "port": "lost", "type": "lost"}
      |  ]
      |}
      |""".stripMargin

  def writeTraceMap(path: os.Path): Unit =
    os.write.over(path, TraceMapJson)
}

case class LauberhornTraceDma(
                               payloadWidth: Int = LauberhornTraceDma.PayloadWidth,
                               appSources: Int = LauberhornTraceDma.AppSources,
                               sysSources: Int = LauberhornTraceDma.SysSources,
                               sysCdcFifoDepth: Int = 64,
                               axiBufferBase: BigInt = 0,
                               axiBufferSize: BigInt = BigInt(32L * 1024 * 1024 * 1024)
                             ) extends Component {
  // TraceBufferDMA adds a 5-bit global source index and a 48-bit timestamp
  // around each 75-bit source-specific payload.  That makes the Lauberhorn
  // trace sample exactly 128 bits, so four samples pack into each 512-bit beat.
  //
  // DCS event payloads:
  //   [0]     error
  //   [40:1]  CLI
  //   [47:41] state
  //   [51:48] action
  //   [56:52] request
  //   [74:57] reserved
  //
  // ECI frame payloads:
  //   [63:0]  ECI header word
  //   [67:64] VC
  //   [73:68] scaled stall count, saturated at 63
  //   [74]    accepted within the configured stall threshold
  // The scaled stall count is stalled_cycles >> 16 by default.  This spans at
  // least 10 ms for clocks up to about 400 MHz while preserving the 128b sample.
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
    addressWidth = 35,
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
    timestampWidth = LauberhornTraceDma.TimestampWidth,
    lostCountWidth = LauberhornTraceDma.LostCountWidth,
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
