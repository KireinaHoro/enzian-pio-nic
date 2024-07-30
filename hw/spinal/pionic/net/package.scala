package pionic

import spinal.core._
import spinal.lib.misc.plugin._

import scala.collection.mutable

package object net {
  object ProtoType extends SpinalEnum {
    val ethernet, ip, udp, oncRpcCall = newElement()
  }

  case class ProtoMetadata()(implicit config: PioNicConfig) extends Union {
    val ethernet = newElement(EthernetMetadata())
    val ip = newElement(IpMetadata())
    val udp = newElement(UdpMetadata())
  }

  trait ProtoDecoder extends FiberPlugin {
    // downstream decoder, condition to match
    // e.g. Ip.downs = [ (Tcp, proto === 6), (Udp, proto === 17) ]
    val downs = mutable.ListBuffer[(ProtoDecoder, ProtoMetadata => Bool)]()

    // possible upstream carriers
    // e.g. oncRpc.from(Tcp -> <port registered>, Udp -> <port registered>)
    // this is not called for the source decoder (ethernet)
    def from(carrierRules: (ProtoDecoder, ProtoMetadata => Bool)*): Unit = {
      carrierRules foreach { case (dec, matchFunc) =>
        dec.downs.append((this, matchFunc))
      }
    }
  }

  trait ProtoEncoder {

  }
}
