package lauberhorn.host.eci

import lauberhorn.sim.CapturedTraceEvent
import scala.collection.mutable

/** Observed protocol-side obligations, not an end-to-end refinement proof.
  * Trace addresses here are protocol-local worker offsets. Counts preserve
  * repeated equal-valued events. Events on distinct ports in one cycle are
  * linearized in dependency order; this permits simultaneous handshakes.
  */
object CoherenceEventChecks {
  def check(events: Seq[CapturedTraceEvent]): Unit = {
    val order = Map("EciLclLciFire" -> 0, "EciLclLciaFire" -> 1)
    val counts = mutable.Map[(BigInt, BigInt), Array[Int]]()
    val relevant = events.filter(e => order.contains(e.eventName))
      .sortBy(e => (e.cycle, order(e.eventName)))
    assert(relevant.nonEmpty, "no coherence events observed")
    relevant.foreach { event =>
      val address = event.data("LclAddress")
      assert(address % 128 == 0, s"unaligned coherence address in $event")
      val key = (event.data("CoreID"), address)
      val count = counts.getOrElseUpdate(key, Array.fill(2)(0))
      val stage = order(event.eventName)
      count(stage) += 1
      assert(stage == 0 || count(stage) <= count(stage - 1),
        s"coherence completion without predecessor: $event, counts=${count.toSeq}")
    }
    // Nonzero residual counts are retained transactions, not permission to
    // release resources. A finite capture need not finish every transaction.
    println(s"CoherenceEventChecks: ${relevant.size} events across ${counts.size} lines")
  }
}
