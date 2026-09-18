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
  val head, tail = Reg(UInt(1 bits)) init 0
  io.push.ready := !occupied(tail)
  io.pop.valid := occupied(head)
  io.pop.payload := records(head)
  when(io.push.fire) {
    records(tail) := io.push.payload
    owners(tail) := io.pushOwner
    occupied(tail) := True
    tail := tail + 1
  }
  when(io.pop.fire) {
    occupied(head) := False
    head := head + 1
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
