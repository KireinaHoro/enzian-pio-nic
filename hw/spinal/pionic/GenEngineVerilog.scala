package pionic

import mainargs._
import pionic.host.eci._
import pionic.host.pcie._
import pionic.net._
import spinal.core.{FixedFrequency, IntToBuilder}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda.xilinx.VivadoConstraintWriter

import scala.language.postfixOps

object GenEngineVerilog {
  private def base(c: ConfigDatabase) = {
    Seq(
      c,
      new DebugPlugin,
      new ProfilerPlugin,
      new GlobalCSRPlugin,
      // packet pipeline
      new XilinxCmacPlugin,
      new EthernetDecoder,
      new IpDecoder,
      new UdpDecoder,
      new OncRpcCallDecoder,
      new RxPacketDispatch,
      new AxiDmaPlugin,
    ) ++ Seq.tabulate(c[Int]("num cores") + 1)(new CoreControlPlugin(_))
  }

  def engine(implicit c: ConfigDatabase) = {
    val b = base(c)
    val plugins = c[String]("host interface") match {
      case "pcie" => b :+ new PcieBridgeInterfacePlugin
      // TODO: only one DecoupledRxTxProtocol for bypass; numCores CoupledProtocol for RPC requests
      case "eci" => b ++ Seq(new EciInterfacePlugin) ++ Seq.tabulate(c[Int]("num cores") + 1)(new EciDecoupledRxTxProtocol(_))
    }
    NicEngine(plugins)
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
    val gitVersion = version.map(_.asHex).getOrElse((BigInt(1) << 64) - 1)
    val genDir = os.pwd / os.RelPath(Config.outputDirectory) / name
    os.makeDir.all(genDir)

    val elabConfig = Config.spinal(genDir.toString).copy(
      defaultClockDomainFrequency = FixedFrequency(name match {
        case "pcie" => 250 MHz
        case "eci" => 250 MHz
      })
    )

    val report = elabConfig.generateVerilog {
      val c = new ConfigDatabase()
      c.post("host interface", name, emitHeader = false)
      c.post("git version", gitVersion, action = ConfigDatabase.Override, emitHeader = false)

      engine(c)
    }

    val host = report.toplevel.host
    report.mergeRTLSource("NicEngine_ips")
    VivadoConstraintWriter(report)
    if (printRegMap) host[ConfigDatabase].f.dumpAll()
    if (genHeaders) {
      println("Generating headers and mackerel device files")
      host[ConfigDatabase].f.writeMackerel(os.pwd / "sw" / "devices", s"pionic_$name")
      host[ConfigDatabase].f.writeHeader(s"pionic_$name", genDir / "regblock_bases.h")
      host[ConfigDatabase].writeConfigs(genDir / "config.h", elabConfig)
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
