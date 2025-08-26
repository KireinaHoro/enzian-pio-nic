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

  case class OncRpcCallServiceQuery() extends Bundle {
    val port = Bits(16 bits)
    val hdr = OncRpcCallHeader()
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

    def matchQuery(q: OncRpcCallServiceQuery) = enabled &&
      progNum === q.hdr.progNum &&
      progVer === q.hdr.progVer &&
      proc    === q.hdr.proc &&
      listenPort.asBits === q.port
  }

  /** Definition for a session with a specific client.  Filled in by [[OncRpcCallDecoder]] and used by
    * [[OncRpcReplyEncoder]] to complete the (funcPtr, xid) -> (dest IP, dest port) lookup.
    */
  case class OncRpcSessionDef() extends Bundle {
    val funcPtr = Bits(64 bits)
    val xid = Bits(32 bits)
    val clientAddr = Bits(32 bits)
    val clientPort = Bits(16 bits)
    val serverPort = Bits(16 bits)
    val active = Bool()
  }
}
