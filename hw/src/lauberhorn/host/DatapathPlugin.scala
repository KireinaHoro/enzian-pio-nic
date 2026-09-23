package lauberhorn.host

import lauberhorn._
import spinal.core._
import spinal.lib.{Flow, Stream}
import spinal.lib.misc.plugin.FiberPlugin

/** Common functionalities of a per-core datapath plugin. */
abstract class DatapathPlugin(val coreID: Int) extends FiberPlugin with DatapathService {
  require(log2Up(coreID) <= CoreID.width, s"datapath trace CoreID $coreID does not fit in ${CoreID.width} bits")

  /** datapath interfaces */
  val hostIf = during setup new Area {
    val hostTx    = Stream(PacketBufDesc())
    val hostTxResult = Flow(Bits(2 bits))
    val hostTxAck = Stream(HostReqWithTrace())
    val hostRx    = Stream(HostReqWithTrace())
    val hostRxAck = Stream(PacketBufDesc())
  }
  def hostTx    = hostIf.hostTx
  def hostTxResult = hostIf.hostTxResult
  def hostTxAck = hostIf.hostTxAck
  def hostRx    = hostIf.hostRx
  def hostRxAck = hostIf.hostRxAck
}
