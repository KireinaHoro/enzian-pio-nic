package pionic

import spinal.lib._

package object sim {
  def isSorted[T](s: Seq[T])(implicit ord: Ordering[T]): Boolean = s match {
    case Nil => true
    case x :: Nil => true
    case x :: xs => ord.lteq(x, xs.head) && isSorted(xs)
  }

  implicit class PacketDescBytesRicher(lb: List[Byte])(implicit config: PioNicConfig) {
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
      PacketDescSim.fromBigInt(lb.bytesToBigInt)
    }
  }

  case class PacketDescSim(addr: BigInt, size: BigInt)(implicit config: PioNicConfig) {
    def toBigInt: BigInt = ((size & config.pktBufLenMask) << config.pktBufAddrWidth) | (addr & config.pktBufAddrMask)
  }

  object PacketDescSim {
    def fromBigInt(v: BigInt)(implicit config: PioNicConfig) = PacketDescSim(v & config.pktBufAddrMask, (v >> config.pktBufAddrWidth) & config.pktBufLenMask)
  }
}
