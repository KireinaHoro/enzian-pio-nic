package pionic

import spinal.lib.bus.amba4.axi.sim.Axi4Master
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master

trait AsSimBusMaster[B] {
  def read(b: B, addr: BigInt, totalBytes: BigInt): List[Byte]

  def write(b: B, addr: BigInt, data: List[Byte]): Unit
}

object AsSimBusMaster {
  implicit val axiliteAsMaster = new AsSimBusMaster[AxiLite4Master] {
    def read(b: AxiLite4Master, addr: BigInt, totalBytes: BigInt) = b.read(addr, totalBytes)

    def write(b: AxiLite4Master, addr: BigInt, data: List[Byte]) = b.write(addr, data)
  }
  implicit val axiAsMaster = new AsSimBusMaster[Axi4Master] {
    def read(b: Axi4Master, addr: BigInt, totalBytes: BigInt) = b.read(addr, totalBytes)

    def write(b: Axi4Master, addr: BigInt, data: List[Byte]) = b.write(addr, data)
  }
}