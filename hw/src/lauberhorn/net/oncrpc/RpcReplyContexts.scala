package lauberhorn.net.oncrpc

import spinal.core._
import spinal.lib._

/** Routing bytes stay wire-native. Only the opaque token crosses the host ABI.
  * A future transport tag may select a generation-tagged TCP connection reference;
  * this implementation stores UDP request contexts only. */
case class UdpReplyContext() extends Bundle {
  val xid = Bits(32 bits)
  val clientAddr = Bits(32 bits)
  val clientPort = Bits(16 bits)
  val serverPort = Bits(16 bits)
}

/** Bounded per-request routing state, not a connection or duplicate-request cache.
  * Allocation and retirement may run together, but a retiring slot is not offered
  * until the next cycle. Generations wrap; this is not an authentication boundary. */
class RpcReplyContexts(count: Int, ownerWidth: Int = 16) extends Component {
  require(count >= 2 && isPow2(count))
  val slotBits = log2Up(count)
  val generationBits = 32 - slotBits
  val io = new Bundle {
    val allocate = slave(Flow(UdpReplyContext()))
    val allocateOwner = in UInt(ownerWidth bits)
    // Privileged reclamation, only after admission/worker/datapath drain.
    val reclaim = slave(Flow(UInt(ownerWidth bits)))
    val discard = slave(Flow(Bits(32 bits)))
    val available = out Bool()
    val token = out Bits(32 bits)
    val replyToken = in Bits(32 bits)
    val replyOwner = in UInt(ownerWidth bits)
    val replyValid = out Bool()
    val replyContext = out(UdpReplyContext())
    val retire = in Bool()
    val readIndex = in UInt(slotBits bits)
    val readContext = out(UdpReplyContext())
    val readActive = out Bool()
    val readToken = out Bits(32 bits)
    val readOwner = out UInt(ownerWidth bits)
  }
  val active = Vec.fill(count)(RegInit(False))
  val generations = Vec.fill(count)(Reg(UInt(generationBits bits)) init 0)
  val contexts = Vec.fill(count)(Reg(UdpReplyContext()))
  val owners = Vec.fill(count)(Reg(UInt(ownerWidth bits)))
  val free = Bits(count bits)
  for (i <- 0 until count) free(i) := !active(i)
  val slot = OHToUInt(OHMasking.first(free))
  val nextGeneration = (generations(slot) + 1).resize(generationBits)
  io.available := free.orR
  io.token := nextGeneration.asBits ## slot.asBits
  when(io.allocate.valid) {
    assert(io.available, "reply context allocated without capacity")
    active(slot) := True
    generations(slot) := nextGeneration
    contexts(slot) := io.allocate.payload
    owners(slot) := io.allocateOwner
  }
  val replySlot = io.replyToken(slotBits - 1 downto 0).asUInt
  io.replyValid := active(replySlot) && owners(replySlot) === io.replyOwner &&
    generations(replySlot).asBits === io.replyToken(31 downto slotBits)
  io.replyContext := contexts(replySlot)
  when(io.retire) {
    assert(io.replyValid, "invalid reply context retired")
    active(replySlot) := False
  }
  val discardSlot = io.discard.payload(slotBits - 1 downto 0).asUInt
  // Validate retirement against each local slot, rather than muxing a
  // generation out and broadcasting the result back to the active-bit bank.
  // Only the B4 discard path changes; ordinary reply/allocate timing is intact.
  for (i <- 0 until count) {
    when(io.discard.valid && discardSlot === i && active(i) &&
         generations(i).asBits === io.discard.payload(31 downto slotBits)) {
      active(i) := False
    }
    when(io.reclaim.valid && active(i) && owners(i) === io.reclaim.payload) {
      active(i) := False
    }
  }
  when(io.reclaim.valid && io.allocate.valid) {
    assert(io.allocateOwner =/= io.reclaim.payload,
      "process admission must stop before reply contexts are reclaimed")
  }
  io.readContext := contexts(io.readIndex)
  io.readActive := active(io.readIndex)
  io.readToken := generations(io.readIndex).asBits ## io.readIndex.asBits
  io.readOwner := owners(io.readIndex)
}
