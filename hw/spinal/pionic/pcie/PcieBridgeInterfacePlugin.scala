package pionic.pcie

import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class PcieBridgeInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  val logic = during setup new Area {
    val s_axi = slave(Axi4(config.axiConfig))
    awaitBuild()

    val axiWideConfigNode = Axi4(config.axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(config.regWidth))

    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(config.axiConfig.dataWidth)
    csr.readAndWrite(busCtrl, alloc(_))

    private val pktBufferAlloc = config.allocFactory("pkt")(0x100000, config.pktBufSize, config.pktBufSize)(config.axiConfig.dataWidth)

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

    // drive control interface (packet action)
    cores foreach { c =>
      val baseAddress = (1 + c.coreID) * 0x1000
      val cio = c.logic.ctrl.io

      val alloc = config.allocFactory("control", c.coreID)(baseAddress, 0x1000, config.regWidth / 8)(config.axiConfig.dataWidth)

      val rxNextAddr = alloc("hostRxNext", readSensitive = true)
      busCtrl.readStreamBlockCycles(cio.hostRxNext, rxNextAddr, cio.rxBlockCycles)
      busCtrl.driveStream(cio.hostRxNextAck, alloc("hostRxNextAck"))

      // on read primitive (AR for AXI), set hostRxNextReq for timing ReadStart
      cio.hostRxNextReq := False
      busCtrl.onReadPrimitive(SingleMapping(rxNextAddr), haltSensitive = false, "read request issued") {
        cio.hostRxNextReq := True
      }

      // should not block; only for profiling (to use ready signal)
      busCtrl.readStreamNonBlocking(cio.hostTx, alloc("hostTx", readSensitive = true))
      busCtrl.driveStream(cio.hostTxAck, alloc("hostTxAck"))

      busCtrl.driveAndRead(cio.allocReset, alloc("allocReset")) init false

      cio.statistics.elements.foreach { case (name, data) =>
        data match {
          case d: UInt => busCtrl.read(d, alloc(name))
          case v: Vec[_] => v zip config.pktBufAllocSizeMap.map(_._1) foreach { case (elem, slotSize) =>
            busCtrl.read(elem, alloc(name, subName = s"upTo$slotSize"))
          }
          case _ =>
        }
      }

      // rx profile results
      if (config.collectTimestamps) {
        cio.hostRxLastProfile.storage.foreach { case (namedType, data) =>
          busCtrl.read(data, alloc("hostRxLastProfile", subName = namedType.getName()))
        }
        cio.hostTxLastProfile.storage.foreach { case (namedType, data) =>
          busCtrl.read(data, alloc("hostTxLastProfile", subName = namedType.getName()))
        }
      }
    }
  }
}
