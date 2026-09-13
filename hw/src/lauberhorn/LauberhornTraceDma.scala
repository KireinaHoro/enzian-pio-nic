package lauberhorn

import jsteward.blocks.misc.TraceBufferDMA
import jsteward.blocks.axi.{AxiStreamAligner, AxiStreamAsyncFifo, SimpleAsyncFifo}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._
import ujson.{Arr, Obj, Value}

import scala.language.postfixOps

object LauberhornTraceDma {
  // Samples are fixed at 128 bits so four samples pack exactly into each
  // 512-bit AXI beat. The full sample layout is:
  //   [74:0]   payload
  //   [80:75]  source id
  //   [127:81] timestamp
  // The all-ones source id is reserved by TraceBufferDMA for marker samples:
  // a zero payload marker is a bubble used to flush partial beats, and a
  // non-zero marker payload reports the saturated lost-sample count.
  val SampleWidth = 128

  // The lost count is stored inside the 75-bit payload field of marker samples,
  // so it does not affect the 128-bit sample layout.
  val LostCountWidth = 32

  // TracePlugin emits events as it elaborates, before the final event list is
  // known, so the hardware reserves enough low payload bits for up to 64 event
  // IDs. The JSON map reports the actually used event-id width from the final
  // trace event list.
  val EventIdSlotWidth = 6

  // Matches the ECI design's 512-bit AXI datapath.
  val AxiDataWidth = 512
  val AxiBytes = AxiDataWidth / 8
  val WriteSlotWidth = 29
  val DmaBurstFifoSize = 256
  val DmaFrameFifoSize = 1024

  val NicDecoderSlr = 0
  val NicHostInterfaceSlr = 1
  val TraceBufferDmaSlr = 2
  val PipelineStagesPerSlrCrossing = 5

  val EciHeaderWidth = 64
  val EciVcWidth = 4
  val EciSizeWidth = 3

  case class EciTraceFrame() extends Bundle {
    val header = Bits(EciHeaderWidth bits)
    val vc = Bits(EciVcWidth bits)
    val size = Bits(EciSizeWidth bits)
  }

  case class PayloadField(name: String, offset: Int, width: Int, format: Option[String] = None) {
    def end: Int = offset + width

    def json: (String, Value) = {
      val fields = Seq("offset" -> ujson.Num(offset), "width" -> ujson.Num(width)) ++
        format.map(fmt => Seq("format" -> ujson.Str(fmt))).getOrElse(Seq.empty)
      name -> Obj.from(fields)
    }
  }

  case class PayloadFormat(fields: Seq[PayloadField], extra: Seq[(String, Value)] = Seq.empty) {
    def width: Int = fields.map(_.end).max
    def json: Value = Obj.from(Seq("fields" -> Obj.from(fields.map(_.json))) ++ extra)
  }

  case class SourceInfo(fields: Seq[(String, Value)]) {
    def json: Value = Obj.from(fields)
  }

  def pipelineStagesToTraceBufferDma(sourceSlr: Int): Int = {
    require(sourceSlr >= 0 && sourceSlr <= TraceBufferDmaSlr, s"trace source SLR must be in [0, $TraceBufferDmaSlr], got $sourceSlr")
    scala.math.abs(TraceBufferDmaSlr - sourceSlr) * PipelineStagesPerSlrCrossing
  }

  private def dcsSlr(dcs: String): Int = dcs match {
    case "even" => TraceBufferDmaSlr
    case "odd" => NicDecoderSlr
  }

  private case class SourceSpec(portPrefix: String, pipelineStages: Int, fields: Seq[(String, Value)]) {
    def info(source: Int): SourceInfo =
      SourceInfo(("source" -> ujson.Num(source)) +:
        ("port" -> ujson.Str(s"${portPrefix}_$source")) +:
        ("pipeline_stages" -> ujson.Num(pipelineStages)) +:
        fields)

    def localInfo(source: Int, localSource: Int): SourceInfo =
      SourceInfo(("source" -> ujson.Num(source)) +:
        ("port" -> ujson.Str(s"${portPrefix}_$localSource")) +:
        ("pipeline_stages" -> ujson.Num(pipelineStages)) +:
        fields)
  }

  private val EciChannels = Seq("req_wod_i", "rsp_wod_i", "rsp_wd_i", "rsp_wod_o", "rsp_wd_o", "fwd_wod_o")
  private val AppLclTraceChannels = Seq(
    "lcl_fwd_wod_i",
    "lcl_rsp_wod_i",
    "lcl_fwd_wod_pipe_o",
    "lcl_rsp_wod_pipe_o",
    "dcu_lcl_fwd_wod_i",
    "dcu_lcl_rsp_wod_i",
    "lcl_rsp_wod_o",
  )
  private val SysGsyncChannels = Seq(
    "gsync_req_odd",
    "gsync_req_even",
    "gsync_rsp_odd",
    "gsync_rsp_even",
  )
  private val SysInterruptChannels = Seq(
    "intc_req_vc12",
    "intc_rsp_vc12",
  )
  private val SysBoundaryChannels = Seq(
    ("link1_out_lo", "link1", "lo"),
    ("link1_out_hi", "link1", "hi"),
    ("link2_out_lo", "link2", "lo"),
    ("link2_out_hi", "link2", "hi"),
  )
  private val SysCreditReturnChannels = Seq(
    ("link1_out_credit_return", "link1"),
    ("link2_out_credit_return", "link2"),
  )

  // DCS tracing provides two local event sources per DCS and there are two DCSs
  // in the ECI design: even and odd.
  private val AppDcsSpecs = Seq("even" -> 2, "odd" -> 2).flatMap { case (dcs, count) =>
    (0 until count).map { localSource =>
      SourceSpec("appDcsTraceIn", pipelineStagesToTraceBufferDma(dcsSlr(dcs)), Seq(
        "type" -> "dcs_event",
        "clock_domain" -> "app",
        "dcs" -> dcs,
        "local_source" -> localSource,
      ))
    }
  }

  // Each DCS has six regular ECI channels. appEciTraceIn records frames after crossing
  // into the app clock domain, then appends LCL taps from the Lauberhorn/DCS
  // boundary and selected DCS internal handoff points.
  private val AppEciSpecs = Seq("even", "odd").flatMap { dcs =>
    (EciChannels ++ AppLclTraceChannels).zipWithIndex.map { case (channel, localSource) =>
      SourceSpec("appEciTraceIn", pipelineStagesToTraceBufferDma(dcsSlr(dcs)), Seq(
        "type" -> "eci",
        "clock_domain" -> "app",
        "dcs" -> dcs,
        "local_source" -> localSource,
        "channel" -> channel,
      ))
    }
  }

  // sysEciTraceIn records the same six ECI channels per DCS before app CDC,
  // plus top-level GSYNC and interrupt-controller request/response channels.
  private val SysDcsSpecs = Seq("even", "odd").flatMap { dcs =>
    EciChannels.zipWithIndex.map { case (channel, localSource) =>
      SourceSpec("sysEciTraceIn", pipelineStagesToTraceBufferDma(NicHostInterfaceSlr), Seq(
        "type" -> "eci",
        "clock_domain" -> "sys",
        "dcs" -> dcs,
        "local_source" -> localSource,
        "channel" -> channel,
      ))
    }
  }
  private val SysGsyncSpecs = SysGsyncChannels.zipWithIndex.map { case (channel, idx) =>
    SourceSpec("sysEciTraceIn", pipelineStagesToTraceBufferDma(NicHostInterfaceSlr), Seq(
      "type" -> "eci",
      "clock_domain" -> "sys",
      "local_source" -> (SysDcsSpecs.length + idx),
      "channel" -> channel,
    ))
  }
  private val SysInterruptSpecs = SysInterruptChannels.zipWithIndex.map { case (channel, idx) =>
    SourceSpec("sysEciTraceIn", pipelineStagesToTraceBufferDma(NicHostInterfaceSlr), Seq(
      "type" -> "eci",
      "clock_domain" -> "sys",
      "local_source" -> (SysDcsSpecs.length + SysGsyncSpecs.length + idx),
      "channel" -> channel,
    ))
  }
  private val SysBoundarySpecs = SysBoundaryChannels.zipWithIndex.map { case ((channel, link, path), idx) =>
    SourceSpec("sysEciTraceIn", pipelineStagesToTraceBufferDma(NicHostInterfaceSlr), Seq(
      "type" -> "eci",
      "clock_domain" -> "sys",
      "local_source" -> (SysDcsSpecs.length + SysGsyncSpecs.length + SysInterruptSpecs.length + idx),
      "channel" -> channel,
      "boundary" -> "dynamic_static",
      "link" -> link,
      "path" -> path,
    ))
  }
  private val SysEciSpecs = SysDcsSpecs ++ SysGsyncSpecs ++ SysInterruptSpecs ++ SysBoundarySpecs
  private val SysCreditReturnSpecs = SysCreditReturnChannels.zipWithIndex.map { case ((channel, link), idx) =>
    SourceSpec("sysCreditTraceIn", pipelineStagesToTraceBufferDma(NicHostInterfaceSlr), Seq(
      "type" -> "credit_return",
      "clock_domain" -> "sys",
      "local_source" -> idx,
      "channel" -> channel,
      "boundary" -> "dynamic_static",
      "link" -> link,
    ))
  }
  private val SysSpecs = SysEciSpecs ++ SysCreditReturnSpecs

  val AppSources = AppDcsSpecs.length + AppEciSpecs.length
  val SysSources = SysSpecs.length
  val SysEciSources = SysEciSpecs.length
  val SysCreditReturnSources = SysCreditReturnSpecs.length
  val AppDcsPipelineStages: Seq[Int] = AppDcsSpecs.map(_.pipelineStages)
  val AppEciPipelineStages: Seq[Int] = AppEciSpecs.map(_.pipelineStages)
  val SysEciPipelineStages: Seq[Int] = SysEciSpecs.map(_.pipelineStages)
  val SysCreditReturnPipelineStages: Seq[Int] = SysCreditReturnSpecs.map(_.pipelineStages)

  // DCS event payload layout comes from the DCS trace producer.
  private val DcsEventFormat = PayloadFormat(Seq(
    PayloadField("error", offset = 0, width = 1),
    PayloadField("cli", offset = 1, width = 40, format = Some("hex")),
    PayloadField("state", offset = 41, width = 7),
    PayloadField("action", offset = 48, width = 4),
    PayloadField("request", offset = 52, width = 5),
    PayloadField("error_code", offset = 57, width = 4),
  ))

  // ECI trace payloads carry the 64-bit header plus local decode metadata:
  // virtual channel and whether this event is the accepted handshake. If a
  // frame is not accepted immediately, tracing first emits accepted=0 when the
  // frame is presented, then accepted=1 when it is accepted.
  private val EciFormat = PayloadFormat(Seq(
    PayloadField("eci_header", offset = 0, width = 64, format = Some("hex")),
    PayloadField("vc", offset = 64, width = 4),
    PayloadField("accepted", offset = 68, width = 1),
    PayloadField("size", offset = 69, width = 3),
    PayloadField("reserved", offset = 72, width = 3),
  ))
  private val CreditReturnFormat = PayloadFormat(Seq(
    PayloadField("credit_return", offset = 0, width = 11, format = Some("hex")),
  ))

  private val FixedPayloadFormats = Seq(
    "dcs_event" -> DcsEventFormat,
    "eci" -> EciFormat,
    "credit_return" -> CreditReturnFormat,
  )

  val PayloadWidth = FixedPayloadFormats.map(_._2.width).max
  require(PayloadWidth >= EventIdSlotWidth, s"payload width $PayloadWidth cannot hold $EventIdSlotWidth-bit event IDs")

  def eventIdWidth(lauberhornEvents: Seq[TraceEvent]): Int =
    log2Up(scala.math.max(lauberhornEvents.length, 2))

  private def traceDataKeys(lauberhornEvents: Seq[TraceEvent]): Seq[(TraceDataKey, Int)] =
    lauberhornEvents
      .flatMap(event => event.dataKeys.zip(event.dataWidths))
      .foldLeft(Seq.empty[(TraceDataKey, Int)]) { case (keys, (key, width)) =>
        keys.find(_._1.name == key.name) match {
          case Some((existing, existingWidth)) =>
            require(existingWidth == width,
              s"trace data key ${existing.name} has conflicting widths ${existingWidth} and ${width}")
            keys
          case None => keys :+ (key -> width)
        }
      }

  private def traceCorrelation: Value = Obj(
    "id_kinds" -> Obj(
      PacketID.name -> Obj(
        "flow_kind" -> "packet",
        "invalid" -> "all_ones",
        "wrap" -> true,
      ),
      RpcID.name -> Obj(
        "flow_kind" -> "rpc",
        "invalid" -> "all_ones",
        "wrap" -> true,
      ),
      HostMsgID.name -> Obj(
        "flow_kind" -> "host_msg",
        "invalid" -> "all_ones",
        "wrap" -> true,
      ),
    ),
    "flow_kinds" -> Obj(
      "packet" -> Obj(
        "id_kind" -> PacketID.name,
        "title" -> "packetFlow",
        "label" -> "packet",
      ),
      "rpc" -> Obj(
        "id_kind" -> RpcID.name,
        "title" -> "rpcFlow",
        "label" -> "rpc",
      ),
      "host_msg" -> Obj(
        "id_kind" -> HostMsgID.name,
        "title" -> "hostMsgFlow",
        "label" -> "hostMsg",
      ),
    ),
    "relationships" -> Arr(
      Obj(
        "type" -> "same_event_union",
        "id_kinds" -> Arr(PacketID.name, RpcID.name, HostMsgID.name),
      ),
    ),
    "directions" -> Arr(
      Obj(
        "direction" -> "rx",
        "contains" -> Arr("scheduler", "decoder", "_rx", "rx"),
      ),
      Obj(
        "direction" -> "tx",
        "contains" -> Arr("encoder", "_tx", "tx"),
      ),
    ),
  )

  private def lauberhornEventFormat(lauberhornEvents: Seq[TraceEvent]): PayloadFormat = {
    val idWidth = eventIdWidth(lauberhornEvents)
    val reservedIdBits = EventIdSlotWidth - idWidth
    require(reservedIdBits >= 0, s"too many trace events for $EventIdSlotWidth-bit event IDs")
    val dataKeys = traceDataKeys(lauberhornEvents)
    lauberhornEvents.foreach { event =>
      require(EventIdSlotWidth + event.dataWidths.sum <= PayloadWidth,
        s"trace event ${event.name} data does not fit in $PayloadWidth-bit payload")
    }

    val fields = Seq(
      PayloadField("event_id", offset = 0, width = idWidth),
    ) ++
      Option.when(reservedIdBits > 0)(
        PayloadField("reserved_event_id", offset = idWidth, width = reservedIdBits)
      )

    PayloadFormat(
      fields = fields,
      extra = Seq(
        "events" -> Obj.from(lauberhornEvents.zipWithIndex.map { case (event, id) =>
          id.toString -> event.name
        }),
        "trace_data_keys" -> Obj.from(dataKeys.map { case (key, width) =>
          key.name -> Obj("width" -> width)
        }),
        "event_data" -> Obj.from(lauberhornEvents.zipWithIndex.collect {
          case (event, id) if event.dataKeys.nonEmpty =>
            id.toString -> Arr.from(event.dataKeys.map(key => ujson.Str(key.name)))
        }),
      ),
    )
  }

  case class SourceLayout(lauberhornTracePorts: Seq[TracePlugin#TracePort]) {
    require(lauberhornTracePorts.nonEmpty, "LauberhornTraceDma needs at least one Lauberhorn trace source")
    require(lauberhornTracePorts.map(_.name).distinct.length == lauberhornTracePorts.length,
      s"Lauberhorn trace source names must be unique: ${lauberhornTracePorts.map(_.name).mkString(", ")}")
    lauberhornTracePorts.foreach { port =>
      require(port.pipelineStages >= 0, s"trace pipeline stages must be non-negative, got ${port.pipelineStages}")
    }

    val appDcsInputs: Seq[SourceInfo] = AppDcsSpecs.zipWithIndex.map { case (spec, source) =>
      spec.info(source)
    }

    val appEciInputs: Seq[SourceInfo] = AppEciSpecs.zipWithIndex.map { case (spec, localSource) =>
      spec.localInfo(appDcsInputs.length + localSource, localSource)
    }

    val appInputs: Seq[SourceInfo] = appDcsInputs ++ appEciInputs

    val sysEciInputs: Seq[SourceInfo] = SysEciSpecs.zipWithIndex.map { case (spec, localSource) =>
      spec.localInfo(appInputs.length + localSource, localSource)
    }

    val sysCreditReturnInputs: Seq[SourceInfo] = SysCreditReturnSpecs.zipWithIndex.map { case (spec, localSource) =>
      spec.localInfo(appInputs.length + sysEciInputs.length + localSource, localSource)
    }

    val sysInputs: Seq[SourceInfo] = sysEciInputs ++ sysCreditReturnInputs

    val lauberhornInputs: Seq[SourceInfo] = lauberhornTracePorts.zipWithIndex.map { case (port, localSource) =>
      val source = appInputs.length + sysInputs.length + localSource
      SourceInfo(Seq(
        "source" -> source,
        "port" -> s"lauberhornTraceIn_$localSource",
        "name" -> port.name,
        "type" -> "lauberhorn_event",
        "clock_domain" -> "app",
        "local_source" -> localSource,
        "pipeline_stages" -> port.pipelineStages,
      ))
    }

    val inputs: Seq[SourceInfo] = appInputs ++ sysInputs ++ lauberhornInputs
    val totalSources: Int = inputs.length
    val sourceWidth: Int = log2Up(totalSources + 1)
    val lostSource: BigInt = (BigInt(1) << sourceWidth) - 1
    val sources: Seq[SourceInfo] = inputs :+ SourceInfo(Seq(
      "source" -> ujson.Num(lostSource.toDouble),
      "port" -> "lost",
      "type" -> "lost",
    ))

    def timestampWidth(payloadWidth: Int): Int = SampleWidth - payloadWidth - sourceWidth
  }

  def traceMap(
                lauberhornTracePorts: Seq[TracePlugin#TracePort],
                lauberhornEvents: Seq[TraceEvent],
              ): Value = {
    val layout = SourceLayout(lauberhornTracePorts)
    val tw = layout.timestampWidth(PayloadWidth)
    require(tw > 0, s"trace source count leaves no room for a positive timestamp width")

    Obj(
      "sample" -> Obj(
        "payload_width" -> PayloadWidth,
        "source_width" -> layout.sourceWidth,
        "timestamp_width" -> tw,
        "sample_width" -> SampleWidth,
        "lost_source" -> ujson.Num(layout.lostSource.toDouble),
        "lost_count_width" -> LostCountWidth,
        "axi_data_width" -> AxiDataWidth,
        "byte_order" -> "little",
      ),
      "payload_formats" -> Obj.from(
        FixedPayloadFormats.map { case (name, format) => name -> format.json } :+
          ("lauberhorn_event" -> lauberhornEventFormat(lauberhornEvents).json)
      ),
      "trace_correlation" -> traceCorrelation,
      "sources" -> Arr.from(layout.sources.map(_.json)),
    )
  }

  def traceMapJson(
                    lauberhornTracePorts: Seq[TracePlugin#TracePort],
                    lauberhornEvents: Seq[TraceEvent],
                  ): String =
    ujson.write(traceMap(lauberhornTracePorts, lauberhornEvents), indent = 2)

  def writeTraceMap(
                     path: os.Path,
                     lauberhornTracePorts: Seq[TracePlugin#TracePort],
                     lauberhornEvents: Seq[TraceEvent],
                   ): Unit =
    os.write.over(path, traceMapJson(lauberhornTracePorts, lauberhornEvents))
}

case class LauberhornTraceDma(
                               lauberhornTracePorts: Seq[TracePlugin#TracePort],
                               lauberhornEvents: Seq[TraceEvent] = Seq.empty,
                               sysCdcFifoDepth: Int = 64,
                               axiBufferBase: BigInt = 0,
                               axiBufferSize: BigInt = BigInt(32L * 1024 * 1024 * 1024)
                             ) extends Component {
  // TraceBufferDMA stores each trace sample as:
  //   [74:0]   payload
  //   [80:75]  source id
  //   [127:81] timestamp
  //
  // The 75-bit payload width is the maximum of the payload formats below.
  // The source id width is log2Up(totalSources + 1), with the extra source id
  // reserved for marker samples. The remaining bits are the timestamp.
  // 128-bit samples are intentional because exactly four fit in each 512-bit
  // AXI beat.
  //
  // Marker samples use the reserved all-ones source id. Their payload is either
  // zero for a bubble inserted while flushing a partial beat, or a 32-bit
  // saturated lost-sample count in the low payload bits.
  //
  // DCS event payloads:
  //   [0]     error
  //   [40:1]  CLI
  //   [47:41] state
  //   [51:48] action
  //   [56:52] request
  //   [60:57] error code
  //   [74:61] reserved
  //
  // ECI frame payloads:
  //   [63:0]  ECI header word
  //   [67:64] VC
  //   [68]    accepted handshake event
  //   [71:69] ECI channel size
  //   [74:72] reserved
  //
  // Lauberhorn event payloads:
  //   [5:0]   event id slot; the JSON map reports how many of these bits are
  //           actually needed for the generated event list
  //   [74:6]  extra data, currently including the core id in [9:6]
  //
  // appDcsTraceIn is sampled in the app clock domain. Current source allocation:
  //   0..3   DCS event traces
  //
  // appEciTraceIn is sampled in the app clock domain. Current source allocation:
  //   0..5   even-DCS ECI frames after crossing into app
  //   6..11  odd-DCS ECI frames after crossing into app
  //
  // sysEciTraceIn is sampled in the system clock domain and crossed into app here.
  // Current source allocation:
  //   0..5   even-DCS ECI frames before app CDC
  //   6..11  odd-DCS ECI frames before app CDC
  //   12..15 GSYNC request/response frames
  //   16..17 interrupt-controller VC12 request/response frames
  //   18..21 post-gateway dynamic/static boundary frames
  //   22..23 post-gateway dynamic/static returned-credit pulses
  //
  // lauberhornTraceIn is sampled in the app clock domain and carries events
  // from TracePlugin inside NicEngine.  Each source can have a fixed pipeline
  // delay before TraceBufferDMA; the trace parser subtracts that delay from the
  // recorded timestamp using the trace-map metadata.
  val layout = LauberhornTraceDma.SourceLayout(lauberhornTracePorts)
  val totalSources = layout.totalSources
  val appSourceCount = layout.appInputs.length
  val appDcsSourceCount = layout.appDcsInputs.length
  val appEciSourceCount = layout.appEciInputs.length
  val sysSourceCount = layout.sysInputs.length
  val sysEciSourceCount = LauberhornTraceDma.SysEciSources
  val sysCreditReturnSourceCount = LauberhornTraceDma.SysCreditReturnSources
  val lauberhornSourceCount = layout.lauberhornInputs.length
  val payloadWidth = LauberhornTraceDma.PayloadWidth
  val timestampWidth = layout.timestampWidth(payloadWidth)
  require(timestampWidth > 0, s"trace source count leaves no room for a positive timestamp width")
  require(lauberhornEvents.length <= (1 << LauberhornTraceDma.EventIdSlotWidth),
    s"too many trace events for ${LauberhornTraceDma.EventIdSlotWidth}-bit event IDs")

  val axiConfig = Axi4Config(
    // The trace DMA buffer is 32 GiB by default, so 35 address bits cover the
    // full byte address range.
    addressWidth = 35,
    dataWidth = LauberhornTraceDma.AxiDataWidth,
    // Matches the ID width used by the surrounding ECI AXI fabric.
    idWidth = 7,
    useQos = false,
    useRegion = false,
  )

  val sysClock = ClockDomain.external("sysClock")

  // these are in our clock domain (app)
  val appDcsTraceIn = Vec(slave(Flow(Bits(payloadWidth bits))), appDcsSourceCount)
  val appEciTraceIn = Vec(in(Stream(LauberhornTraceDma.EciTraceFrame())), appEciSourceCount)
  val lauberhornTraceIn = Vec(slave(Flow(Bits(payloadWidth bits))), lauberhornSourceCount)
  val axi = master(Axi4(axiConfig))
  val sampleLost = out(Bool())
  val dmaError = out(Bool())
  val wrapped = out(Bool())
  val writeSlot = out(UInt(LauberhornTraceDma.WriteSlotWidth bits))

  val traceDumpRxClock = ClockDomain.external("traceDumpRxClock")
  val traceDumpTxClock = ClockDomain.external("traceDumpTxClock")
  val traceDumpAxisConfig = Axi4StreamConfig(
    dataWidth = LauberhornTraceDma.AxiBytes,
    useKeep = true,
    useLast = true,
  )
  val traceDumpRxAxis = slave(Axi4Stream(traceDumpAxisConfig)) addTag ClockDomainTag(traceDumpRxClock)
  val traceDumpTxAxis = master(Axi4Stream(traceDumpAxisConfig)) addTag ClockDomainTag(traceDumpTxClock)

  val traceDumpLocalMacOverrideValid = in(Bool())
  val traceDumpLocalMacOverride = in(Bits(48 bits))
  val traceDumpLocalIpOverrideValid = in(Bool())
  val traceDumpLocalIpOverride = in(Bits(32 bits))
  val traceDumpListenUdpPortOverrideValid = in(Bool())
  val traceDumpListenUdpPortOverride = in(Bits(16 bits))
  val traceDumpGatewayMacOverrideValid = in(Bool())
  val traceDumpGatewayMacOverride = in(Bits(48 bits))
  val traceDumpServerIpOverrideValid = in(Bool())
  val traceDumpServerIpOverride = in(Bits(32 bits))
  val traceDumpServerUdpPortOverrideValid = in(Bool())
  val traceDumpServerUdpPortOverride = in(Bits(16 bits))
  val traceDumpOverNetwork = in(Bool())
  val traceStop = in(Bool())

  // this is in sys clock domain
  val sysEciTraceIn = Vec(in(Stream(LauberhornTraceDma.EciTraceFrame())), sysEciSourceCount) addTag ClockDomainTag(sysClock)
  val sysCreditTraceIn = Vec(slave(Flow(Bits(payloadWidth bits))), sysCreditReturnSourceCount) addTag ClockDomainTag(sysClock)

  val traceDma = TraceBufferDMA(
    Bits(payloadWidth bits),
    numInputs = totalSources,
    axiConfig = axiConfig,
    axiBufferBase = axiBufferBase,
    axiBufferSize = axiBufferSize,
    timestampWidth = timestampWidth,
    lostCountWidth = LauberhornTraceDma.LostCountWidth,
    burstFifoSize = LauberhornTraceDma.DmaBurstFifoSize,
    frameFifoSize = LauberhornTraceDma.DmaFrameFifoSize,
    axiMaxBurstLen = 256,
  )

  traceDma.axi >> axi
  traceDma.readEnable := True
  traceDma.traceStop := traceStop
  sampleLost := traceDma.sampleLost
  dmaError := traceDma.dmaError
  wrapped := traceDma.wrapped
  writeSlot := traceDma.writeSlot.resized

  val traceDumpRxFifo = AxiStreamAsyncFifo(
    traceDumpAxisConfig,
    frameFifo = true,
    dropWhenFull = true,
    depthBytes = LauberhornTraceDma.AxiBytes * 32,
  )()(traceDumpRxClock, ClockDomain.current)
  new ClockingArea(traceDumpRxClock) {
    traceDumpRxFifo.s_axis << traceDumpRxAxis
  }

  val traceDumpTxAligner = AxiStreamAligner(traceDumpAxisConfig)
  val traceDumpTxFifo = AxiStreamAsyncFifo(
    traceDumpAxisConfig,
    frameFifo = true,
    depthBytes = LauberhornTraceDma.AxiBytes * 32,
    // Decouple CMAC ready from the async FIFO RAM reader.
    outputFifoEnable = true,
  )()(ClockDomain.current, traceDumpTxClock)
  traceDumpTxFifo.s_axis <-/< traceDumpTxAligner.io.output
  traceDumpTxFifo.m_axis >> traceDumpTxAxis

  val traceDump = LauberhornTraceDump(
    axisConfig = traceDumpAxisConfig,
    dmaConfig = traceDma.dmaConfig,
    axiBufferBase = axiBufferBase,
  )
  traceDump.rx << traceDumpRxFifo.m_axis
  traceDump.tx >> traceDumpTxAligner.io.input
  traceDump.cfg.localMacOverrideValid := traceDumpLocalMacOverrideValid
  traceDump.cfg.localMacOverride := traceDumpLocalMacOverride
  traceDump.cfg.localIpOverrideValid := traceDumpLocalIpOverrideValid
  traceDump.cfg.localIpOverride := traceDumpLocalIpOverride
  traceDump.cfg.listenUdpPortOverrideValid := traceDumpListenUdpPortOverrideValid
  traceDump.cfg.listenUdpPortOverride := traceDumpListenUdpPortOverride
  traceDump.cfg.gatewayMacOverrideValid := traceDumpGatewayMacOverrideValid
  traceDump.cfg.gatewayMacOverride := traceDumpGatewayMacOverride
  traceDump.cfg.dumpServerIpOverrideValid := traceDumpServerIpOverrideValid
  traceDump.cfg.dumpServerIpOverride := traceDumpServerIpOverride
  traceDump.cfg.dumpServerUdpPortOverrideValid := traceDumpServerUdpPortOverrideValid
  traceDump.cfg.dumpServerUdpPortOverride := traceDumpServerUdpPortOverride
  traceDump.dumpOverNetwork := traceDumpOverNetwork
  traceDump.status.writeSlot := writeSlot
  traceDump.status.wrapped := wrapped
  traceDump.status.sampleLost := sampleLost
  traceDump.status.dmaError := dmaError
  traceDma.readDesc << traceDump.readDesc
  traceDma.readData >> traceDump.readData
  traceDma.readDescStatus >> traceDump.readDescStatus

  def traceEciFrame(in: Stream[LauberhornTraceDma.EciTraceFrame]): Flow[Bits] = new Area {
    val pendingAccepted = RegInit(False)
    val traceValid = RegInit(False)
    val tracePayload = Reg(Bits(payloadWidth bits)) init(0)

    def packPayload(accepted: Bool): Bits =
      (B(0, 3 bits) ## in.payload.size ## accepted.asBits ## in.payload.vc ## in.payload.header).resized

    traceValid := False

    when(!in.valid) {
      pendingAccepted := False
    } elsewhen(in.ready) {
      traceValid := True
      tracePayload := packPayload(True)
      pendingAccepted := False
    } otherwise {
      when(!pendingAccepted) {
        traceValid := True
        tracePayload := packPayload(False)
        pendingAccepted := True
      }
    }

    val out = Flow(Bits(payloadWidth bits))
    out.valid := traceValid
    out.payload := tracePayload
  }.out

  for (idx <- 0 until appDcsSourceCount) {
    traceDma.traceIn(idx) := appDcsTraceIn(idx).delay(LauberhornTraceDma.AppDcsPipelineStages(idx))
  }

  for (idx <- 0 until appEciSourceCount) {
    traceDma.traceIn(appDcsSourceCount + idx) :=
      traceEciFrame(appEciTraceIn(idx)).delay(LauberhornTraceDma.AppEciPipelineStages(idx))
  }

  for (idx <- 0 until sysEciSourceCount) {
    val fifo = SimpleAsyncFifo(Bits(payloadWidth bits), depthWords = sysCdcFifoDepth)()(sysClock, ClockDomain.current)
    new ClockingArea(sysClock) {
      val sysTrace = traceEciFrame(sysEciTraceIn(idx))
      fifo.slavePort.valid := sysTrace.valid
      fifo.slavePort.payload := sysTrace.payload
    }

    val sysTraceToDma = Flow(Bits(payloadWidth bits))
    sysTraceToDma.valid := fifo.masterPort.valid
    sysTraceToDma.payload := fifo.masterPort.payload
    traceDma.traceIn(appSourceCount + idx) := sysTraceToDma.delay(LauberhornTraceDma.SysEciPipelineStages(idx))
    fifo.masterPort.ready := True
  }

  for (idx <- 0 until sysCreditReturnSourceCount) {
    val fifo = SimpleAsyncFifo(Bits(payloadWidth bits), depthWords = sysCdcFifoDepth)()(sysClock, ClockDomain.current)
    new ClockingArea(sysClock) {
      fifo.slavePort.valid := sysCreditTraceIn(idx).valid
      fifo.slavePort.payload := sysCreditTraceIn(idx).payload
    }

    val sysTraceToDma = Flow(Bits(payloadWidth bits))
    sysTraceToDma.valid := fifo.masterPort.valid
    sysTraceToDma.payload := fifo.masterPort.payload
    traceDma.traceIn(appSourceCount + sysEciSourceCount + idx) :=
      sysTraceToDma.delay(LauberhornTraceDma.SysCreditReturnPipelineStages(idx))
    fifo.masterPort.ready := True
  }

  for (idx <- 0 until lauberhornSourceCount) {
    traceDma.traceIn(appSourceCount + sysSourceCount + idx) :=
      lauberhornTraceIn(idx).delay(lauberhornTracePorts(idx).pipelineStages)
  }
}
