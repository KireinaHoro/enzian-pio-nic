package pionic.pcie

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._
import jsteward.blocks.axi._
import jsteward.blocks.misc.{Profiler, RegAllocatorFactory}

import scala.language.postfixOps
import mainargs._
import pionic._
import spinal.lib.eda.xilinx.VivadoConstraintWriter

case class PcieEngine()(implicit val config: PioNicConfig) extends CmacInterface {
  // allow second run of elaboration to work
  config.allocFactory.clear()

  val io = new Bundle {
    val s_axi = slave(Axi4(config.axiConfig))
  }

  val axiWideConfigNode = Axi4(config.axiConfig)
  val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

  private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(config.axiConfig.dataWidth)
  private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, config.pktBufSize, config.pktBufSize)(config.axiConfig.dataWidth)

  busCtrl.driveAndRead(globalCtrl, alloc("ctrl"))
  busCtrl.driveAndRead(dispatchMask, alloc("dispatchMask")) init (1 << config.numCores) - 1

  // global statistics
  busCtrl.read(rxOverflowCounter.value, alloc("rxOverflowCount"))

  val cyclesCounter = CounterFreeRun(config.regWidth bits)
  globalStatus.cyclesCount.bits := cyclesCounter
  busCtrl.read(cyclesCounter.value, alloc("cyclesCount")) // for host reference

  Axi4CrossbarFactory()
    .addSlaves(
      axiWideConfigNode -> (0x0, (config.numCores + 1) * 0x1000),
      pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), config.pktBufSize),
      // pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
    )
    .addConnections(
      io.s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b),
    )
    .build()

  coreCtrls.zipWithIndex map { case (ctrl, id) =>
    ctrl.drivePcie(busCtrl, (1 + id) * 0x1000)
  }

  // rename ports so Vivado could infer interfaces automatically
  noIoPrefix()
  addPrePopTask { () =>
    renameAxi4IO
    renameAxi4StreamIO(alwaysAddT = true)
  }
}

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
      val genDir = os.pwd / os.RelPath(Config.outputDirectory) / "pcie"
      os.makeDir.all(genDir)
      config.allocFactory.writeHeader("pionic", genDir / "regs.h")
      writeHeader(genDir / "config.h")
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
