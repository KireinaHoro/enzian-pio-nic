package pionic.host.pcie

import pionic._
import pionic.host.DatapathPlugin
import spinal.core.Bool
import spinal.lib
import spinal.lib.bus.amba4.axi.Axi4

class PcieDatapathPlugin(coreID: Int) extends DatapathPlugin(coreID) {

  def rxPktBuffer: Axi4 = ???

  def txPktBuffer: Axi4 = ???

  def hostRxReq: Bool = ???

  def hostRx: lib.Stream[host.HostReq] = ???

  def hostRxAck: lib.Stream[PacketBufDesc] = ???

  def hostTx: lib.Stream[PacketBufDesc] = ???

  def hostTxAck: lib.Stream[host.HostReq] = ???
}
