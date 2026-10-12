package lauberhorn.host.eci

import lauberhorn.{Config, Global}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.database.Database

/** Actual RX router, with only the unrelated scheduler/packet-buffer environment tied off.
  * Generated into an ignored directory for the DCU + AXI writeback RTL reproducer.
  */
case class RxWritebackFixture() extends Component {
  val axi = slave(Axi4(Axi4Config(addressWidth = 38, dataWidth = 512, idWidth = 7)))
  val invDone = in Bool()
  val quiescent = out Bool()
  val router = DcsRxAxiRouter(axi.config, axi.config.copy(addressWidth = 24))
  router.rxTraceId := 0
  router.dcsAxi <> axi
  router.rxDesc.valid := False
  router.rxDesc.payload.assignFromBits(B(0, router.rxDesc.payload.getBitsWidth bits))
  router.cancelRxDesc := False
  router.blockCycles := 0
  router.currCl := 0
  router.doDesched := False
  router.yieldRequested := False
  router.fatalRelease := False
  router.gsyncRelease := False
  router.invDone := invDone
  router.pktBufAxi.setBlocked()
  quiescent := router.quiescent
}

object GenRxWritebackFixture {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "output directory required")
    val db = new Database
    db on {
      Global.initDatabase(5, 4)
      Global.HOST_REQ_WIDTH.set(512)
    }
    Config.spinal(args(0), blackboxOnlyIfRequested).generateVerilog(db on RxWritebackFixture())
  }
}
