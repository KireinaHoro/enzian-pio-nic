package pionic.eci

import jsteward.blocks.eci.{DcsInterface, EciWord}
import mainargs._
import pionic._
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.eda.xilinx.VivadoConstraintWriter
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class EciInterfacePlugin(implicit val config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  val retainer = Retainer()

  val logic = during build new Area {
    val dcsOdd = DcsInterface(config.axiConfig)
    val dcsEven = DcsInterface(config.axiConfig)

    val wordWidth = config.axiConfig.dataWidth
    val numWords = config.pktBufSize / (wordWidth / 8)
    val pktBuffer = Mem(Bits(wordWidth bits), numWords)

    // connect CmacInterface packet DMA
    val dmaAxiNode = Axi4(config.axiConfig)
    val dmaAxiCtrl = Axi4SlaveFactory(dmaAxiNode)
    dmaAxiCtrl.readSyncMemWordAligned(pktBuffer, 0)
    dmaAxiCtrl.writeMemWordAligned(pktBuffer, 0)

    // pktBuffer.readWriteSync()
  }
}