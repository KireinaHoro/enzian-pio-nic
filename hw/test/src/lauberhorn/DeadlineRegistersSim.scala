package lauberhorn

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.RegAllocatorFactory
import jsteward.blocks.misc.RegAllocatorFactory.allocToGeneric
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Driver

case class DeadlineRegistersFixture() extends Component {
  val bus = slave(AxiLite4(AxiLite4Config(addressWidth = 12, dataWidth = 64)))
  val allocator = new RegAllocatorFactory
  val alloc = allocator("timer")(0, 256, 8)()
  // Like the scheduler, include reserved slot zero plus domain slots.
  val slots = Seq.fill(3)(new DomainDeadline())
  slots.foreach { slot =>
    slot.io.enabled := True
    slot.io.lifetime := 1
    slot.io.now := 0
    slot.io.claim := False
  }
  val registers = new DeadlineRegisters(AxiLite4SlaveFactory(bus), alloc, slots, U(0, 64 bits), 1)
  val addresses = allocator.readBack("timer")
}

class DeadlineRegistersSim extends DutSimFunSuite[DeadlineRegistersFixture] {
  val dut = Config.sim.compile(DeadlineRegistersFixture())
  test("command-generation-forwarding-preserves-stalled-read-responses") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    val driver = AxiLite4Driver(dut.bus, dut.clockDomain)
    dut.clockDomain.waitSampling(20)
    def address(name: String): BigInt = dut.addresses("deadline", name)
    def write(name: String, value: BigInt): Unit = driver.write(address(name), value)
    def read(name: String): BigInt = driver.read(address(name))
    def step(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    write("next", 100)
    write("expected", 0)
    write("lifetime", 1)
    write("command", 1)
    assert(read("result") == 0 && read("generation") == 1)
    write("command", 1)
    assert(read("result") == 2 && read("generation") == 1)
    write("expected", 1)
    write("next", 0)

    // A command must not withdraw an already offered unrelated R response.
    dut.bus.r.ready #= false
    dut.bus.ar.addr #= address("minimumLead")
    dut.bus.ar.prot #= 0
    dut.bus.ar.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.bus.ar.ready.toBoolean)
    dut.bus.ar.valid #= false
    dut.clockDomain.waitSamplingWhere(dut.bus.r.valid.toBoolean)
    val monitor = fork {
      for (_ <- 0 until 20) {
        assert(dut.bus.r.valid.toBoolean && dut.bus.r.data.toBigInt == 1)
        step()
      }
    }
    write("command", 1)
    monitor.join()
    dut.bus.r.ready #= true; step(); dut.bus.r.ready #= false
    assert(read("result") == 0 && read("generation") == 2)
    write("command", 1)
    assert(read("result") == 2 && read("generation") == 2)
    write("command", 3)
    assert(read("result") == 1 && read("generation") == 2)
  }
}
