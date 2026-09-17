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
class RpcReplyContexts(count: Int) extends Component {
  require(count >= 2 && isPow2(count))
  val slotBits = log2Up(count)
  val generationBits = 32 - slotBits
  val io = new Bundle {
    val allocate = slave(Flow(UdpReplyContext()))
    val available = out Bool()
    val token = out Bits(32 bits)
    val replyToken = in Bits(32 bits)
    val replyValid = out Bool()
    val replyContext = out(UdpReplyContext())
    val retire = in Bool()
    val readIndex = in UInt(slotBits bits)
    val readContext = out(UdpReplyContext())
    val readActive = out Bool()
    val readToken = out Bits(32 bits)
  }
  val active = Vec.fill(count)(RegInit(False))
  val generations = Vec.fill(count)(Reg(UInt(generationBits bits)) init 0)
  val contexts = Vec.fill(count)(Reg(UdpReplyContext()))
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
  }
  val replySlot = io.replyToken(slotBits - 1 downto 0).asUInt
  io.replyValid := active(replySlot) &&
    generations(replySlot).asBits === io.replyToken(31 downto slotBits)
  io.replyContext := contexts(replySlot)
  when(io.retire) {
    assert(io.replyValid, "invalid reply context retired")
    active(replySlot) := False
  }
  io.readContext := contexts(io.readIndex)
  io.readActive := active(io.readIndex)
  io.readToken := generations(io.readIndex).asBits ## io.readIndex.asBits
}
