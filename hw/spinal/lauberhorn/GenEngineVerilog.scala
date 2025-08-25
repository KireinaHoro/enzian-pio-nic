package lauberhorn

import mainargs._
import lauberhorn.Global._
import lauberhorn.host.BypassCmdSink
import lauberhorn.host.eci._
import lauberhorn.host.pcie._
import lauberhorn.net._
import lauberhorn.net.ethernet.{EthernetDecoder, EthernetEncoder}
import lauberhorn.net.ip.{IpDecoder, IpEncoder}
import lauberhorn.net.oncrpc.OncRpcCallDecoder
import lauberhorn.net.udp.{UdpDecoder, UdpEncoder}
import spinal.core.{FixedFrequency, IntToBuilder}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda._
import spinal.lib.eda.xilinx.TimingExtractorXdc

import scala.language.postfixOps

object GenEngineVerilog {
  private def base = {
    Seq(
      new PatchSignalNames,
      new DebugPlugin,
      new ProfilerPlugin,

      // packet decoder pipeline
      new XilinxCmacPlugin,
      new EthernetDecoder,
      new IpDecoder,
      new UdpDecoder,
      new OncRpcCallDecoder,
      new DecoderSink,

      // packet encoder pipeline
      new EthernetEncoder,
      new IpEncoder,
      new UdpEncoder,
      new EncoderSource,

      // scheduler
      new Scheduler,
      new BypassCmdSink,

      // packet buffer & dma control
      new DmaControlPlugin,
      new PacketBuffer,
    )
  }

  def engine(nw: Int, variant: String) = {
    val nc = nw + 1
    val e = new NicEngine
    val plugins = e.database on {
      initDatabase(nc, nw)

      base ++ (variant match {
        case "pcie" => Seq(new PcieBridgeInterfacePlugin) ++
          Seq.tabulate(nc)(new PcieDatapathPlugin(_)) ++
          Seq.tabulate(nw)(cid => new PciePreemptionControlPlugin(cid + 1))
        case "eci" => Seq(new EciInterfacePlugin) ++
          // TODO: only one DecoupledRxTxProtocol for bypass; numCores CoupledProtocol for RPC requests
          Seq.tabulate(nc)(new EciDecoupledRxTxProtocol(_)) ++
          Seq.tabulate(nw)(cid => new EciPreemptionControlPlugin(cid + 1))
      })
    }

    e.host.asHostOf(plugins)
    e
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

    report.toplevel.database on {
      ALLOC.dumpAll()
      if (genHeaders) {
        println("Writing mackerel device files")
        ALLOC.writeMackerel(os.pwd / "sw" / "devices", s"lauberhorn_$name")

        println("Writing register address map")
        ALLOC.writeHeader(s"lauberhorn_$name", genDir / "regblock_bases.h")

        println("Writing configs in database")
        writeConfigs(genDir / "config.h", elabConfig)
      }
    }
    println("Writing merged RTL sources for all IPs")
    report.mergeRTLSource("NicEngine_ips")

    println("Writing timing constraints for Vivado")
    TimingExtractor(report, new TimingExtractorXdc)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
