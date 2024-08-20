package pionic.host

import spinal.lib._
import pionic._
import jsteward.blocks.misc.sim.BigIntRicher
import spinal.core.IntToBuilder

package object pcie {
  implicit class PacketDescBytesRicher(lb: List[Byte])(implicit c: ConfigDatabase) {
    // with status bit
    def toRxPacketDesc = {
      val d = lb.bytesToBigInt.toLong
      if ((d & 1) == 0) {
        None
      } else {
        Some(PacketDescSim.fromBigInt(d >> 1))
      }
    }

    def toTxPacketDesc = {
      PacketDescSim.fromBigInt(lb.bytesToBigInt >> 1)
    }
  }

  sealed abstract class HostPacketDescSim(val addr: BigInt, val size: BigInt) {
    def parseData(lb: BigInt)(implicit config: PioNicConfig): HostPacketDescSim
  }
  object HostPacketDescSim {
    def fromBits(lb: List[Byte])(implicit config: PioNicConfig): HostPacketDescSim = {
      val b = lb.bytesToBigInt
      val addr = b(config.pktBufAddrWidth downto 0)
      val size = b(config.pktBufAddrWidth + config.pktBufLenWidth downto config.pktBufAddrWidth)
      val ty = b()
    }
  }
  case class HostDescBypassSim(addr: BigInt, size: BigInt) extends HostPacketDescSim(addr, size) {

  }

  case class PacketDescSim(addr: BigInt, size: BigInt)(implicit config: PioNicConfig) {
    def toBigInt: BigInt = ((size & config.pktBufLenMask) << config.pktBufAddrWidth) | (addr & config.pktBufAddrMask)
  }

  object PacketDescSim {
    def fromBigInt(v: BigInt)(implicit config: PioNicConfig) = PacketDescSim(v & config.pktBufAddrMask, (v >> config.pktBufAddrWidth) & config.pktBufLenMask)
  }
}
