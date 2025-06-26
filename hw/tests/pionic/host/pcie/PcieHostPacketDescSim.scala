package pionic.host.pcie

import jsteward.blocks.misc.sim.{BigIntParser, BigIntBuilder}
import pionic.sim._
import pionic.Global._
import spinal.core.IntToBuilder

sealed abstract class PcieHostPacketDescSim extends HostPacketDescSim {
  def addr: BigInt
  def size: BigInt
  def encode: BigInt

  /** generate a [[pionic.PacketBufDesc]] for freeing */
  def toRxAck: BigInt = {
    (new BigIntBuilder)
      .push(PKT_BUF_ADDR_WIDTH, addr, skip = 1)
      .push(PKT_BUF_LEN_WIDTH, size)
      .toBigInt
  }
  /** generate a [[PcieHostCtrlInfo]] for TX use */
  def toTxDesc: List[Byte] = {
    val b = (new BigIntBuilder)
      .push(PKT_BUF_ADDR_WIDTH, addr, skip = 1)
      .push(PKT_BUF_LEN_WIDTH, size)
      .push(HOST_REQ_TY_WIDTH, ty)
      .pushTo(HOST_REQ_WIDTH.get+1, encode)
      .toBigInt
    // make sure we encode all zero bytes as well
    spinal.core.sim.SimBigIntPimper(b).toBytes(HOST_REQ_WIDTH.get+1).toList
  }
}

object PcieHostPacketDescSim {
  def fromBigInt(v: BigInt) = {
    val p = new BigIntParser(v)
    val addr = p.pop(PKT_BUF_ADDR_WIDTH).toInt
    val size = p.pop(PKT_BUF_LEN_WIDTH).toInt
    val ty   = p.pop(HOST_REQ_TY_WIDTH).toInt
    val data = p.popTo(HOST_REQ_WIDTH)

    val dp = new BigIntParser(data)
    ty match {
      case 0 => throw new RuntimeException("error host packet desc received")
      case 1 =>
        BypassPacketDescSimPcie(addr, size,
          dp.pop(PKT_DESC_TY_WIDTH),
          dp.pop(BYPASS_HDR_WIDTH, skip = 19))
      case 2 =>
        val xid = dp.pop(32, skip = 21)
        OncRpcCallPacketDescSimPcie(addr, size,
          dp.pop(64),
          xid,
          dp.pop(ONCRPC_INLINE_BYTES*8))
      case 3 => ???
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorPacketDescSimPcie(addr: BigInt, size: BigInt) extends PcieHostPacketDescSim with ErrorPacketDescSim {
  override def encode: BigInt = BigInt(0)
}
case class BypassPacketDescSimPcie(addr: BigInt, size: BigInt, packetType: BigInt, packetHdr: BigInt) extends PcieHostPacketDescSim with BypassPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(PKT_DESC_TY_WIDTH, packetType)
      .push(BYPASS_HDR_WIDTH, packetHdr, skip = 19)
      .toBigInt
  }
}
case class OncRpcCallPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt) extends PcieHostPacketDescSim with OncRpcCallPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(32, xid, skip = 21)
      .push(64, funcPtr)
      .push(BYPASS_HDR_WIDTH, args)
      .toBigInt
  }
}
