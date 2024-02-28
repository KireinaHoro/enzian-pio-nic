package pionic

import mainargs._
import pionic.eci.EciInterfacePlugin
import pionic.pcie.PcieBridgeInterfacePlugin
import spinal.lib.eda.xilinx.VivadoConstraintWriter
import spinal.lib.misc.plugin._

object GenEngineVerilog {
  def base(implicit config: PioNicConfig) = Seq(
    new GlobalCSRPlugin,
    new XilinxCmacPlugin,
  ) ++ Seq.tabulate(config.numCores)(new CoreControlPlugin(_))

  def engineFromName(name: String)(implicit config: PioNicConfig) = name match {
    case "pcie" => NicEngine(base :+ new PcieBridgeInterfacePlugin)
    case "eci" => NicEngine(base :+ new EciInterfacePlugin)
  }

  @main
  def run(
           @arg(doc = "name of engine to generate")
           name: String,
           @arg(doc = "generate driver headers")
           genHeaders: Boolean = true,
           @arg(doc = "print register map")
           printRegMap: Boolean = true,
         ): Unit = {
    implicit val config = PioNicConfig()

    val genDir = os.pwd / os.RelPath(Config.outputDirectory) / name
    os.makeDir.all(genDir)

    val report = Config.spinal(genDir.toString).generateVerilog(engineFromName(name))
    report.mergeRTLSource("NicEngine_ips")
    VivadoConstraintWriter(report)
    if (printRegMap) config.allocFactory.dumpAll()
    if (genHeaders) {
      config.allocFactory.writeHeader("pionic", genDir / "regs.h")
      writeHeader(genDir / "config.h")
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
