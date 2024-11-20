package pionic

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/**
 * Descriptor used to describe a packet (payload of any protocol) in the packet buffer.
 *
 * This might get transmitted to the host (e.g. for [[pionic.host.pcie.PcieBridgeInterfacePlugin]], which directly
 * exposes the packet buffer to the host CPU for reading and writing), or might not be (e.g. for
 * [[pionic.host.eci.EciInterfacePlugin]], which aliases packet buf buffer to the same address)
 */
case class PacketBufDesc()(implicit c: ConfigDatabase) extends Bundle {
  override def clone = PacketBufDesc()

  val addr = PacketAddr()
  val size = PacketLength()

  assert(getBitsWidth <= 64, "packet buf desc size too big!")

  def addMackerel = {
    import Widths._
    c.f.addMackerelEpilogue(this.getClass,
      s"""
         |regtype host_pkt_buf_desc "PCIe Host Packet Buffer Descriptor" {
         |  valid 1   "TX descriptor valid (rsvd for RX)";
         |  addr  $aw "Address in packet buffer";
         |  size  $lw "Length of packet";
         |  _     ${63-aw-lw} rsvd;
         |};
         |""".stripMargin,
      target = "core"
    )
  }
}

case class PacketAlloc(base: Long, len: Long)(implicit c: ConfigDatabase) extends Component {
  val bufSizeMap = c[Seq[(Int, Double)]]("pkt buf alloc size map")

  val roundedMap = bufSizeMap.map { case (size, ratio) =>
    val alignedSize = roundUp(size, c[Int]("axis data width")).toLong
    val slots = (len * ratio / alignedSize).toInt
    (alignedSize, slots)
  }.filter(_._2 != 0)
  val numPorts = roundedMap.length

  val io = new Bundle {
    val allocReq = slave Stream PacketLength()
    // size == 0: dropped
    val allocResp = master Stream PacketBufDesc()
    val freeReq = slave Stream PacketBufDesc()

    // stats
    val slotOccupancy = out(Vec.fill(numPorts)(UInt(32 bits)))
  }

  assert(bufSizeMap.map(_._2).sum <= 1, "sum of packet categories exceed 1")
  assert(roundedMap.length == bufSizeMap.length, "some packet categories did not manage to get any slots")
  assert(log2Up(base + len) <= c[Int]("pkt buf addr width"), "packet buffer address bits overflow")
  println("==============")
  println(f"Allocator [$base%#x - ${base + len}%#x]")

  // return largest possible buffer if requested larger than everything
  val defaultIdx = U(numPorts - 1, log2Up(numPorts) bits)

  def sizeIdx(size: PacketLength) = roundedMap.map(_._1).zipWithIndex
    .foldRight(defaultIdx) { case ((alignedSize, idx), signal) =>
      Mux(size.bits <= alignedSize, idx, signal)
    }

  val inProgress: Bool = Reg(Bool()) init False
  inProgress := (io.allocReq.fire ## io.allocResp.fire) mux(
    b"10" -> True,
    b"01" -> False,
    default -> inProgress,
  )

  val freeDemux = StreamDemux(io.freeReq, sizeIdx(io.freeReq.payload.size), numPorts).setName("freeDemux")
  val allocRespMux = new StreamMux(PacketBufDesc(), numPorts).setName("allocRespMux")
  allocRespMux.io.output.haltWhen(!inProgress) >> io.allocResp

  val inIdx = io.allocReq.map(sizeIdx)
  inIdx.ready := !inProgress
  allocRespMux.io.select := inIdx.asFlow.toReg

  var curBase = base

  roundedMap.zipWithIndex foreach { case ((alignedSize, slots), idx) =>
    // round up slot size to streaming bus size to increase bus utilisation
    println(f"Rx Size $alignedSize: $slots slots @ $curBase%#x")

    // FIXME: what happens if try to allocate when empty?
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
    slotFifo.io.push << StreamArbiterFactory().lowerFirst.onArgs(freeDemux(idx).map(_.addr), initEnq)
    // pop only when we have a pending request
    slotFifo.io.pop.translateInto(allocRespMux.io.inputs(idx)) { (dst, src) =>
      dst.addr := src
      dst.size.bits := alignedSize
    }

    io.slotOccupancy(idx) := slotFifo.io.occupancy.resized
  }

  println("==============")
}
