package lauberhorn.host

import jsteward.blocks.misc.RegBlockAlloc
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import lauberhorn.Global.BYPASS_PKTS
import spinal.core.Component.push
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.regif.AccessType.RO

import scala.collection.mutable

/** Collects different sources of commands for the bypass core and muxes them
  * to the bypass datapath service.  Currently the following sources exist:
  *  - [[lauberhorn.DmaControlPlugin]]: for bypass packets that do not go to [[lauberhorn.Scheduler]]
  *  - [[lauberhorn.net.ip.IpEncoder]]: to signal a pending ARP request
  * */
class BypassCmdSink extends FiberPlugin {
  lazy val bypassDp = host.list[DatapathService].head

  val upstreams = mutable.ArrayBuffer[Stream[HostReq]]()
  def getSink() = {
    val ret = Stream(HostReq())
    upstreams.append(ret)
    ret
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    busCtrl.read(logic.bypassFifo.io.occupancy, alloc("stat", "Number of queued bypass descriptors",
      "queueOccupancy", attr = RO))
    busCtrl.read(logic.bypassFifo.io.availability, alloc("stat", "Number of free slots in bypass queue",
      "queueAvailability", attr = RO))
  }

  val logic = during build new Area {
    val bypassFifo = StreamFifo(HostReq(), BYPASS_PKTS)
    bypassFifo.io.push << StreamArbiterFactory(s"${getName()}_bypassDescMux")
      .roundRobin
      .on(upstreams)
    bypassDp.hostRx << bypassFifo.io.pop
  }
}
