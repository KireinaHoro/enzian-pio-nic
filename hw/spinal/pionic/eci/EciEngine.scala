package pionic.eci

import jsteward.blocks.eci.{DcsInterface, EciWord}
import mainargs._
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.eda.xilinx.VivadoConstraintWriter

import scala.language.postfixOps

case class EciEngine()(implicit val config: PioNicConfig) extends NicEngine with CmacInterface {
  val dcsOdd = DcsInterface(config.axiConfig)
  val dcsEven = DcsInterface(config.axiConfig)

  // TODO: wire up CmacInterface
}

object GenEngineVerilog {
  implicit val config = new PioNicConfig

  @main
  def run(@arg(doc = "generate driver headers")
          genHeaders: Boolean = true,
          @arg(doc = "print register map")
          printRegMap: Boolean = true,
         ): Unit = {
    val report = Config.spinal.generateVerilog(EciEngine())
    report.mergeRTLSource("EciEngine_ips")
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