package pionic.host.pcie

import jsteward.blocks.axi._
import jsteward.blocks.misc.RichStream
import pionic._
import pionic.host.HostService
import pionic.net.ProtoDecoder
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.bus.regif.AccessType.{RO, WO}
import spinal.lib.misc.plugin._

import scala.language.postfixOps

class PcieBridgeInterfacePlugin(implicit cc: ConfigDatabase) extends PioNicPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  // PCIe bridge block AXI config
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
  )

  val logic = during build new Area {
    val s_axi = slave(Axi4(axiConfig))

    val axiWideConfigNode = Axi4(axiConfig)
    val busCtrl = Axi4SlaveFactory(axiWideConfigNode.resize(regWidth))

    private val alloc = cc.f("global")(0, 0x1000, regWidth / 8)(axiConfig.dataWidth)
    csr.readAndWrite(busCtrl, alloc.toGeneric)

    private val pktBufferAlloc = cc.f("pkt")(0x100000, pktBufSize, pktBufSize)(axiConfig.dataWidth)

    // TODO: partition buffer for each core (and steer DMA writes) for max throughput
    val pktBuffer = new AxiDpRam(axiConfig.copy(addressWidth = log2Up(pktBufSize)))

    Axi4CrossbarFactory()
      .addSlaves(
        axiWideConfigNode -> (0x0, numCores * 0x1000),
        pktBuffer.io.s_axi_b -> (pktBufferAlloc("buffer"), pktBufSize),
      )
      .addConnection(s_axi -> Seq(axiWideConfigNode, pktBuffer.io.s_axi_b))
      .build()

    retainer.await()

    // FIXME: we could need an adapter here
    host[AxiDmaPlugin].packetBufDmaMaster >> pktBuffer.io.s_axi_a

    // drive control interface (packet action)
    cores foreach { c =>
      val baseAddress = (1 + c.coreID) * 0x1000
      val cio = c.logic.io

      val alloc = host[ConfigDatabase].f("core", c.coreID)(baseAddress, 0x1000, regWidth / 8)(axiConfig.dataWidth)

      val rxHostDesc = cio.hostRx.map(PcieHostCtrlInfo.packFrom)

      val hostDescSizeRound = roundUp(rxHostDesc.payload.getBitsWidth+1, 64) / 8
      postConfig("host desc size", hostDescSizeRound.toInt * 8, action = ConfigDatabase.OneShot)

      val rxAddr = alloc("hostRx",
        readSensitive = true,
        attr = RO,
        size = hostDescSizeRound,
        // TODO: what's the syntax for allowing multiple aliases for datatype reg?
        ty = "host_ctrl_info_error | host_ctrl_info_bypass | host_ctrl_info_onc_rpc_call")
      busCtrl.readStreamBlockCycles(rxHostDesc, rxAddr, csr.logic.ctrl.rxBlockCycles)

      busCtrl.driveStream(cio.hostRxAck.padSlave(1), alloc("hostRxAck",
        attr = WO,
        ty = "host_pkt_buf_desc"))

      // on read primitive (AR for AXI), set hostRxReq for timing ReadStart
      cio.hostRxReq := False
      busCtrl.onReadPrimitive(SingleMapping(rxAddr), haltSensitive = false, "read request issued") {
        cio.hostRxReq := True
      }

      // should not block; only for profiling (to use ready signal)
      busCtrl.readStreamNonBlocking(cio.hostTx, alloc("hostTx",
        readSensitive = true,
        attr = RO,
        ty = "host_pkt_buf_desc"))

      val txHostDesc = Stream(PcieHostCtrlInfo())
      busCtrl.driveStream(txHostDesc.padSlave(1), alloc("hostTxAck",
        attr = WO,
        size = hostDescSizeRound,
        // TODO: what's the syntax for allowing multiple aliases for datatype reg?
        ty = "host_ctrl_info_error | host_ctrl_info_bypass | host_ctrl_info_onc_rpc_call"))
      cio.hostTxAck.translateFrom(txHostDesc) { case (cc, h) =>
        h.unpackTo(cc)
      }

      c.logic.connectControl(busCtrl, alloc.toGeneric)
      c.logic.reportStatistics(busCtrl, alloc.toGeneric)
    }

    // control for the decoders
    host.list[ProtoDecoder[_]].foreach(_.driveControl(busCtrl, alloc.toGeneric))

    host[ProfilerPlugin].logic.reportTimestamps(busCtrl, alloc.toGeneric)
  }

  during build {
    PcieHostCtrlInfo().addMackerel
    PacketBufDesc().addMackerel
  }
}
