package lauberhorn

import spinal.core.sim._
import spinal.lib.sim._
import jsteward.blocks.DutSimFunSuite
import lauberhorn.Global.PKT_BUF_TX_OFFSET
import spinal.lib.misc.database.Database

import scala.collection.mutable

// TODO: use scalatest to test more configurations
class PacketAllocSim extends DutSimFunSuite[PacketAlloc] {
  // TODO: test on multiple configs
  val db = new Database
  db on {
    import Global._
    PKT_BUF_ADDR_WIDTH.set(24)
    PKT_BUF_LEN_WIDTH.set(16)
    PKT_BUF_ALLOC_SIZES.set(Seq(
      (128, .1),
      (1518, .3),
      (9618, .6),
    ))
    DATAPATH_WIDTH.set(512)
    PKT_BUF_TX_OFFSET.set(0x50000)
  }

  val dut = Config.sim
    .compile(db on PacketAlloc(0, PKT_BUF_TX_OFFSET.get))

  // TODO: refactor overflow case out
  test("many-alloc-free") { implicit dut =>
    SimTimeout(6000000)
    dut.clockDomain.forkStimulus(period = 4) // 250 MHz

    // this will overflow the larger buffers, but since we free them the allocator should block
    // TODO: test the block-till-free case properly

    val sizes = mutable.Queue.fill(40000)(simRandom.between(16, 300))
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

    val allocatedMap = mutable.HashSet[Long]()
    StreamDriver(dut.io.freeReq, dut.clockDomain) { p =>
      if (toFree.nonEmpty) {
        val (addr, size) = toFree.dequeue()
        println(f"Free addr $addr%#x size $size")
        p.addr.bits #= addr
        p.size.bits #= size
        assert(allocatedMap.contains(addr), s"freeing unallocated slot $addr!")
        allocatedMap.remove(addr)
        println(s"Allocated buffers: ${allocatedMap.map(_.toHexString).mkString(",")}")
        true
      } else false
    }

    StreamReadyRandomizer(dut.io.allocResp, dut.clockDomain)
      .setFactor(0.7f)
    StreamMonitor(dut.io.allocResp, dut.clockDomain) { p =>
      if (expect.nonEmpty) {
        val expected = expect.dequeue
        val addr = p.addr.bits.toLong
        val size = p.size.bits.toLong

        if (expected == 0) {
          assert(size == 0, "shouldn't allocate an actual slot when a zero-sized packet is requested")
          assert(addr == dut.base, "zero-sized response should have the base address")
        }

        println(f"Allocated addr $addr%#x size $size")

        assert(expected <= size,
          f"allocated packet $size%d smaller than expected $expected%d")
        assert(dut.base <= addr && addr < dut.len,
          f"packet addr $addr%#x outside address range [${dut.base}%#x - ${dut.len + dut.base}%#x]")

        // assert that the buffer is not previously allocated
        println(s"Allocated buffers: ${allocatedMap.map(_.toHexString).mkString(",")}")
        assert(!allocatedMap.contains(addr), s"double allocation for $addr!")
        allocatedMap.add(addr)

        delayed(simRandom.nextInt(2000)) {
          toFree.enqueue((addr, size))
        }
      }
    }

    dut.clockDomain.waitActiveEdgeWhere(sizes.isEmpty && expect.isEmpty && toFree.isEmpty && allocatedMap.isEmpty)
    sleepCycles(5)

    dut.roundedMap.zipWithIndex.foreach { case ((alignedSize, slots), idx) =>
      assert(dut.io.slotOccupancy(idx).toLong == slots, s"slots of size $alignedSize missing!")
    }
  }
}
