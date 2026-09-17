package lauberhorn

import spinal.core._
import spinal.lib._

/** Best-effort execution watchdog, separate from the process-stop mechanism.
  * Instantiate per worker with a shared tick clock. Limit must be less than
  * half the clock range; wraparound then preserves deadline ordering.
  * Expiry is sticky until the owner is retired or monitoring is disabled.
  */
class ExecutionWatchdog(timeWidth: Int = 32) extends Component {
  require(timeWidth >= 2)
  val io = new Bundle {
    val now = in UInt(timeWidth bits)
    val enabled = in Bool()
    val limit = in UInt(timeWidth bits)
    val clear = in Bool()
    // begin also represents an atomic end-old/begin-next interval transition.
    val begin, end = in Bool()
    val active, expired = out Bool()
    val deadline = out UInt(timeWidth bits)
  }
  val active = RegInit(False)
  val expired = RegInit(False)
  val deadline = Reg(UInt(timeWidth bits)) init 0
  val elapsed = (io.now - deadline).resize(timeWidth)
  when(!io.enabled || io.clear) {
    active := False
    expired := False
  } otherwise {
    when(io.enabled) {
      assert(!io.limit.msb && io.limit =/= 0,
        "execution watchdog limit must be positive and less than half the clock range")
    }
    when(!expired) {
      when(io.begin) {
        deadline := io.now + io.limit
        active := True
      } elsewhen(io.end) {
        active := False
      } elsewhen(active && !elapsed.msb) {
        expired := True
        active := False
      }
    }
  }
  io.active := active
  io.expired := expired
  io.deadline := deadline
}
