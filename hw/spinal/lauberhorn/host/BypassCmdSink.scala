package lauberhorn.host

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

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

  val logic = during build new Area {
    bypassDp.hostRx <-/< StreamArbiterFactory(s"${getName()}_bypassDescMux").roundRobin.on(upstreams)
  }
}
