package pionic.host.eci

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.sim.StreamDriver

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.BigIntRicher

import pionic._

import scala.language.postfixOps
import scala.util.Random
import scala.collection.mutable

class DcsRxAxiRouterSim extends DutSimFunSuite[DcsRxAxiRouter[UInt]] {
  val pktBufSize = 9 * 1024 * 16 // 16 packets @ 9KB (MTU)

  val dut = Config.sim
    .workspaceName("dcsRxAxiRouter")
    .compile(DcsRxAxiRouter(
      Stream(UInt(64 bits)),
      Axi4Config(
        dataWidth = 512,
        addressWidth = 20
      ),
      pktBufSizePerCore = pktBufSize,
      regWidth = 64,
    ))

  def dutSetup(blockCycles: Int)(implicit dut: DcsRxAxiRouter[UInt]) = {
    val axiMaster = Axi4Master(dut.dcsAxi, dut.clockDomain)
    val axiMemory = AxiMemorySim(dut.pktBufAxi, dut.clockDomain, AxiMemorySimConfig())

    // prefill memory with random pattern
    axiMemory.memory.writeArray(0x0, Random.nextBytes(pktBufSize))

    // start accepting requests
    axiMemory.start()

    // drive descriptor interface
    val descQueue = mutable.Queue[Long]()
    StreamDriver(dut.rxDesc, dut.clockDomain) { p =>
      if (descQueue.isEmpty) false else {
        val toIssue = descQueue.dequeue()
        println(f"Issuing packet descriptor $toIssue%#x")
        p #= toIssue
        true
      }
    }

    dut.blockCycles #= blockCycles

    (axiMaster, axiMemory, descQueue)
  }

  test("read-wait") { implicit dut =>
    // read requests with wait times
    val (aMaster, aMem, dQ) = dutSetup(500)

    0 until 50 foreach { idx =>
      val toWait = Random.nextInt(1000)
      val dataToRead = Random.nextInt(pktBufSize)
      val desc = Random.nextLong()
      val thr = fork {
        if (toWait <= 500) {
          sleepCycles(toWait)
          dQ.enqueue(desc)
        }
      }

      val clToRead = idx % 2
      val clBytes = aMaster.read(0x80 * clToRead, 0x80)
      val cl: BigIntRicher = clBytes.bytesToBigInt
      println(f"Received CL#$clToRead = ${cl.toBigInt}%#x")

      if (toWait > 500) {
        // should have been a timeout
        assert(!cl(0), "should have gotten a timeout!")
      } else {
        // should have received an actual descriptor
        assert(cl(0), "should have gotten an actual descriptor!")
        val gotDesc = cl(64 downto 1)
        println(f"Got descriptor $gotDesc%#x")
        assert(cl(64 downto 1) == desc, "descriptor mismatch")

        val dataExpected = aMem.memory.readArray(0, dataToRead).toList
        val dataGot = clBytes.drop(64) ++ aMaster.read(0x100, dataToRead - 0x40)

        check(dataExpected, dataGot)
      }

      thr.join()
    }
  }

  test("cancel-wait") { dut =>
    // block read, then cancel wait by setting blockCycles to 0
  }
}
