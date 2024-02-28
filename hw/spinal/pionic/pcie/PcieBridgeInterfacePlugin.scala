package pionic.pcie

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class PcieBridgeInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  val logic = during setup new Area {
    val s_axi = slave(Axi4(config.axiConfig))
    awaitBuild()

    val axiWideConfigNode = Axi4(config.axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(config.axiConfig.dataWidth)
    private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, config.pktBufSize, config.pktBufSize)(config.axiConfig.dataWidth)

    csr.ctrl.elements.foreach { case (name, data) =>
      assert(data.isReg, "control CSR should always be register")
      busCtrl.readAndWrite(data, alloc(name))
    }
    csr.status.elements.foreach { case (name, data) =>
      busCtrl.read(data, alloc(name))
    }

    val pktBuffer = new AxiDpRam(config.axiConfig.copy(addressWidth = log2Up(config.pktBufSize)))

    Axi4CrossbarFactory()
      .addSlaves(
        axiWideConfigNode -> (0x0, (cores.length + 1) * 0x1000),
        pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), config.pktBufSize),
        // pktBuffer.io.s_axi_b -> (0x100000, pktBufferSize),
      )
      .addConnections(
        s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b),
      )
      .build()

    retainer.await()
    macIf.packetBufDmaMaster >> pktBuffer.io.s_axi_a
    cores foreach { c =>
      c.logic.get.ctrl.drivePcie(busCtrl, (1 + c.coreID) * 0x1000)
    }
  }
}
