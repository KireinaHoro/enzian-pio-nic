package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import mainargs._
import pionic.Global._
import pionic.host.eci._
import pionic.host.pcie._
import pionic.net._
import pionic.net.ethernet.EthernetDecoder
import spinal.core.{FixedFrequency, IntToBuilder, roundUp}
import spinal.lib.BinaryBuilder2
import spinal.lib.eda.xilinx.VivadoConstraintWriter

import scala.language.postfixOps

object GenEngineVerilog {
  private def base = {
    Seq(
      new PatchSignalNames,
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
    val e = new NicEngine
    e.database on {
      // set enough parameters to get us rolling
      val bufSizeMap = Seq(
        128  -> .1,
        1518 -> .3, // max Ethernet frame with MTU 1500
        9618 -> .6, // max jumbo frame
      )
      PKT_BUF_ALLOC_SIZES.set(bufSizeMap)
      PKT_BUF_ADDR_WIDTH.set(24)
      PKT_BUF_LEN_WIDTH.set(16)
      PKT_BUF_RX_SIZE_PER_CORE.set(64 * 1024)
      PKT_BUF_TX_SIZE_PER_CORE.set(1024)

      DATAPATH_WIDTH.set(64)
      MTU.set(bufSizeMap.map(_._1).max)
      ROUNDED_MTU.set(roundUp(MTU.get, DATAPATH_WIDTH.get).toInt)

      NUM_CORES.set(nc)
      NUM_WORKER_CORES.set(nw)
      REG_WIDTH.set(64)
      PID_WIDTH.set(16)
      NUM_SERVICES.set(256)
      NUM_PROCS.set(32)
      RX_PKTS_PER_PROC.set(32)
      BYPASS_HDR_WIDTH.set(54 * 8) // ETH + IP + TCP

      ALLOC.set(new RegAllocatorFactory)

      val plugins = base ++ (variant match {
        case "pcie" => Seq(new PcieBridgeInterfacePlugin) ++
          Seq.tabulate(nc)(new PcieDatapathPlugin(_)) ++
          Seq.tabulate(nw)(cid => new PciePreemptionControlPlugin(cid + 1))
        case "eci" => Seq(new EciInterfacePlugin) ++
          // TODO: only one DecoupledRxTxProtocol for bypass; numCores CoupledProtocol for RPC requests
          Seq.tabulate(nc)(new EciDecoupledRxTxProtocol(_)) ++
          Seq.tabulate(nw)(cid => new EciPreemptionControlPlugin(cid + 1))
      })

      e.host.asHostOf(plugins)
    }
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
        println("Generating headers and mackerel device files")
        ALLOC.writeMackerel(os.pwd / "sw" / "devices", s"pionic_$name")
        ALLOC.writeHeader(s"pionic_$name", genDir / "regblock_bases.h")
        writeConfigs(genDir / "config.h", elabConfig)
      }
    }
    report.mergeRTLSource("NicEngine_ips")
    VivadoConstraintWriter(report)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
