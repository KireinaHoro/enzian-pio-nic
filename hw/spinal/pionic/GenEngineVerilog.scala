package pionic

import mainargs._
import pionic.eci.{EciDecoupledRxTxProtocol, EciInterfacePlugin}
import pionic.pcie.PcieBridgeInterfacePlugin
import spinal.lib.eda.xilinx.VivadoConstraintWriter
import spinal.lib.misc.plugin._

import scala.collection.mutable

// FIXME: use the more holistic Database approach
class ConfigWriter extends FiberPlugin {
  private val configs = mutable.HashMap[String, Any]()

  def postConfig[T](name: String, value: T): Unit = {
    configs += name -> value
  }

  def getConfig[T](name: String): T = {
    configs(name).asInstanceOf[T]
  }

  def writeConfigs(outPath: os.Path)(implicit config: PioNicConfig): Unit = {
    import config._
    os.remove(outPath)
    os.write(outPath,
      f"""|#ifndef __PIONIC_CONFIG_H__
          |#define __PIONIC_CONFIG_H__
          |
          |#define PIONIC_NUM_CORES $numCores
          |#define PIONIC_PKT_ADDR_WIDTH $pktBufAddrWidth
          |#define PIONIC_PKT_ADDR_MASK ((1 << PIONIC_PKT_ADDR_WIDTH) - 1)
          |#define PIONIC_PKT_LEN_WIDTH $pktBufLenWidth
          |#define PIONIC_PKT_LEN_MASK ((1 << PIONIC_PKT_LEN_WIDTH) - 1)
          |
          |#define PIONIC_CLOCK_FREQ ${Config.spinal().defaultClockDomainFrequency.getValue.toLong}
          |
            ${
        configs.map { case (k, v) =>
          s"|#define PIONIC_${k.toUpperCase.replace(' ', '_')} ($v)"
        }.mkString("\n")
      }
          |
          |#endif // __PIONIC_CONFIG_H__
          |""".stripMargin)
  }
}

object GenEngineVerilog {
  def base(implicit config: PioNicConfig) = Seq(
    new ConfigWriter,
    new GlobalCSRPlugin,
    new XilinxCmacPlugin,
  ) ++ Seq.tabulate(config.numCores)(new CoreControlPlugin(_))

  def engineFromName(name: String)(implicit config: PioNicConfig) = name match {
    case "pcie" => NicEngine(base :+ new PcieBridgeInterfacePlugin)
    case "eci" => NicEngine(base
      ++ Seq(new EciInterfacePlugin)
      ++ Seq.tabulate(config.numCores)(new EciDecoupledRxTxProtocol(_)))
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
      report.toplevel.host[ConfigWriter].writeConfigs(genDir / "config.h")
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
