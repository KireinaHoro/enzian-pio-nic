package pionic

import spinal.core._
import spinal.lib._

class PacketAlloc(base: Int, len: Int)(implicit config: PioNicConfig) extends Component {
  val io = new Bundle {
    val allocReq = slave Stream PacketLength()
    // size == 0: dropped
    val allocResp = master Stream PacketDesc()
    val freeReq = slave Stream PacketDesc()
  }

  assert(config.pktBufAllocSizeMap.map(_._2).sum <= 1, "sum of packet categories exceed 1")

  val numPorts = config.pktBufAllocSizeMap.length

  def sizeIdx(size: PacketLength) = config.pktBufAllocSizeMap.map(_._1).zipWithIndex
    .foldRight(U(numPorts - 1)) { case ((maxSize, idx), signal) =>
      Mux(size.bits <= maxSize, idx, signal)
    }

  val freeDemux = StreamDemux(io.freeReq, sizeIdx(io.freeReq.payload.size), numPorts)
  val allocRespMux = new StreamMux(PacketDesc(), numPorts)
  allocRespMux.io.output >> io.allocResp
  io.allocReq.map(sizeIdx) >> allocRespMux.io.createSelector()

  var curBase = base

  config.pktBufAllocSizeMap.zipWithIndex foreach { case ((size, ratio), idx) =>
    // round up slot size to streaming bus size to increase bus utilisation
    val alignedSize = roundUp(size, config.axisConfig.dataWidth).toInt

    val slots = (len * ratio / alignedSize).toInt
    val slotFifo = StreamFifo(PacketAddr(), slots)

    val initDone = RegInit(False)
    val remainingInit = Counter(0, slots, slotFifo.io.push.fire && !initDone)
    when(remainingInit.willOverflow) {
      initDone := True
    }

    val initEnq = Stream(PacketAddr())
    initEnq.payload.bits := (curBase + alignedSize * remainingInit.value).resized
    initEnq.valid := True
    curBase += alignedSize * slots

    // push from init and free request
    slotFifo.io.push << StreamMux(initDone.asUInt, Seq(initEnq, freeDemux(idx).map(_.addr)))
    slotFifo.io.pop.translateInto(allocRespMux.io.inputs(idx)) { (dst, src) =>
      dst.addr := src
      dst.size.bits := alignedSize
    }
  }
}
