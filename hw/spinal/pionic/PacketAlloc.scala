package pionic

import spinal.core._
import spinal.lib._

class PacketAlloc(base: Int, len: Int)(implicit config: PioNicConfig) extends Component {
  val io = new Bundle {
    val allocReq = slave Stream UInt(config.pktBufAddrWidth bits)
    val allocResp = master Stream PacketDesc()

    val freeReq = slave Stream PacketDesc()
  }

  // WIP
  io.allocReq.setBlocked()
  io.allocResp.setIdle()

  io.freeReq.setBlocked()
}
