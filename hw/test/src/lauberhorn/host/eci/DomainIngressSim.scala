package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.axi._
import lauberhorn.Config
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping

/** Actual ingress -> sticky domain faults -> fences on BOTH worker owners. */
case class DomainIngressFixture() extends Component {
  val cfg = Axi4Config(addressWidth = 38, dataWidth = 512, idWidth = 3,
    useRegion = false, useQos = false)
  val ingress = EciAxiIngress(cfg, 2)
  val faults = EciDomainFaults(3, 1)
  val io = new Bundle {
    val input = slave(Axi4(cfg))
    val failed = out Bits(3 bits)
    val interrupt = master(Stream(Bool()))
    val endpoints = Vec(master(Axi4(ingress.routedConfig)), 3)
  }
  ingress.io.input << io.input
  ingress.io.enabled := 3
  for (w <- 0 until 2) {
    ingress.io.owners(w) := w + 1
    ingress.io.fenced(w) := faults.io.failed(w + 1)
  }
  for (s <- 0 until 3) faults.io.codes(s) := s
  faults.io.bound := 6
  faults.io.violations(0) << ingress.io.violation
  io.failed := faults.io.failed
  io.interrupt << faults.io.interrupt
  val xbar = new AxiCrossbar(ingress.routedConfig,
    Seq(AxiCrossbarSlaveConfig(concurrentOps = 4, threads = 4)),
    (0 until 3).map(i => AxiCrossbarMasterConfig(Seq(SizeMapping(i * 8192, 8192)),
      Seq(true), Seq(true), concurrentOps = 4)))
  xbar.s_axi(0) << ingress.io.routed
  io.endpoints.zip(xbar.m_axi).foreach { case (out, port) => out << port }
}

class DomainIngressSim extends DutSimFunSuite[DomainIngressFixture] {
  val dut = Config.sim.addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH -Wno-ASCRANGE -Wno-INITIALDLY")
    .compile(DomainIngressFixture())
  test("inactive PA kills its domain across workers without blaming the victim") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    val a = dut.io.input
    a.ar.valid #= false
    a.aw.valid #= false
    a.w.valid #= false
    a.r.ready #= false
    a.b.ready #= true
    dut.io.interrupt.ready #= false
    Seq(a.ar, a.aw).foreach { ch =>
      ch.id #= 1; ch.addr #= 0; ch.len #= 1; ch.size #= 6
      ch.burst #= 1; ch.lock #= 0; ch.cache #= 0; ch.prot #= 0
    }
    a.w.data #= 0; a.w.strb #= 0; a.w.last #= false
    for (p <- dut.io.endpoints) {
      p.ar.ready #= false; p.aw.ready #= false; p.w.ready #= false
      p.r.valid #= false; p.r.id #= 1; p.r.data #= 0; p.r.resp #= 0; p.r.last #= false
      p.b.valid #= false; p.b.id #= 1; p.b.resp #= 0
    }
    dut.clockDomain.waitSampling(20)
    def step(n: Int = 1): Unit = { dut.clockDomain.waitSampling(n); sleep(1) }
    def request(worker: Int, domain: Int): Unit = {
      a.ar.addr #= EciAddressMap.encode(2, worker, domain, EciAddressMap.rx_control)
      a.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
      a.ar.valid #= false
    }
    def rejected(): Unit = {
      for (last <- Seq(false, true)) {
        dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
        assert(a.r.resp.toInt == 3 && a.r.data.toBigInt == 0 && a.r.last.toBoolean == last)
        assert(dut.io.endpoints.forall(p => !p.ar.valid.toBoolean))
        a.r.ready #= true
        dut.clockDomain.waitSampling()
        a.r.ready #= false
      }
      step(3)
    }
    // Domain 1 owns worker 0, but attempts its OWN PA on worker 1 (owned by 2).
    request(1, 1)
    rejected()
    assert(dut.io.failed.toInt == 2 && dut.io.interrupt.valid.toBoolean)
    // Even its otherwise-current PA is fenced before the kernel handles IRQ.
    request(0, 1)
    rejected()
    assert(dut.io.failed.toInt == 2)
    dut.io.interrupt.ready #= true
    step(2)
    dut.io.interrupt.ready #= false
    assert(!dut.io.interrupt.valid.toBoolean && dut.io.failed.toInt == 2)
    // The victim's valid PA still reaches its endpoint and returns its bytes.
    request(1, 2)
    val p = dut.io.endpoints(2)
    dut.clockDomain.waitSamplingWhere(p.ar.valid.toBoolean)
    assert(p.ar.user.toBigInt == EciAddressMap.encode(2, 1, 2, EciAddressMap.rx_control))
    p.ar.ready #= true
    dut.clockDomain.waitSampling()
    p.ar.ready #= false
    a.r.ready #= true
    for (last <- Seq(false, true)) {
      p.r.data #= 0x22; p.r.last #= last; p.r.valid #= true
      dut.clockDomain.waitSamplingWhere(p.r.ready.toBoolean)
      p.r.valid #= false
    }
    step(5)
    assert(dut.io.failed.toInt == 2 && !dut.io.interrupt.valid.toBoolean)
  }
}
