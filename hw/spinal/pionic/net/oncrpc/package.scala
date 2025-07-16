package pionic.net

import pionic.Global.{ONCRPC_INLINE_BYTES, PKT_BUF_LEN_WIDTH}
import pionic.PID
import spinal.core._

import scala.language.postfixOps

package object oncrpc {
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
    def getType = ???

    def getPayloadSize: UInt = ???

    def collectHeaders: Bits = ???

    def asUnion: PacketDescData = ???
  }
}
