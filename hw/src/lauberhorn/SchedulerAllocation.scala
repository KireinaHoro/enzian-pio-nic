package lauberhorn

import spinal.core._
import spinal.lib._

/** Normal cooperative allocation, separate from failure fencing. Owner includes
  * reservations. available means the core dispatch FSM can reserve this cycle;
  * ready means its transport is at an RX boundary. One grant is committed per
  * cycle. Packet and deadline requests alternate when both can make progress.
  */
object AllocationReason extends SpinalEnum {
  val initialPacket, packetBacklog, background, deadline = newElement()
}
class SchedulerAllocation(domains: Int, cores: Int, capacity: Int) extends Component {
  require(domains > 0 && cores > 0 && capacity > 0)
  val slotWidth = log2Up(domains + 1)
  val io = new Bundle {
    val enabled = in Bits(domains + 1 bits)
    val maximum = in Vec(UInt(log2Up(cores + 1) bits), domains + 1)
    val fill = in Vec(UInt(log2Up(capacity + 1) bits), domains + 1)
    val owner = in Vec(UInt(slotWidth bits), cores)
    val available, ready = in Bits(cores bits)
    val deadlines = in Bits(domains + 1 bits)
    // Arrival occupancy is pre-enqueue, as in the existing packet policy.
    val arrival = slave Flow(UInt(slotWidth bits))
    val arrivalFill = in UInt(log2Up(capacity + 1) bits)
    val grant = master Flow(new Bundle {
      val slot = UInt(slotWidth bits)
      val core = UInt(log2Up(cores) max 1 bits)
      val reason = AllocationReason()
    })
  }
  val count = Vec((0 to domains).map(d => CountOne(io.owner.map(_ === d).asBits)))
  val idle = io.owner.map(_ === 0).asBits & io.available
  def victims(slot: UInt, allowReady: Bool, protectDonor: Boolean = false,
              emptyDonor: Boolean = false): Bits = {
    val ready = (0 until cores).map { c =>
      val donor = io.owner(c)
      val watermark = if (protectDonor) io.fill(donor).resize(io.fill(donor).getWidth + 1) * 2 <= capacity else True
      val empty = if (emptyDonor) io.fill(donor) === 0 && !io.deadlines(donor) else True
      io.available(c) && io.ready(c) && io.enabled(donor) && donor =/= slot && watermark && empty
    }.asBits
    Mux(idle.orR, idle, ready & B(cores bits, default -> allowReady))
  }
  def eligible(slot: UInt): Bool = slot =/= 0 && io.enabled(slot) && count(slot) < io.maximum(slot)
  val backlog = io.arrivalFill.resize(io.arrivalFill.getWidth + 1) * 2 >= capacity
  val packetVictims = victims(io.arrival.payload, backlog)
  val packet = io.arrival.valid && eligible(io.arrival.payload) &&
    (backlog || count(io.arrival.payload) === 0) && packetVictims.orR

  val timerVictims = (1 to domains).map(d => victims(U(d, slotWidth bits), True, protectDonor = true))
  val timerRequests = (1 to domains).map(d => io.deadlines(d) && eligible(U(d, slotWidth bits)) &&
    count(d) === 0 && timerVictims(d - 1).orR).asBits
  val timerPriority = Reg(Bits(domains bits)) init 1
  val timerSelected = OHMasking.roundRobin(timerRequests, timerPriority)
  val timerSlot = (OHToUInt(timerSelected).resize(slotWidth) + 1).resize(slotWidth)
  val timer = timerRequests.orR

  val backgroundVictims = (1 to domains).map(d => victims(U(d, slotWidth bits), True, emptyDonor = true))
  val backgroundRequests = (1 to domains).map(d => io.fill(d) =/= 0 && eligible(U(d, slotWidth bits)) &&
    count(d) === 0 && backgroundVictims(d - 1).orR).asBits
  val backgroundSelected = OHMasking.firstV2(backgroundRequests)
  val backgroundSlot = (OHToUInt(backgroundSelected).resize(slotWidth) + 1).resize(slotWidth)
  val preferTimer = RegInit(False)
  val selectedVictims = Bits(cores bits)
  io.grant.valid := packet || timer || backgroundRequests.orR
  io.grant.slot := backgroundSlot
  io.grant.reason := AllocationReason.background
  selectedVictims := Vec(backgroundVictims)((backgroundSlot - 1).resize(log2Up(domains)))
  when(packet && (!timer || !preferTimer)) {
    io.grant.slot := io.arrival.payload
    io.grant.reason := Mux(backlog, AllocationReason.packetBacklog, AllocationReason.initialPacket)
    selectedVictims := packetVictims
    preferTimer := True
  } elsewhen(timer) {
    io.grant.slot := timerSlot
    io.grant.reason := AllocationReason.deadline
    selectedVictims := Vec(timerVictims)((timerSlot - 1).resize(log2Up(domains)))
    timerPriority := timerSelected.rotateLeft(1)
    preferTimer := False
  }
  io.grant.core := OHToUInt(OHMasking.firstV2(selectedVictims)).resized
}
