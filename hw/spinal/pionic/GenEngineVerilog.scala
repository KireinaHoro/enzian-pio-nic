package pionic

import mainargs._
import pionic.host.eci._
import pionic.host.pcie._
import pionic.net._
import pionic.net.ethernet.{EthernetDecoder, EthernetEncoder}
import spinal.core.{FixedFrequency, IntToBuilder}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda.xilinx.VivadoConstraintWriter

import scala.language.postfixOps

object GenEngineVerilog {
  private def base = {
    Seq(
      new DebugPlugin,
      new ProfilerPlugin,
      new GlobalCSRPlugin,
      // packet decoder pipeline
      new XilinxCmacPlugin,
      new EthernetDecoder,
      new IpDecoder,
      new UdpDecoder,
      new OncRpcCallDecoder,
      new RxDecoderSink,
      // packet encoder pipeline
      new RawEncoder,
      new TxEncoderSource,
      // scheduler
      new Scheduler,
      // packet buffer & dma control
      new DmaControlPlugin,
      new PacketBuffer,
    )
  }

  def engine(nw: Int, variant: String) = {
    val nc = nw + 1
    val plugins = variant match {
      case "pcie" => base ++ Seq(new PcieBridgeInterfacePlugin) ++
        Seq.tabulate(nc)(new PcieDatapathPlugin(_)) ++
        Seq.tabulate(nw)(cid => new PciePreemptionControlPlugin(cid + 1))
      case "eci" => base ++ Seq(new EciInterfacePlugin) ++
        // TODO: only one DecoupledRxTxProtocol for bypass; numCores CoupledProtocol for RPC requests
        Seq.tabulate(nc)(new EciDecoupledRxTxProtocol(_)) ++
        Seq.tabulate(nw)(cid => new EciPreemptionControlPlugin(cid + 1))
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
      val e = engine(4, name)
      e.database on { Global.GIT_VERSION.set(gitVersion) }

      e
    }

    val host = report.toplevel.host
    report.mergeRTLSource("NicEngine_ips")
    VivadoConstraintWriter(report)
    // if (printRegMap) host[ConfigDatabase].f.dumpAll()
    if (genHeaders) {
      println("Generating headers and mackerel device files")
      // host[ConfigDatabase].f.writeMackerel(os.pwd / "sw" / "devices", s"pionic_$name")
      // host[ConfigDatabase].f.writeHeader(s"pionic_$name", genDir / "regblock_bases.h")
      // host[ConfigDatabase].writeConfigs(genDir / "config.h", elabConfig)
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
