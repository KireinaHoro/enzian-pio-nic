package pionic

import spinal.core.sim._
import spinal.lib.sim._
import jsteward.blocks.DutSimFunSuite
import spinal.lib.misc.database.Database

import scala.collection.mutable

// TODO: use scalatest to test more configurations
class PacketAllocSim extends DutSimFunSuite[PacketAlloc] {
  // TODO: test on multiple configs
  val db = new Database
  db on {
    import Global._
    PKT_BUF_ADDR_WIDTH.set(32)
    PKT_BUF_ALLOC_SIZES.set(Seq(
      (128, .6), // 60% 128B packets
      (1518, .3), // 30% 1518B packets (max Ethernet frame with MTU 1500)
      (9618, .1), // 10% 9618B packets (max jumbo frame)
    ))
    DATAPATH_WIDTH.set(64)
  }

  val dut = Config.sim
    .compile(db on PacketAlloc(0xdead0000, 0x40000))

  // TODO: refactor overflow case out
  test("simple-allocate-free") { dut =>
    SimTimeout(6000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    // this will overflow the larger buffers, but since we free them the allocator should block
    // TODO: test the block-till-free case properly

    val sizes = mutable.Queue(64 until 9618 by 64: _*) ++
      // should correctly give out alloc resp without popping any buffer
      // should correctly ignore freeing of zero buffers
      Seq.fill(20)(0)
    val expect = mutable.Queue[Long]()
    val toFree = mutable.Queue[(Long, Long)]()

    StreamDriver(dut.io.allocReq, dut.clockDomain) { p =>
      if (sizes.isEmpty) false else {
        val issued = sizes.dequeue()
        println(s"Request size $issued")
        expect.enqueue(issued)
        p.bits #= issued
        true
      }
    }

    StreamDriver(dut.io.freeReq, dut.clockDomain) { p =>
      if (toFree.nonEmpty) {
        val (addr, size) = toFree.dequeue()
        println(f"Free addr $addr%#x size $size")
        p.addr.bits #= addr
        p.size.bits #= size
        true
      } else false
    }

    StreamReadyRandomizer(dut.io.allocResp, dut.clockDomain)
    StreamMonitor(dut.io.allocResp, dut.clockDomain) { p =>
      if (expect.nonEmpty) {
        val expected = expect.dequeue
        val addr = p.addr.bits.toLong
        val size = p.size.bits.toLong

        if (expected == 0) {
          assert(size == 0, "shouldn't allocate an actual slot when a zero-sized packet is requested")
          assert(addr == dut.base, "zero-sized response should have the base address")
        }

        assert(expected <= size,
          f"allocated packet $size%d smaller than expected $expected%d")
        assert(dut.base <= addr && addr < dut.len,
          f"packet addr $addr%#x outside address range [${dut.base}%#x - ${dut.len + dut.base}%#x]")
        // TODO: assert that the buffer is not previously allocated
        println(f"Allocated addr $addr%#x size $size")
        // hold packets for 20 cycles
        delayed(20) {
          toFree.enqueue((addr, size))
        }
      }
    }

    dut.clockDomain.waitActiveEdgeWhere(sizes.isEmpty && expect.isEmpty && toFree.isEmpty)
  }
}
