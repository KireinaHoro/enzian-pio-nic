package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.Config
import spinal.core.sim._

class EciAddressDecoderSim extends DutSimFunSuite[EciAddressDecoder] {
  val dut = Config.sim.compile(EciAddressDecoder(7))
  test("fixed physical decode checks domain ownership without truncating high bits") { implicit dut =>
    dut.io.enabled #= 127
    for (w <- 0 until 7) dut.io.owners(w) #= 0
    val domains = Seq(0, 15, 16, (1 << 19) - 1) ++ (0 until 19).map(1 << _)
    val offsets = Seq(0x300, 0x380, 0x1300, 0x1380) ++
      (0 until 12).flatMap(i => Seq(0x400 + 128 * i, 0x1400 + 128 * i))
    for (w <- 0 until 7; d <- domains) {
      dut.io.owners(w) #= d
      for (offset <- offsets) {
        dut.io.address #= EciAddressMap.encode(7, w, d, offset)
        sleep(1)
        assert(dut.io.known.toBoolean && dut.io.admitted.toBoolean)
        assert(!dut.io.bypass.toBoolean)
        assert(dut.io.worker.toInt == w && dut.io.domain.toInt == d)
        assert(dut.io.offset.toInt == offset)
        assert(dut.io.tx.toBoolean == (offset >= 0x1000))
      }
      dut.io.owners(w) #= (d ^ 1)
      sleep(1)
      assert(dut.io.known.toBoolean && !dut.io.admitted.toBoolean)
      dut.io.owners(w) #= d
      dut.io.enabled #= (127 ^ (1 << w))
      sleep(1)
      assert(!dut.io.admitted.toBoolean)
      dut.io.enabled #= 127
      dut.io.address #= (EciAddressMap.encode(7, w, d, 0x300) | (BigInt(1) << 38))
      sleep(1)
      assert(!dut.io.known.toBoolean && !dut.io.admitted.toBoolean)
    }
    for (offset <- offsets) {
      dut.io.address #= offset
      sleep(1)
      assert(dut.io.bypass.toBoolean && dut.io.admitted.toBoolean)
    }
    for (offset <- Seq(0, 0x280, 0x301, 0xa00, 0x1280, 0x1a00, 0x1f80)) {
      dut.io.address #= offset
      sleep(1)
      assert(!dut.io.transportLine.toBoolean && !dut.io.admitted.toBoolean)
    }
  }
}
