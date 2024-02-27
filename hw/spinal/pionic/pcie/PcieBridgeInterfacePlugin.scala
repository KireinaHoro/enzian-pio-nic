package pionic.pcie

import jsteward.blocks.axi._
import pionic.{CmacInterfacePlugin, PioNicConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class PcieBridgeInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val ci = host[CmacInterfacePlugin].logic.get

  val logic = during build new Area {
    val s_axi = slave(Axi4(config.axiConfig))

    val axiWideConfigNode = Axi4(config.axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(config.axiConfig.dataWidth)
    private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, config.pktBufSize, config.pktBufSize)(config.axiConfig.dataWidth)

    busCtrl.driveAndRead(ci.globalCtrl, alloc("ctrl"))
    busCtrl.driveAndRead(ci.dispatchMask, alloc("dispatchMask")) init (1 << config.numCores) - 1

    // global statistics
    busCtrl.read(ci.rxOverflowCounter.value, alloc("rxOverflowCount"))

    val cyclesCounter = CounterFreeRun(config.regWidth bits)
    ci.globalStatus.cyclesCount.bits := cyclesCounter
    busCtrl.read(cyclesCounter.value, alloc("cyclesCount")) // for host reference

    val pktBuffer = new AxiDpRam(config.axiConfig.copy(addressWidth = log2Up(config.pktBufSize)))
    ci.axiDma.io.m_axi >> pktBuffer.io.s_axi_a

    Axi4CrossbarFactory()
      .addSlaves(
        axiWideConfigNode -> (0x0, (config.numCores + 1) * 0x1000),
        pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), config.pktBufSize),
        // pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
      )
      .addConnections(
        s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b),
      )
      .build()

    ci.coreCtrls.zipWithIndex map { case (ctrl, id) =>
      ctrl.drivePcie(busCtrl, (1 + id) * 0x1000)
    }
  } setName ""
}
