package pionic

import jsteward.blocks.axi.AxiStreamArbMux
import pionic.net.{ProtoMetadata, TaggedProtoMetadata}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable

trait PacketSinkService {
  // called by packet decoders to post packets for DMA
  def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], coreMask: Bits = null, coreMaskChanged: Bool = null)
  // consumed by AXI DMA engine
  def packetSink: Axi4Stream
}

class PacketSink(implicit config: PioNicConfig) extends FiberPlugin with PacketSinkService {
  lazy val ms = host[MacInterfaceService]
  lazy val cores = host.list[CoreControlPlugin]

  lazy val coreDescUpstreams = Seq.fill(cores.length)(mutable.ListBuffer[Stream[TaggedProtoMetadata]]())
  lazy val payloadSources = mutable.ListBuffer[Axi4Stream]()
  override def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], coreMask: Bits, coreMaskChanged: Bool): Unit = new Area {
    // handle payload data
    payloadSources.append(payloadSink)

    // handle metadata
    val tagged = metadataSink.map { md =>
      val ret = TaggedProtoMetadata()
      ret.ty := md.getType
      ret.metadata.assignFromBits(md.asBits)
      ret
    }

    // dispatch to cores
    if (coreMask == null) {
      // bypass-core (#0) only
      coreDescUpstreams.head.append(tagged)
    } else {
      // round-robin dispatch to all other (non-bypass) cores that are enabled
      // TODO: replace with more complicated scheduler?
      StreamDispatcherWithEnable(
        input = tagged,
        outputCount = cores.length - 1,
        enableMask = coreMask,
        maskChanged = coreMaskChanged,
      ) zip coreDescUpstreams foreach { case (td, cl) =>
        cl.append(td)
      }
    }
  }
  override def packetSink = logic.axisMux.masterPort

  val logic = during build new Area {
    // mux payload data axis to DMA
    val axisMux = new AxiStreamArbMux(ms.axisConfig, numSlavePorts = payloadSources.length)
    axisMux.slavePorts zip payloadSources foreach { case (sl, ms) =>
      sl << ms
    }

    // drive packet descriptors interface of core control modules
    cores zip coreDescUpstreams foreach { case (c, us) =>
      val cio = c.logic.io

      cio.igMetadata << StreamArbiterFactory().roundRobin.on(us)
    }
  }
}
