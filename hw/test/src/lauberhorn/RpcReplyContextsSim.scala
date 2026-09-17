package lauberhorn

import jsteward.blocks.DutSimFunSuite
import lauberhorn.net.oncrpc.RpcReplyContexts
import spinal.core.sim._

class RpcReplyContextsSim extends DutSimFunSuite[RpcReplyContexts] {
  val dut = Config.sim.compile(new RpcReplyContexts(16))
  test("bounded-allocation-retirement-and-stale-tokens") { implicit dut =>
    SimTimeout(1000000)
    dut.clockDomain.forkStimulus(4)
    dut.io.allocate.valid #= false
    dut.io.allocateOwner #= 42
    dut.io.reclaim.valid #= false
    dut.io.reclaim.payload #= 0
    dut.io.discard.valid #= false
    dut.io.discard.payload #= 0
    dut.io.retire #= false
    dut.io.replyToken #= 0
    dut.io.readIndex #= 0
    dut.io.allocate.xid #= 0x12345678
    dut.io.allocate.clientAddr #= 0xc0a80101L
    dut.io.allocate.clientPort #= 1000
    dut.io.allocate.serverPort #= 2000
    dut.clockDomain.waitSampling(20)
    def allocate(port: Int): BigInt = {
      sleep(1)
      assert(dut.io.available.toBoolean)
      val token = dut.io.token.toBigInt
      dut.io.allocate.clientPort #= port
      dut.io.allocate.valid #= true
      dut.clockDomain.waitSampling()
      dut.io.allocate.valid #= false
      sleep(1)
      token
    }
    def check(token: BigInt, valid: Boolean): Unit = {
      dut.io.replyToken #= token
      sleep(1)
      assert(dut.io.replyValid.toBoolean == valid)
    }
    val tokens = (0 until 16).map(i => allocate(1000 + i))
    assert(tokens.distinct.size == 16 && !dut.io.available.toBoolean)
    // Identical XIDs from different peers are distinct requests.
    for ((token, i) <- tokens.zipWithIndex) {
      check(token, true)
      assert(dut.io.replyContext.clientPort.toInt == 1000 + i)
      assert(dut.io.replyContext.xid.toBigInt == 0x12345678)
    }
    var previous = tokens(7)
    for (_ <- 0 until 1024) {
      check(previous, true)
      dut.io.retire #= true
      dut.clockDomain.waitSampling()
      dut.io.retire #= false
      check(previous, false)
      val next = allocate(54321)
      assert((next & 15) == (previous & 15) && next != previous)
      check(previous, false)
      check(next, true)
      previous = next
    }
    // Concurrent allocation and retirement affect different slots.
    check(tokens(0), true)
    dut.io.retire #= true
    dut.clockDomain.waitSampling()
    dut.io.retire #= false
    sleep(1)
    val next = dut.io.token.toBigInt
    check(tokens(1), true)
    dut.io.retire #= true
    dut.io.allocate.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.retire #= false
    dut.io.allocate.valid #= false
    check(tokens(1), false)
    check(next, true)
    check(tokens(2), true)
    // Reclaim one process without losing other owners, even when another
    // process allocates in the reclaim cycle. Reused slots reject old tokens.
    dut.io.allocateOwner #= 99
    val other = allocate(4567)
    check(tokens(2), true)
    dut.io.retire #= true
    dut.clockDomain.waitSampling()
    dut.io.retire #= false
    sleep(1)
    val concurrent = dut.io.token.toBigInt
    dut.io.allocate.valid #= true
    dut.io.reclaim.payload #= 42
    dut.io.reclaim.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.allocate.valid #= false
    dut.io.reclaim.valid #= false
    check(previous, false)
    check(next, false)
    check(other, true)
    check(concurrent, true)
    dut.io.readIndex #= (other & 15).toInt
    sleep(1)
    assert(dut.io.readOwner.toInt == 99)
    dut.io.allocateOwner #= 42
    val reused = allocate(7654)
    assert(reused != next)
    check(next, false)
    check(reused, true)
    dut.io.reclaim.payload #= 99
    dut.io.reclaim.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.reclaim.valid #= false
    check(other, false)
    check(concurrent, false)
    check(reused, true)
    // A late discard must not invalidate a new generation in the same slot.
    dut.io.discard.payload #= next
    dut.io.discard.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.discard.valid #= false
    check(reused, true)
    dut.io.discard.payload #= reused
    dut.io.discard.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.discard.valid #= false
    check(reused, false)
  }
}
