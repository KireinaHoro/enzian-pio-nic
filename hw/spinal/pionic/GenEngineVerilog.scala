package pionic

import mainargs._
import pionic.host.eci.{EciDecoupledRxTxProtocol, EciInterfacePlugin}
import pionic.host.pcie.PcieBridgeInterfacePlugin
import spinal.core.{FixedFrequency, IntToBuilder, SpinalConfig}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda.xilinx.VivadoConstraintWriter
import spinal.lib.misc.plugin._

import scala.collection.mutable
import scala.language.postfixOps

// FIXME: use the more holistic Database approach
class ConfigWriter extends FiberPlugin {
  private val configs = mutable.HashMap[String, Any]()

  def postConfig[T](name: String, value: T): Unit = {
    configs += name -> value
  }

  def getConfig[T](name: String): T = {
    configs(name).asInstanceOf[T]
  }

  def writeConfigs(outPath: os.Path, spinalConfig: SpinalConfig)(implicit config: PioNicConfig): Unit = {
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
          |#define PIONIC_CLOCK_FREQ ${spinalConfig.defaultClockDomainFrequency.getValue.toLong}
          |
          |#define PIONIC_MTU $mtu
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
    new AxiDmaPlugin,
    new XilinxCmacPlugin,
    new DebugPlugin,
    new ProfilerPlugin,
  ) ++ Seq.tabulate(config.numCores)(new CoreControlPlugin(_))

  def engineFromName(name: String)(implicit config: PioNicConfig) = name match {
    case "pcie" => NicEngine(base :+ new PcieBridgeInterfacePlugin)
    case "eci" => NicEngine(base
      ++ Seq(new EciInterfacePlugin)
      // TODO: add one DecoupledRxTxProtocol for bypass and numCores CoupledProtocol for RPC requests
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
           @arg(doc = "git version (for embedding as CSR)")
           version: Option[String],
         ): Unit = {
    implicit val config = PioNicConfig(gitVersion = version.map(_.asHex).getOrElse((BigInt(1) << 64) - 1))

    val genDir = os.pwd / os.RelPath(Config.outputDirectory) / name
    os.makeDir.all(genDir)

    val elabConfig = Config.spinal(genDir.toString).copy(
      defaultClockDomainFrequency = FixedFrequency(name match {
        case "pcie" => 250 MHz
        case "eci" => 250 MHz
      })
    )
    val report = elabConfig.generateVerilog(engineFromName(name))
    report.mergeRTLSource("NicEngine_ips")
    VivadoConstraintWriter(report)
    if (printRegMap) config.allocFactory.dumpAll()
    if (genHeaders) {
      config.allocFactory.writeHeader("pionic", genDir / "regs.h")
      report.toplevel.host[ConfigWriter].writeConfigs(genDir / "config.h", elabConfig)
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
