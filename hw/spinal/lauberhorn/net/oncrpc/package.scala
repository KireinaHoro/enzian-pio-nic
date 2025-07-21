package lauberhorn.net

import lauberhorn.Global.{ONCRPC_INLINE_BYTES, PKT_BUF_LEN_WIDTH}
import lauberhorn.PID
import spinal.core._

import scala.language.postfixOps

package object oncrpc {
  /** Header of an ONC-RPC call.  Corresponds to the `struct rpc_msg` when `mtype == 0`. */
  case class OncRpcCallHeader() extends Bundle {
    val xid = Bits(32 bits)
    val msgType = Bits(32 bits)
    val rpcVer = Bits(32 bits)
    val progNum = Bits(32 bits)
    val progVer = Bits(32 bits)
    val proc = Bits(32 bits)
    val creds = Bits(64 bits)
    val verifier = Bits(64 bits)
  }

  /** Header of an ONC-RPC reply.  Corresponds to the `struct rpc_msg` when `mtype == 1` (REPLY), `reply_stat stat == 0`
    * (MSG_ACCEPTED), and `accept_stat stat == 0` (SUCCESS).
    *
    * Note that `stat == 1` (MSG_DENIED) as well as other forms of abnormal replies are omitted in this implementation
    * -- we assume the client will not send requests that will be rejected.
    */
  case class OncRpcReplyHeader() extends Bundle {
    val xid = Bits(32 bits)
    val msgType = Bits(32 bits)
    val replyStat = Bits(32 bits)
    val verifier = Bits(64 bits)
    val acceptStat = Bits(32 bits)
  }

  // XXX: this is in big endian
  case class OncRpcCallServiceDef() extends Bundle {
    val enabled = Bool()

    val progNum = Bits(32 bits)
    val progVer = Bits(32 bits)
    val proc = Bits(32 bits)
    val listenPort = UInt(16 bits)

    val funcPtr = Bits(64 bits)
    val pid = PID()

    def matchHeader(h: OncRpcCallHeader, port: Bits) = enabled &&
      progNum === h.progNum &&
      progVer === h.progVer &&
      proc === h.proc &&
      listenPort.asBits === port
  }

  case class OncRpcCallMetadata() extends Bundle with ProtoMetadata {
    override def clone = OncRpcCallMetadata()

    val funcPtr = Bits(64 bits)
    val pid = PID()
    // first fields in the XDR payload
    val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    val hdr = OncRpcCallHeader()
    val udpPayloadSize = UInt(PKT_BUF_LEN_WIDTH bits)

    def getType = PacketDescType.oncRpcCall

    def getPayloadSize: UInt = {
      val inlineLen = ONCRPC_INLINE_BYTES.get
      val payloadLen = udpPayloadSize - hdr.getBitsWidth / 8
      (payloadLen > inlineLen) ? (payloadLen - inlineLen) | U(0)
    }

    def collectHeaders: Bits = ??? // never collected

    def asUnion: PacketDescData = {
      val ret = PacketDescData() setCompositeName(this, "union")
      ret.oncRpcCall.get := this
      ret
    }
  }

  case class OncRpcReplyMetadata() extends Bundle with ProtoMetadata {
    def getType = PacketDescType.oncRpcReply
    def getPayloadSize: UInt = ???

    def collectHeaders: Bits = ???

    def asUnion: PacketDescData = ???
  }
}
