package pionic.pcie

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import jsteward.blocks.axi._

import scala.language.postfixOps
import mainargs._
import pionic._
import spinal.lib.eda.xilinx.VivadoConstraintWriter

case class PcieEngine()(implicit val config: PioNicConfig) extends NicEngine
  with CmacInterface
  with PcieBridgeInterface

object GenEngineVerilog {
  implicit val config = new PioNicConfig

  @main
  def run(@arg(doc = "generate driver headers")
          genHeaders: Boolean = true,
          @arg(doc = "print register map")
          printRegMap: Boolean = true,
         ): Unit = {
    val report = Config.spinal.generateVerilog(PcieEngine())
    report.mergeRTLSource("PcieEngine_ips")
    VivadoConstraintWriter(report)
    if (printRegMap) config.allocFactory.dumpAll()
    if (genHeaders) {
      val commonGenDir = os.pwd / os.RelPath(Config.outputDirectory)
      val genDir = commonGenDir / "eci"
      os.makeDir.all(genDir)
      config.allocFactory.writeHeader("pionic", genDir / "regs.h")
      writeHeader(commonGenDir / "config.h")
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
