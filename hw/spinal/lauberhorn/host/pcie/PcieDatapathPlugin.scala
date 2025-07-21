package lauberhorn.host.pcie

import jsteward.blocks.misc.RichStream
import lauberhorn.GlobalCSRPlugin
import lauberhorn.host.DatapathPlugin
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._
import spinal.lib.bus.regif.AccessType.{RO, WO}

import lauberhorn.Global._

class PcieDatapathPlugin(coreID: Int) extends DatapathPlugin(coreID) {
  lazy val csr = host[GlobalCSRPlugin]

  def driveDatapath(busCtrl: BusSlaveFactory, baseAddr: Int, dataWidth: Int): Unit = {
    val alloc = ALLOC.get("core", coreID)(baseAddr, 0x1000, REG_WIDTH / 8)(dataWidth)

    val hostDescSizeRound = roundUp(PcieHostCtrlInfo().getBitsWidth+1, 64) / 8
    HOST_REQ_WIDTH.set(hostDescSizeRound.toInt * 8)

    val rxAddr = alloc("hostRx",
      readSensitive = true,
      attr = RO,
      size = hostDescSizeRound,
      // TODO: what's the syntax for allowing multiple aliases for datatype reg?
      ty = "host_ctrl_info_error | host_ctrl_info_bypass | host_ctrl_info_onc_rpc_call")

    val rxHostDesc = hostRx.map(PcieHostCtrlInfo.packFrom)
    busCtrl.readStreamBlockCycles(rxHostDesc, rxAddr, csr.logic.ctrl.rxBlockCycles)

    // on read primitive (AR for AXI), set hostRxReq for timing ReadStart
    hostRxReq := False
    busCtrl.onReadPrimitive(SingleMapping(rxAddr), haltSensitive = false, "read request issued") {
      hostRxReq := True
    }

    busCtrl.driveStream(hostRxAck.padSlave(1), alloc("hostRxAck",
      attr = WO,
      ty = "host_pkt_buf_desc"))

    // should not block; only for profiling (to use ready signal)
    busCtrl.readStreamNonBlocking(hostTx, alloc("hostTx",
      readSensitive = true,
      attr = RO,
      ty = "host_pkt_buf_desc"))

    val txHostDesc = Stream(PcieHostCtrlInfo())
    busCtrl.driveStream(txHostDesc.padSlave(1), alloc("hostTxAck",
      attr = WO,
      size = hostDescSizeRound,
      // TODO: what's the syntax for allowing multiple aliases for datatype reg?
      ty = "host_ctrl_info_error | host_ctrl_info_bypass | host_ctrl_info_onc_rpc_call"))
    hostTxAck.translateFrom(txHostDesc) { case (cc, h) =>
      h.unpackTo(cc)
    }
  }
}
