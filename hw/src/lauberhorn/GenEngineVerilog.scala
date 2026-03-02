package lauberhorn

import mainargs._
import lauberhorn.Global._
import lauberhorn.host.BypassCmdSink
import lauberhorn.host.eci._
import lauberhorn.host.pcie._
import lauberhorn.net._
import lauberhorn.net.ethernet.{EthernetDecoder, EthernetEncoder}
import lauberhorn.net.ip.{IpDecoder, IpEncoder}
import lauberhorn.net.oncrpc.{OncRpcCallDecoder, OncRpcReplyEncoder}
import lauberhorn.net.udp.{UdpDecoder, UdpEncoder}
import spinal.core.{FixedFrequency, IntToBuilder}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda._
import spinal.lib.eda.xilinx.TimingExtractorXdc

import jsteward.blocks.eci.EciChannel
import jsteward.blocks.misc.TraceBuffer

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
      new OncRpcReplyEncoder,
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
        case "eci" => Seq(new EciInterfacePlugin, new EciThreadClRouter, new DcsTraceBuffer) ++
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
           @arg(doc = "output directory")
           outDir: String,
           @arg(doc = "generate driver headers")
           genHeaders: Boolean = true,
           @arg(doc = "print register map")
           printRegMap: Boolean = true,
           @arg(doc = "git version (for embedding as CSR)")
           version: Option[String],
         ): Unit = {
    val gitVersion = version.map(_.asHex).getOrElse((BigInt(1) << 64) - 1)
    val elabConfig = Config.spinal(outDir)
    val report = elabConfig.generateVerilog {
      val e = engine(4, name)
      e.database on { Global.GIT_VERSION.set(gitVersion) }
      e
    }

    val out = os.Path(outDir)

    report.toplevel.database on {
      ALLOC.dumpAll()
      if (genHeaders) {
        println("Writing mackerel device files")
        ALLOC.writeMackerel(os.pwd / "sw" / "devices", s"lauberhorn_$name")

        println("Writing register address map")
        ALLOC.writeHeader(s"lauberhorn_$name", out / "regblock_bases.h")

        println("Writing configs in database")
        writeConfigs(out / "config.h", elabConfig)
      }
    }
    println("Writing merged RTL sources for all IPs")
    report.mergeRTLSource("NicEngine_ips")

    // use .sv suffix for IPs
    os.move.over(out / "NicEngine_ips.v", out / "NicEngine_ips.sv")

    println("Writing timing constraints for Vivado")
    TimingExtractor(report, new TimingExtractorXdc)

    // write trace buffer module
    if (name == "eci") {
      // DCS trace buffer
      Config.spinal(outDir, prefix = "dtb_").generateVerilog {
        // 6 channels:
        // req_wod_slave, rsp_wod_slave, rsp_wd_slave
        // rsp_wod_master, rsp_wd_master, fwd_wod_master
        // we only log the header (no CL contents)
        TraceBuffer(EciChannel(), 6, 512).setDefinitionName("dcs_eci_buf")
      }
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
