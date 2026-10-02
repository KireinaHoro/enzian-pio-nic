package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.axi._
import lauberhorn.Config
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping

case class EciIngressFixture() extends Component {
  val cfg = Axi4Config(addressWidth = 38, dataWidth = 512, idWidth = 3,
    useRegion = false, useQos = false)
  val ingress = EciAxiIngress(cfg, 1)
  val io = new Bundle {
    val input = slave(Axi4(cfg))
    val owner = in UInt(19 bits)
    val enabled, fenced = in Bool()
    val busy = out Bool()
    val endpoints = Vec(master(Axi4(ingress.routedConfig)), 2)
  }
  ingress.io.input << io.input
  ingress.io.owners(0) := io.owner
  ingress.io.enabled(0) := io.enabled
  ingress.io.fenced(0) := io.fenced
  io.busy := ingress.io.busy(0)
  val xbar = new AxiCrossbar(ingress.routedConfig,
    Seq(AxiCrossbarSlaveConfig(concurrentOps = 4, threads = 4)),
    (0 until 2).map(i => AxiCrossbarMasterConfig(Seq(SizeMapping(i * 8192, 8192)),
      Seq(true), Seq(true), concurrentOps = 4)))
  xbar.s_axi(0) << ingress.io.routed
  io.endpoints.zip(xbar.m_axi).foreach { case (out, port) => out << port }
}

class EciAxiIngressSim extends DutSimFunSuite[EciIngressFixture] {
  val dut = Config.sim.addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH -Wno-ASCRANGE -Wno-INITIALDLY")
    .compile(EciIngressFixture())
  test("accepted original addresses survive crossbar backpressure and owner changes") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    val a = dut.io.input
    dut.io.owner #= 1
    dut.io.enabled #= true
    dut.io.fenced #= false
    a.ar.valid #= false
    a.aw.valid #= false
    a.w.valid #= false
    a.r.ready #= false
    a.b.ready #= false
    Seq(a.ar, a.aw).foreach { channel =>
      channel.id #= 1
      channel.addr #= 0
      channel.len #= 1
      channel.size #= 6
      channel.burst #= 1
      channel.lock #= 0
      channel.cache #= 0
      channel.prot #= 0
    }
    a.w.data #= 0
    a.w.strb #= (BigInt(1) << 64) - 1
    a.w.last #= false
    for (port <- dut.io.endpoints) {
      port.ar.ready #= false
      port.aw.ready #= false
      port.w.ready #= false
      port.r.valid #= false
      port.r.id #= 1
      port.r.data #= 0
      port.r.resp #= 0
      port.r.last #= false
      port.b.valid #= false
      port.b.id #= 1
      port.b.resp #= 0
    }
    dut.clockDomain.waitSampling(20)
    def step(n: Int = 1): Unit = { dut.clockDomain.waitSampling(n); sleep(1) }
    val target = dut.io.endpoints(1)
    val code = (1 << 18) | 1
    val original = EciAddressMap.encode(1, 0, code, 0x300)
    dut.io.owner #= code
    a.ar.addr #= original
    a.ar.valid #= true
    dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
    a.ar.valid #= false
    dut.io.owner #= 2
    dut.io.enabled #= false
    dut.io.fenced #= true
    step(8)
    assert(dut.io.busy.toBoolean)
    assert(target.ar.valid.toBoolean)
    assert(target.ar.user.toBigInt == original)
    assert(target.ar.addr.toBigInt == 0x2300)
    step(5)
    assert(target.ar.user.toBigInt == original && target.ar.addr.toBigInt == 0x2300)
    target.ar.ready #= true
    dut.clockDomain.waitSamplingWhere(target.ar.valid.toBoolean)
    target.ar.ready #= false
    a.r.ready #= true
    for (last <- Seq(false, true)) {
      target.r.valid #= true
      target.r.last #= last
      target.r.data #= 0x1234
      dut.clockDomain.waitSamplingWhere(target.r.ready.toBoolean)
      target.r.valid #= false
      if (!last) {
        step(2)
        assert(dut.io.busy.toBoolean)
      }
    }
    step(6)
    assert(!dut.io.busy.toBoolean)

    // Late RX writeback must still reach the independent RX sink while fenced.
    val oldRx = EciAddressMap.encode(1, 0, code, 0x380)
    a.aw.addr #= oldRx
    a.aw.valid #= true
    dut.clockDomain.waitSamplingWhere(a.aw.ready.toBoolean)
    a.aw.valid #= false
    step(8)
    assert(target.aw.valid.toBoolean && target.aw.user.toBigInt == oldRx)
    assert(dut.io.busy.toBoolean)
    target.aw.ready #= true
    dut.clockDomain.waitSamplingWhere(target.aw.valid.toBoolean)
    target.aw.ready #= false
    target.w.ready #= true
    for (last <- Seq(false, true)) {
      a.w.valid #= true
      a.w.last #= last
      dut.clockDomain.waitSamplingWhere(a.w.ready.toBoolean)
      a.w.valid #= false
      step(2)
    }
    target.w.ready #= false
    target.b.valid #= true
    dut.clockDomain.waitSamplingWhere(target.b.ready.toBoolean)
    target.b.valid #= false
    step(3)
    assert(dut.io.busy.toBoolean && a.b.valid.toBoolean)
    a.b.ready #= true
    dut.clockDomain.waitSamplingWhere(a.b.valid.toBoolean)
    step(2)
    assert(!dut.io.busy.toBoolean)

    // Old-owner TX writes receive DECERR, never reach the successor endpoint.
    a.aw.addr #= EciAddressMap.encode(1, 0, code, 0x1300)
    a.aw.valid #= true
    dut.clockDomain.waitSamplingWhere(a.aw.ready.toBoolean)
    a.aw.valid #= false
    a.b.ready #= false
    for (last <- Seq(false, true)) {
      a.w.valid #= true
      a.w.last #= last
      dut.clockDomain.waitSamplingWhere(a.w.ready.toBoolean)
      a.w.valid #= false
      step(2)
      assert(!target.aw.valid.toBoolean && !target.w.valid.toBoolean)
    }
    dut.clockDomain.waitSamplingWhere(a.b.valid.toBoolean)
    assert(a.b.resp.toInt == 3 && !dut.io.busy.toBoolean)
    a.b.ready #= true
    step(2)

    // A rejected inactive-domain read still returns ordinary AXI beats. The
    // production axi_rd_cl ignores RRESP; this test must not be described as
    // an architectural ECI fault or a no-cache-allocation guarantee.
    a.ar.addr #= original
    a.ar.valid #= true
    a.r.ready #= false
    dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
    a.ar.valid #= false
    for (last <- Seq(false, true)) {
      dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
      assert(a.r.resp.toInt == 3 && a.r.data.toBigInt == 0)
      assert(a.r.last.toBoolean == last)
      assert(!target.ar.valid.toBoolean && !dut.io.busy.toBoolean)
      a.r.ready #= true
      dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
      a.r.ready #= false
      step(2)
    }
  }
}
