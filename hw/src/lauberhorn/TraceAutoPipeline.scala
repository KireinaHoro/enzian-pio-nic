package lauberhorn

import spinal.core._
import spinal.lib._

/** One free-running register bank on either side of a Vivado auto-pipeline.
  * Reset/enable are deliberately outside the tagged register bank (UG904).
  */
case class TracePipelineBank(width: Int, group: String, limit: Int) extends Component {
  addAttribute("keep_hierarchy", "yes")
  addAttribute("USER_SLR_ASSIGNMENT", group)
  val input = in Bits(width bits)
  val output = out Bits(width bits)
  val data = RegNext(input)
  data.addAttribute("SHREG_EXTRACT", "NO")
  data.addAttribute("KEEP", "TRUE")
  if (limit > 0) {
    data.addAttribute("AUTOPIPELINE_GROUP", "event")
    data.addAttribute("AUTOPIPELINE_LIMIT", limit)
  }
  output := data
}

/** Payload and valid are one auto-pipeline group. The simulation-only extra
  * stages exercise every permitted implementation latency; production uses 0.
  * Implemented depth is extracted from source/data to sink/data, never guessed.
  */
case class TraceAutoPipeline(payloadWidth: Int, limit: Int, sourceId: Int,
                             simulationExtraStages: Int = 0) extends Component {
  require(limit >= 0 && limit <= 24)
  require(simulationExtraStages >= 0 && simulationExtraStages <= limit)
  addAttribute("keep_hierarchy", "yes")
  addAttribute("AUTOPIPELINE_MODULE", "TRUE")
  val input = slave Flow(Bits(payloadWidth bits))
  val output = master Flow(Bits(payloadWidth bits))
  val source = TracePipelineBank(payloadWidth + 1, s"trace_source_$sourceId", limit)
  val sink = TracePipelineBank(payloadWidth + 1, "trace_collector", 0)
  source.input := (input.valid && !ClockDomain.current.isResetActive) ## input.payload
  var link = source.output
  for (_ <- 0 until simulationExtraStages) link = RegNext(link)
  sink.input := link
  // Flush unknown/resetless valid bits at startup and on each reset. The mask
  // is downstream of the extraction endpoint and adds no transport latency.
  val warmup = Reg(UInt(log2Up(limit + 3) bits)) init 0
  when(warmup =/= limit + 2) { warmup := warmup + 1 }
  output.valid := sink.output.msb && warmup === limit + 2
  output.payload := sink.output(payloadWidth - 1 downto 0)
}
