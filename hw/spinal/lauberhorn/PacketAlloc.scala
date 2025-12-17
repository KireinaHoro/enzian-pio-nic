package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import scala.language.postfixOps

import Global._
import jsteward.blocks.eci.EciCmdDefs.ECI_CL_SIZE_BYTES

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

  def addMackerel() = {
    ALLOC.addMackerelEpilogue(
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
    // Align to ECI: 64 + 128 * n
    val alignedSize = roundUp(size - 64, ECI_CL_SIZE_BYTES).toInt + 64
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

  io.allocReq.assertPersistence()
  io.freeReq.assertPersistence()
  io.allocResp.assertPersistence()

  io.allocReq.setBlocked()
  io.freeReq.setBlocked()
  io.allocResp.setIdle()

  // return largest possible buffer if requested larger than everything
  val defaultIdx = U(numPorts - 1, log2Up(numPorts+1) bits)
  def sizeIdx(size: PacketLength) = roundedMap.map(_._1).zipWithIndex
    .foldRight(defaultIdx) { case ((alignedSize, idx), signal) =>
      Mux(size.bits <= alignedSize, idx, signal)
    }

  val allocRespMux = new StreamMux(PacketBufDesc(), numPorts)
  val allocSel = Reg(UInt(log2Up(numPorts) bits)) init 0
  allocRespMux.io.select := allocSel
  allocRespMux.io.output.setBlocked()

  // the state machine limits allocation II=2; ok since DMA state machine is slower
  val allocFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        io.allocReq.ready := True
        when (io.allocReq.valid) {
          assert(io.allocReq.bits =/= 0, "allocator only handles non-zero sizes!")
          allocSel := sizeIdx(io.allocReq)
          goto(popResp)
        }
      }
    }
    val popResp = new State {
      whenIsActive {
        allocRespMux.io.output >> io.allocResp
        when (io.allocResp.fire) {
          goto(idle)
        }
      }
    }
  }

  var curBase = base

  roundedMap.zipWithIndex foreach { case ((alignedSize, slots), idx) => new Area {
    // round up slot size to streaming bus size to increase bus utilisation
    println(f"Rx Size $alignedSize: $slots slots @ $curBase%#x")

    // FIXME: what happens if try to allocate when empty?
    val slotFifo = StreamFifo(PacketAddr(), slots)

    slotFifo.io.push.setIdle()
    slotFifo.io.push.assertPersistence()

    val remainingInit = Counter(slots)
    val myBase = curBase
    curBase += alignedSize * slots

    val freeFsm = new StateMachine {
      val init = new State with EntryPoint {
        whenIsActive {
          slotFifo.io.push.valid := True
          slotFifo.io.push.bits := (myBase + alignedSize * remainingInit.value).resized
          when (slotFifo.io.push.ready) {
            remainingInit.increment()
            when (remainingInit.willOverflow) {
              goto(ready)
            }
          }
        }
      }
      val ready = new State {
        whenIsActive {
          when (io.freeReq.size.bits === alignedSize) {
            io.freeReq.map(_.addr) >> slotFifo.io.push
          }
          assert(!slotFifo.io.push.isStall, "free channel should not stall after init!")
        }
      }
    }

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
    }.setName(s"simChecks_$alignedSize")
  }.setName(s"size_$alignedSize") }

  println("==============")
}
