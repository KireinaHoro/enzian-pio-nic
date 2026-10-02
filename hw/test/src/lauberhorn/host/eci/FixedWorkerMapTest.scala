package lauberhorn.host.eci

import org.scalatest.funsuite.AnyFunSuite

class FixedWorkerMapTest extends AnyFunSuite {
  test("fixed-worker map retains every domain bit, worker and line offset") {
    val domains = Seq(0, 15, 16, FixedWorkerMap.domain_limit - 1) ++
      (0 until FixedWorkerMap.domain_bits).map(1 << _)
    val offsets = Seq(0, 8191, 0x300, 0x380, 0x1300, 0x1380) ++
      (0 until 12).flatMap(i => Seq(0x400 + 128 * i, 0x1400 + 128 * i))
    for (n <- 1 to 7; w <- 0 until n; d <- domains; o <- offsets) {
      val address = FixedWorkerMap.encode(n, w, d, o)
      assert(FixedWorkerMap.decode(n, address).contains((w, d, o)))
    }
    // Explicit vectors taken from the selected JSON, including bit 37.
    assert(FixedWorkerMap.encode(7, 0, 0, 0x300) == BigInt("200300", 16))
    assert(FixedWorkerMap.encode(7, 6, 1 << 18, 0x1380) == BigInt("2000807380", 16))
  }

  test("fixed-worker map rejects bypass, unsupported workers and high address bits") {
    assert(FixedWorkerMap.decode(7, 0).isEmpty)
    assert(FixedWorkerMap.decode(7, BigInt(1) << 38).isEmpty)
    assert(FixedWorkerMap.decode(7, -1).isEmpty)
    assert(FixedWorkerMap.decode(4, FixedWorkerMap.encode(7, 4, 0)).isEmpty)
    assert(FixedWorkerMap.decode(0, 0).isEmpty)
    assert(FixedWorkerMap.decode(8, 0).isEmpty)
    intercept[IllegalArgumentException] { FixedWorkerMap.encode(4, 4, 0) }
    intercept[IllegalArgumentException] { FixedWorkerMap.encode(4, 0, 1 << 19) }
    intercept[IllegalArgumentException] { FixedWorkerMap.encode(4, 0, 0, 8192) }
  }
}
