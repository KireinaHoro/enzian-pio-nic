package lauberhorn.net.udp

import lauberhorn.Global.MTU
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream

/** Reserve a separate whole-packet store for each RPC encoder. The store is
  * invisible to RX until TLAST has arrived, breaking TX/RX ready loops. */
class RpcLocalRoute(md: Stream[UdpTxMeta], pld: Axi4Stream.Axi4Stream,
                    remoteMd: Stream[UdpTxMeta], remotePld: Axi4Stream.Axi4Stream,
                    localMd: Stream[UdpTxMeta], localPld: Axi4Stream.Axi4Stream,
                    localAddress: Bits) extends Area {
  val busy = RegInit(False)
  val isLocal = RegInit(False)
  val complete = RegInit(False)
  val headerSent = RegInit(False)
  val remoteHeaderSent = RegInit(False)
  val saved = Reg(UdpTxMeta())
  // RPC headers can add up to 40 bytes to a host payload. Round up, including
  // a possible partially occupied first beat from inline-header injection.
  val depth = (MTU.get + 40 + pld.config.dataWidth - 1) / pld.config.dataWidth + 1
  val fifo = StreamFifo(pld.payloadType, depth)
  val chooseLocal = md.daddr === localAddress

  // Own metadata before selecting local delivery versus the remote encoder.
  // This breaks the IP-address comparison -> encoder arbitration -> DMA
  // completion ready chain and holds the route through downstream stalls.
  remoteMd.valid := busy && !isLocal && !remoteHeaderSent
  remoteMd.payload := saved
  md.ready := !busy && !complete
  when(remoteMd.fire) { remoteHeaderSent := True }
  when(md.fire) {
    busy := True
    isLocal := chooseLocal
    saved := md.payload
    remoteHeaderSent := False
    assert(md.pldLen <= MTU.get + 40, "RPC local packet exceeds reserved capacity")
  }
  remotePld.valid := pld.valid && busy && !isLocal && remoteHeaderSent
  remotePld.payload := pld.payload
  fifo.io.push.valid := pld.valid && busy && isLocal
  fifo.io.push.payload := pld.payload
  pld.ready := busy && (isLocal ? fifo.io.push.ready | (remoteHeaderSent && remotePld.ready))
  when(pld.fire && pld.last) {
    busy := False
    when(isLocal) { complete := True }
  }
  localMd.valid := complete && !headerSent
  localMd.payload := saved
  when(localMd.fire) { headerSent := True }
  localPld.valid := fifo.io.pop.valid && complete && headerSent
  localPld.payload := fifo.io.pop.payload
  fifo.io.pop.ready := localPld.ready && complete && headerSent
  when(localPld.fire && localPld.last) {
    complete := False
    headerSent := False
  }
}
