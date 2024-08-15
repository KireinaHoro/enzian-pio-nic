package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import spinal.lib.bus.misc.BusSlaveFactory

import scala.Function.untupled
import scala.collection.mutable
import scala.reflect.ClassTag

package object net {
  /**
   * Type of the (potentially partially) decoded packet. Used by [[TaggedProtoMetadata]] as well as [[HostPacketDesc]].
   */
  object ProtoMetadataType extends SpinalEnum {
    val ethernet, ip, udp, oncRpcCall = newElement()
  }

  /**
   * Protocol metadata passed along in the decoder pipeline and to the core control module.
   *
   * Should also provide all information needed to reconstruct the entire packet for exceptional delivery to the TAP
   * interface (bypass).  [[CoreControlPlugin]] will translate this to a [[HostPacketDesc]], dropping fields that the
   * host does not need.
   */
  trait ProtoMetadata extends Data {
    // used to tag metadata sent to cores
    def getType: ProtoMetadataType.E
    def getPayloadSize: UInt
  }

  /**
   * [[ProtoMetadata]] plus type information. Used between [[PacketSink]] and [[CoreControlPlugin]].
   */
  case class TaggedProtoMetadata()(implicit config: PioNicConfig) extends Bundle {
    val ty = ProtoMetadataType()
    val metadata = new Union {
      val ethernet = newElement(EthernetMetadata())
      val ip = newElement(IpMetadata())
      val udp = newElement(UdpMetadata())
      val oncRpcCall = newElement(OncRpcCallMetadata())
    }

    def getPayloadSize: UInt = {
      val ret = UInt(16 bits)
      switch (ty) {
        import ProtoMetadataType._
        is (ethernet) { ret := metadata.ethernet.getPayloadSize }
        is (ip) { ret := metadata.ip.getPayloadSize }
        is (udp) { ret := metadata.udp.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
        default { ret := 0 }
      }
      ret
    }
  }

  type DecodeConsumer[T <: ProtoMetadata] = (T => Bool, Stream[T], Axi4Stream)

  trait ProtoDecoder[T <: ProtoMetadata] extends FiberPlugin {
    // downstream decoder, condition to match
    // e.g. Ip.downs = [ (Tcp, proto === 6), (Udp, proto === 17) ]
    val consumers = mutable.ListBuffer[DecodeConsumer[T]]()
    val csr = host[GlobalCSRPlugin].logic

    // possible upstream carriers
    // e.g. oncRpc.from(Tcp -> <port registered>, Udp -> <port registered>)
    // this is not called for the source decoder (ethernet)
    def from[M <: ProtoMetadata, D <: ProtoDecoder[M]: ClassTag] = Function.untupled((consumer: DecodeConsumer[M]) => {
      host[D].consumers.append(consumer)
    })

    def connectConsumers(metadata: Stream[T], payload: Axi4Stream) = new Area {
      // FIXME: do we need synchronous here?
      val forkedHeaders = StreamFork(metadata, consumers.length + 1)//, synchronous = true)
      val forkedPayloads = StreamFork(payload, consumers.length + 1)

      val attempted = CombInit(False)
      consumers.zipWithIndex foreach { case ((matchFunc, headerSink, payloadSink), idx) => new Area {
        // is it possible to go to this consumer?
        val hdr = forkedHeaders(idx)
        val pld = forkedPayloads(idx)

        // FIXME: timing!  what happens if the downstream decoder is busy?
        val attempt = matchFunc(hdr.payload)

        // track attempt for bypass
        attempted := attempted | attempt

        headerSink << hdr.takeWhen(attempt)
        payloadSink << pld.takeFrameWhen(attempt)
      }
      }

      val bypassHeader = forkedHeaders.last.takeWhen(!attempted)
      val bypassPayload = forkedPayloads.last.takeFrameWhen(!attempted)

      host[PacketSinkService].consume(bypassPayload, bypassHeader)
    }

    def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit
  }

  trait ProtoEncoder {

  }
}
