package pionic

package object pcie {
  def writeHeader(outPath: os.Path)(implicit config: PioNicConfig): Unit = {
    import config._
    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_PCIE_CONFIG_H__
          |#define __PIONIC_PCIE_CONFIG_H__
          |
          |#define PIONIC_NUM_CORES $numCores
          |#define PIONIC_PKT_ADDR_WIDTH $pktBufAddrWidth
          |#define PIONIC_PKT_ADDR_MASK ((1 << PIONIC_PKT_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_LEN_WIDTH $pktBufLenWidth
          |#define PIONIC_PKT_LEN_MASK ((1 << PIONIC_PKT_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${Config.spinal.defaultClockDomainFrequency.getValue.toLong}
          |
          |#endif // __PIONIC_PCIE_CONFIG_H__
          |""".stripMargin)
  }
}
