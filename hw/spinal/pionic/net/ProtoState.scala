package pionic.net

import jsteward.blocks.misc.RegBlockAlloc
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.misc.plugin.FiberPlugin

/**
  * Base class of modules that hold state for one protocol.  The respective [[ProtoDecoder]] and [[ProtoEncoder]]
  * will consult this module for states.  Examples:
  *
  * - Statistics (packet count, etc.)
  * - ARP cache: mappings between IP and Ethernet addresses (used by IP decoders and encoders)
  * - ONCRPC sessions: mappings between (funcPtr, xid) and (IP addr, UDP port num) (used by non-nested ONCRPC call/replies)
  */
trait ProtoState extends FiberPlugin {
  /**
    * Drive control interface for this plugin.  Should be called from a host plugin, like [[pionic.host.eci.EciInterfacePlugin]].
    * @param busCtrl bus slave factory to host register access
    * @param alloc reg allocator
    */
  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit
}
