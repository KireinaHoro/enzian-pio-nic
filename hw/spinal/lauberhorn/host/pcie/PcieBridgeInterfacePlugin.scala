package lauberhorn.host.pcie

import jsteward.blocks.axi._
import jsteward.blocks.misc.RegAllocatorFactory.allocToGeneric
import lauberhorn._
import lauberhorn.net.Decoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import Global._
import spinal.lib.misc.database.Element.toValue
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

class PcieBridgeInterfacePlugin extends FiberPlugin {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val dps = host.list[PcieDatapathPlugin]

  // PCIe bridge block AXI config
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
  )
  PKT_BUF_ID_WIDTH.set(axiConfig.idWidth)

  val logic = during setup new Area {
    val s_axi = slave(Axi4(axiConfig))

    val axiWideConfigNode = Axi4(axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(REG_WIDTH))

    val pktBuffer = host[PacketBuffer].logic.axiMem

    private val alloc = ALLOC.get("global")(0, 0x1000, REG_WIDTH / 8)(axiConfig.dataWidth)
    csr.readAndWrite(busCtrl, alloc)

    private val pktBufferAlloc = ALLOC.get("pkt")(0x100000, PKT_BUF_SIZE.get, PKT_BUF_SIZE.get)(axiConfig.dataWidth)

    Axi4CrossbarFactory()
      .addSlaves(
        axiWideConfigNode -> (0x0, NUM_CORES * 0x1000),
        pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), PKT_BUF_SIZE.get),
      )
      .addConnection(s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b))
      .build()

    awaitBuild()

    // drive control interface (packet action)
    dps foreach { dp =>
      dp.driveDatapath(busCtrl, (1 + dp.coreID) * 0x1000, axiConfig.dataWidth)
    }

    // control for the decoders
    // host.list[Decoder[_]].foreach(_.driveControl(busCtrl, alloc))
    // host[ProfilerPlugin].logic.reportTimestamps(busCtrl, alloc)
    // host[Scheduler].driveControl(busCtrl, alloc)
    // host[Scheduler].reportStatistics(busCtrl, alloc)
    // host[DmaControlPlugin].logic.connectControl(busCtrl, alloc)
    // host[DmaControlPlugin].logic.reportStatistics(busCtrl, alloc)
  }

  during build {
    PcieHostCtrlInfo().addMackerel
    PacketBufDesc().addMackerel
  }
}
