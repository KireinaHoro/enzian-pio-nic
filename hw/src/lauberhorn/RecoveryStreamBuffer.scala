package lauberhorn

import spinal.core._
import spinal.lib._

/** Two registered slots for recovery records. Neither ready nor payload bypasses
  * the registers. Retain each slot's owner until downstream accepts the record,
  * so process draining includes records moved out of queues/worker state.
  * Sustains one transfer/cycle; a full buffer takes one cycle to reopen after pop.
  */
class RecoveryStreamBuffer[T <: Data](payloadType: HardType[T], ownerWidth: Int) extends Component {
  val io = new Bundle {
    val push = slave Stream(payloadType())
    val pushOwner = in UInt(ownerWidth bits)
    val pop = master Stream(payloadType())
    val owners = Vec(master(Flow(UInt(ownerWidth bits))), 2)
  }
  val records = Vec.fill(2)(Reg(payloadType()))
  val owners = Vec.fill(2)(Reg(UInt(ownerWidth bits)))
  val occupied = Vec.fill(2)(RegInit(False))
  // Keep the output record in a fixed register bank. A ring-head mux here
  // made context validation pull mux/comparison logic across the host/decoder
  // floorplan boundary even though the earlier arbitration was registered.
  io.push.ready := !occupied(1)
  io.pop.valid := occupied(0)
  io.pop.payload := records(0)
  when(io.pop.fire) {
    records(0) := records(1)
    owners(0) := owners(1)
    occupied(0) := occupied(1)
    occupied(1) := False
  }
  when(io.push.fire) {
    when(!occupied(0) || io.pop.fire) {
      records(0) := io.push.payload
      owners(0) := io.pushOwner
      occupied(0) := True
    } otherwise {
      records(1) := io.push.payload
      owners(1) := io.pushOwner
      occupied(1) := True
    }
  }
  for(i <- 0 until 2) {
    io.owners(i).valid := occupied(i)
    io.owners(i).payload := owners(i)
  }
}

/** Pipeline accounting events before arithmetic. Pending arrivals participate in
  * drain readback immediately, while delayed departures conservatively retain
  * ownership. Capacity is derived from the admitted-descriptor pipeline.
  */
class RecoveryInFlight(capacity: Int, sources: Int) extends Component {
  val width = log2Up(capacity + 1)
  val io = new Bundle {
    val arrivals = in Bits(sources bits)
    val departure = in Bool()
    val busy = out Bool()
    val count = out UInt(width bits)
  }
  val arrivals = RegNext(CountOne(io.arrivals)) init 0
  val departure = RegNext(io.departure) init False
  val count = Reg(UInt(width bits)) init 0
  val next = count.resize(width + 1) + arrivals.resize(width + 1) - departure.asUInt.resize(width + 1)
  when(arrivals =/= 0 || departure) {
    assert(next <= capacity, "admitted descriptor ownership overflow/underflow")
    count := next.resized
  }
  io.busy := count =/= 0 || arrivals =/= 0 || io.arrivals.orR
  io.count := count
}
