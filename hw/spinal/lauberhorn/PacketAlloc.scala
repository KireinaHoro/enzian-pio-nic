package lauberhorn

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

import Global._

/**
 * Descriptor used to describe a packet (payload of any protocol) in the packet buffer.
 *
 * This might get transmitted to the host (e.g. for [[lauberhorn.host.pcie.PcieBridgeInterfacePlugin]], which directly
 * exposes the packet buffer to the host CPU for reading and writing), or might not be (e.g. for
 * [[lauberhorn.host.eci.EciInterfacePlugin]], which aliases packet buf buffer to the same address)
 */
case class PacketBufDesc() extends Bundle {
  override def clone = PacketBufDesc()

  val addr = PacketAddr()
  val size = PacketLength()

  assert(getBitsWidth <= 64, "packet buf desc size too big!")

  def addMackerel = {
    ALLOC.addMackerelEpilogue(this.getClass,
      s"""
         |regtype host_pkt_buf_desc "PCIe Host Packet Buffer Descriptor" {
         |  valid 1   "TX descriptor valid (rsvd for RX)";
         |  addr  $PKT_BUF_ADDR_WIDTH "Address in packet buffer";
         |  size  $PKT_BUF_LEN_WIDTH "Length of packet";
         |  _     ${63-PKT_BUF_ADDR_WIDTH-PKT_BUF_LEN_WIDTH} rsvd;
         |};
         |""".stripMargin,
      target = "core"
    )
  }
}

case class PacketAlloc(base: Long, len: Long) extends Component {
  val roundedMap = PKT_BUF_ALLOC_SIZES.map { case (size, ratio) =>
    val alignedSize = roundUp(size, DATAPATH_WIDTH.get).toInt
    val slots = (len * ratio / alignedSize).toInt
    (alignedSize, slots)
  }.filter(_._2 != 0)
  val numPorts = roundedMap.length

  val io = new Bundle {
    val allocReq = slave Stream PacketLength()
    val allocResp = master Stream PacketBufDesc()
    val freeReq = slave Stream PacketBufDesc()

    // stats
    val slotOccupancy = out(Vec.fill(numPorts)(UInt(32 bits)))
  }

  assert(PKT_BUF_ALLOC_SIZES.map(_._2).sum <= 1, "sum of packet categories exceed 1")
  assert(roundedMap.length == PKT_BUF_ALLOC_SIZES.length, "some packet categories did not manage to get any slots")
  assert(log2Up(base + len) <= PKT_BUF_ADDR_WIDTH, "packet buffer address bits overflow")
  println("==============")
  println(f"Allocator [$base%#x - ${base + len}%#x]")

  // return largest possible buffer if requested larger than everything
  val defaultIdx = U(numPorts - 1, log2Up(numPorts+1) bits)

  // when size is 0, select the imaginary FIFO for size 0 at index numPorts
  def sizeIdx(size: PacketLength) = Mux(size.bits === 0, U(numPorts),
    roundedMap.map(_._1).zipWithIndex
      .foldRight(defaultIdx) { case ((alignedSize, idx), signal) =>
        Mux(size.bits <= alignedSize, idx, signal)
      })

  val inProgress: Bool = Reg(Bool()) init False
  inProgress := (io.allocReq.fire ## io.allocResp.fire) mux(
    b"10" -> True,
    b"01" -> False,
    default -> inProgress,
  )

  val freeReqNoZeroes = io.freeReq.throwWhen(io.freeReq.size.bits === 0)
  val freeDemux = StreamDemux(
    freeReqNoZeroes,
    sizeIdx(freeReqNoZeroes.payload.size),
    numPorts).setName("freeDemux")
  val allocRespMux = new StreamMux(PacketBufDesc(), numPorts+1).setName("allocRespMux")
  allocRespMux.io.output.haltWhen(!inProgress) >> io.allocResp

  // for requests with length zero, always return a zero-length buffer at the beginning of buffer
  allocRespMux.io.inputs(numPorts).valid := True
  allocRespMux.io.inputs(numPorts).addr.bits := base
  allocRespMux.io.inputs(numPorts).size.bits := 0

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

    val myBase = curBase
    curBase += alignedSize * slots

    // FIXME: we could use a Mux and the initDone signal for less area (but slower startup)
    slotFifo.io.push << StreamArbiterFactory(s"allocFifo_push_$alignedSize").lowerFirst.onArgs(freeDemux(idx).map(_.addr), initEnq)
    // pop only when we have a pending request
    slotFifo.io.pop.translateInto(allocRespMux.io.inputs(idx)) { (dst, src) =>
      dst.addr := src
      dst.size.bits := alignedSize
    }

    io.slotOccupancy(idx) := slotFifo.io.occupancy.resized

    // simulation-only checks
    GenerationFlags simulation new Area {
      val slotOccupied = Vec(Reg(Bool()), slots)
      slotOccupied foreach {
        _.init(True)
      }

      val pushAddr = slotFifo.io.push.payload.bits
      val pushIdx = ((pushAddr - myBase) / alignedSize).resize(log2Up(slots))
      when(slotFifo.io.push.fire) {
        assert(slotOccupied(pushIdx),
          s"size $alignedSize: slot not occupied but tried to free")
        slotOccupied(pushIdx) := False

        assert(pushAddr >= myBase,
          s"size $alignedSize: pushing addr smaller than base")
        assert(pushAddr < curBase,
          s"size $alignedSize: pushing addr bigger than limit")
        assert((pushAddr - myBase) % alignedSize === 0,
          s"size $alignedSize: pushing addr not aligned")
      }

      val popAddr = slotFifo.io.pop.payload.bits
      val popIdx = ((popAddr - myBase) / alignedSize).resize(log2Up(slots))
      when(slotFifo.io.pop.fire) {
        assert(!slotOccupied(popIdx),
          s"size $alignedSize: slot already occupied")
        slotOccupied(popIdx) := True
      }
    }
  }

  println("==============")
}
