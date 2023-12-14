package pionic

import spinal.core._
import spinal.lib._

case class PacketAlloc(base: Long, len: Long)(implicit config: PioNicConfig) extends Component {
  val io = new Bundle {
    val allocReq = slave Stream PacketLength()
    // size == 0: dropped
    val allocResp = master Stream PacketDesc()
    val freeReq = slave Stream PacketDesc()
  }

  assert(config.pktBufAllocSizeMap.map(_._2).sum <= 1, "sum of packet categories exceed 1")
  assert(log2Up(base + len) <= config.pktBufAddrWidth, "packet buffer address bits overflow")
  println("==============")
  println(f"Allocator [$base%#x - ${base + len}%#x]")

  val numPorts = config.pktBufAllocSizeMap.length

  def roundSize(sz: Long) = roundUp(sz, config.axisConfig.dataWidth).toLong

  def sizeIdx(size: PacketLength) = config.pktBufAllocSizeMap.map(_._1).zipWithIndex
    .foldRight(U(numPorts)) { case ((maxSize, idx), signal) =>
      Mux(size.bits <= roundSize(maxSize), idx, signal)
    }

  val inProgress: Bool = Reg(Bool()) init False
  inProgress := (io.allocReq.fire ## io.allocResp.fire) mux(
    b"10" -> True,
    b"01" -> False,
    default -> inProgress,
  )

  val freeDemux = StreamDemux(io.freeReq, sizeIdx(io.freeReq.payload.size), numPorts).setName("freeDemux")
  val allocRespMux = new StreamMux(PacketDesc(), numPorts).setName("allocRespMux")
  allocRespMux.io.output.haltWhen(!inProgress) >> io.allocResp

  val inIdx = io.allocReq.map(sizeIdx)
  inIdx.ready := !inProgress
  allocRespMux.io.select := inIdx.asFlow.toReg

  var curBase = base

  config.pktBufAllocSizeMap.zipWithIndex foreach { case ((size, ratio), idx) =>
    // round up slot size to streaming bus size to increase bus utilisation
    val alignedSize = roundSize(size)
    val slots = (len * ratio / alignedSize).toInt
    println(f"Size $alignedSize: $slots slots @ $curBase%#x")

    val slotFifo = StreamFifo(PacketAddr(), slots)

    val initDone = RegInit(False).setWeakName("initDone")
    val remainingInit = Counter(0, slots - 1, slotFifo.io.push.fire && !initDone).setWeakName("remainingInit")
    when(remainingInit.willOverflow) {
      initDone := True
    }

    val initEnq = Stream(PacketAddr())
    initEnq.payload.bits := (curBase + alignedSize * remainingInit.value).resized
    initEnq.valid := !initDone
    curBase += alignedSize * slots

    // FIXME: we could use a Mux and the initDone signal for less area (but slower startup)
    slotFifo.io.push << StreamArbiterFactory.lowerFirst.onArgs(freeDemux(idx).map(_.addr), initEnq)
    // pop only when we have a pending request
    slotFifo.io.pop.translateInto(allocRespMux.io.inputs(idx)) { (dst, src) =>
      dst.addr := src
      dst.size.bits := alignedSize
    }
  }

  println("==============")
}
