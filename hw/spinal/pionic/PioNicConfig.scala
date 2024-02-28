package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axis.Axi4StreamConfig

case class PioNicConfig(
                         axiConfig: Axi4Config = Axi4Config(
                           addressWidth = 64,
                           dataWidth = 512,
                           idWidth = 4,
                         ),
                         axisConfig: Axi4StreamConfig = Axi4StreamConfig(
                           dataWidth = 64, // BYTES
                           useKeep = true,
                           useLast = true,
                         ),
                         pktBufAddrWidth: Int = 24,
                         pktBufLenWidth: Int = 16, // max 64KB per packet
                         pktBufSizePerCore: Int = 64 * 1024, // 64KB
                         maxRxPktsInFlight: Int = 128,
                         numCores: Int = 4,
                         // for Profiling
                         collectTimestamps: Boolean = true,
                         timestampWidth: Int = 32, // width for a single timestamp
                         pktBufAllocSizeMap: Seq[(Int, Double)] = Seq(
                           (128, .1),
                           (1518, .3), // max Ethernet frame with MTU 1500
                           (9618, .6), // max jumbo frame
                         ),
                         regWidth: Int = 64,
                       ) {
  def pktBufAddrMask = (BigInt(1) << pktBufAddrWidth) - BigInt(1)
  def pktBufLenMask = (BigInt(1) << pktBufLenWidth) - BigInt(1)
  def pktBufSize = numCores * pktBufSizePerCore

  def mtu = pktBufAllocSizeMap.map(_._1).max
  def roundMtu = roundUp(mtu, axisConfig.dataWidth).toInt

  def coreIDWidth = log2Up(numCores)

  val allocFactory = new RegAllocatorFactory
}
