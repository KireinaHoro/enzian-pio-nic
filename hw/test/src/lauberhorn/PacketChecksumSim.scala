package lauberhorn

import jsteward.blocks.DutSimFunSuite
import lauberhorn.net.PacketChecksum
import spinal.core.sim._

class PacketChecksumSim extends DutSimFunSuite[PacketChecksum] {
  val dut = Config.sim.compile(new PacketChecksum(64))
  test("accepted-beats-bubbles-final-drain-and-reseed") { implicit dut =>
    dut.clockDomain.forkStimulus(4)
    dut.io.seed.valid #= false
    dut.io.seed.payload #= 0
    dut.io.beat.valid #= false
    dut.io.beat.payload.foreach(_ #= 0)
    dut.clockDomain.waitSampling(10)
    def tick(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    val random = new scala.util.Random(0x544350)
    for (beats <- Seq(0, 1, 2, 24, 64, 1, 0, 31)) {
      var expected = BigInt(random.nextInt(65536))
      dut.io.seed.valid #= true
      dut.io.seed.payload #= expected
      tick()
      dut.io.seed.valid #= false
      for (_ <- 0 until beats) {
        for (_ <- 0 until random.nextInt(3)) {
          dut.io.beat.valid #= false
          // Invalid bus values must never enter the accumulator.
          dut.io.beat.payload.foreach(_ #= 65535)
          tick()
        }
        val values = Seq.fill(64)(random.nextInt(65536))
        dut.io.beat.payload.zip(values).foreach { case (lane, value) => lane #= value }
        dut.io.beat.valid #= true
        expected += values.map(BigInt(_)).sum
        tick()
      }
      dut.io.beat.valid #= false
      while (dut.io.busy.toBoolean) tick()
      assert(dut.io.sum.toBigInt == (expected & BigInt("ffffffff", 16)))
    }
  }
}
