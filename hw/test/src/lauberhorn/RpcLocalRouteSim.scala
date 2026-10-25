package lauberhorn

import jsteward.blocks.DutSimFunSuite
import lauberhorn.net.udp.{RpcLocalRoute, UdpTxMeta}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.misc.database.Database

case class RpcLocalRouteFixture() extends Component {
  val config = Axi4StreamConfig(dataWidth = 8, useKeep = true, useLast = true)
  val md = slave(Stream(UdpTxMeta()))
  val pld = slave(Axi4Stream(config))
  val remoteMd, localMd = master(Stream(UdpTxMeta()))
  val remotePld, localPld = master(Axi4Stream(config))
  val address = in Bits(32 bits)
  val route = new RpcLocalRoute(md, pld, remoteMd, remotePld, localMd, localPld, address)
}

class RpcLocalRouteSim extends DutSimFunSuite[RpcLocalRouteFixture] {
  val db = new Database
  db on { Global.initDatabase(2, 1) }
  val dut = Config.sim.compile(db on RpcLocalRouteFixture())
  test("captured-route-and-metadata-survive-backpressure-and-address-changes") { implicit dut =>
    SimTimeout(100000)
    dut.clockDomain.forkStimulus(10)
    dut.md.valid #= false
    dut.md.rpcId #= 0; dut.md.native #= false; dut.md.hostMsgId #= 0; dut.md.coreId #= 0
    dut.md.daddr #= 0; dut.md.dport #= 100; dut.md.sport #= 200; dut.md.pldLen #= 24
    dut.pld.valid #= false; dut.pld.data #= 0; dut.pld.keep #= 255; dut.pld.last #= false
    dut.remoteMd.ready #= false; dut.localMd.ready #= false
    dut.remotePld.ready #= false; dut.localPld.ready #= false
    dut.address #= 1
    dut.clockDomain.waitSampling(20)
    def step(): Unit = { dut.clockDomain.waitSampling(); sleep(1) }
    for (packet <- 0 until 12) {
      val local = packet % 2 == 0
      dut.address #= 1
      dut.md.daddr #= (if (local) 1 else 2)
      dut.md.rpcId #= packet
      dut.md.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.md.ready.toBoolean)
      dut.md.valid #= false
      sleep(1)
      // Neither subsequent input metadata nor a CSR address change may retag it.
      dut.md.daddr #= 9
      dut.address #= 2
      val outputMd = if (local) dut.localMd else dut.remoteMd
      val outputPld = if (local) dut.localPld else dut.remotePld
      if (!local) {
        dut.clockDomain.waitSamplingWhere(outputMd.valid.toBoolean)
        for (_ <- 0 until 5) {
          assert(outputMd.valid.toBoolean && outputMd.daddr.toInt == 2 && outputMd.rpcId.toInt == packet)
          assert(!dut.pld.ready.toBoolean && !outputPld.valid.toBoolean)
          step()
        }
        outputMd.ready #= true; step(); outputMd.ready #= false
      }
      for (beat <- 0 until 3) {
        val data = BigInt(packet * 16 + beat + 1)
        dut.pld.data #= data; dut.pld.last #= (beat == 2); dut.pld.valid #= true
        if (!local) {
          sleep(1)
          for (_ <- 0 until 3) {
            assert(outputPld.valid.toBoolean && outputPld.data.toBigInt == data)
            assert(outputPld.last.toBoolean == (beat == 2) && outputPld.keep.toInt == 255)
            step()
          }
          outputPld.ready #= true
        }
        dut.clockDomain.waitSamplingWhere(dut.pld.ready.toBoolean)
        dut.pld.valid #= false; outputPld.ready #= false
        sleep(1)
      }
      if (local) {
        dut.clockDomain.waitSamplingWhere(outputMd.valid.toBoolean)
        for (_ <- 0 until 5) {
          assert(outputMd.valid.toBoolean && outputMd.daddr.toInt == 1 && outputMd.rpcId.toInt == packet)
          assert(!outputPld.valid.toBoolean)
          step()
        }
        outputMd.ready #= true; step(); outputMd.ready #= false
        for (beat <- 0 until 3) {
          dut.clockDomain.waitSamplingWhere(outputPld.valid.toBoolean)
          for (_ <- 0 until 3) {
            assert(outputPld.data.toBigInt == packet * 16 + beat + 1)
            assert(outputPld.last.toBoolean == (beat == 2) && outputPld.keep.toInt == 255)
            step()
          }
          outputPld.ready #= true; step(); outputPld.ready #= false
        }
      }
      step()
      assert(dut.md.ready.toBoolean)
      assert(!dut.remoteMd.valid.toBoolean && !dut.localMd.valid.toBoolean)
    }
  }
}
