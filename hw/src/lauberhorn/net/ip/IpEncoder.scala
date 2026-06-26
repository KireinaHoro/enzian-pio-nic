package lauberhorn.net.ip

import jsteward.blocks.axi.AxiStreamInjectHeader
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global.{NUM_NEIGHBOR_ENTRIES, REG_WIDTH}
import lauberhorn.{MacInterfaceService, PacketID, RxPacketDescWithSource}
import spinal.core._
import spinal.lib._
import lauberhorn.net.{DecoderOutput, DecoderSinkService, Encoder}
import lauberhorn.net.ethernet.{EthernetEncoder, EthernetTxMeta}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.bus.regif.AccessType
import spinal.lib.fsm._

import scala.language.postfixOps

class IpEncoder extends Encoder[IpTxMeta] {
  def getMetadata: IpTxMeta = IpTxMeta()

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    logic.neighborDb.update.setIdle()
    logic.neighborDb.update.value.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"Neighbor table update $name",
        s"neigh_$name", attr = AccessType.WO))
    }

    val idxAddr = alloc("ctrl", "Index of neighbor table entry to update",
      "neigh_idx", attr = AccessType.WO)
    busCtrl.write(logic.neighborDb.update.idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      logic.neighborDb.update.valid := True
    }

    busCtrl.read(logic.dropped.value, alloc("stat", "Number of packets dropped due to neighbor lookup failure",
      "dropped", attr = AccessType.RO))
    busCtrl.read(logic.neighTblFull.value, alloc("stat", "Number of times neighbor table became full (and entry 0 was overridden)",
      "neighTblFull", attr = AccessType.RO))

    val readbackIdxAddr = alloc("stat", "Index of neighbor table entry to read back",
      "neigh_readback_idx", attr = AccessType.WO)
    busCtrl.drive(logic.neighborDb.readbackIdx, readbackIdxAddr)
    logic.neighborDb.readback.elements.foreach { case (name, field) =>
      busCtrl.read(field, alloc("stat", s"Neighbor table readback $name",
        s"neigh_readback_$name", attr = AccessType.RO))
    }
  }

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(IpTxMeta())
    val pld = Axi4Stream(axisConfig)

    val outMd = Stream(EthernetTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[EthernetTxMeta, EthernetEncoder](outMd, outPld)
    outMd.setIdle()

    val neighborMissDesc = Stream(RxPacketDescWithSource())
    val neighborMissPld = Axi4Stream(axisConfig)
    val neighborMissPayloadAck = Bool()
    val neighborMissDropped = Bool()

    host[DecoderSinkService].consume(DecoderOutput(
      priority = 100,
      name = "IpEncoderNeighborMiss",
      desc = neighborMissDesc,
      pld = neighborMissPld,
      payloadAck = neighborMissPayloadAck,
      dropped = neighborMissDropped,
    ))

    neighborMissPld.setIdle()

    // TODO: take ingress IP packet events from decoder and update counter/timer
    //       to allow bypass core to refresh its timer

    awaitBuild()

    // Only enable IP packets from host during simulation
    collectInto(md, pld, acceptHostPackets = GenerationFlags.simulation.isEnabled)

    pld.setBlocked()

    // FIXME: does not support IP options yet
    val encoder = AxiStreamInjectHeader(axisConfig, IpHeader().getBitsWidth / 8)
    encoder.io.input.setIdle()
    encoder.io.header.setIdle()
    encoder.io.output >> outPld


    // We look up the destination MAC address from our neighbor table.
    // Neighbor table entries are in Big Endian
    val neighborDb = LookupTable(IpNeighborDef(), NUM_NEIGHBOR_ENTRIES) { v =>
      v.state init IpNeighborEntryState.none
    }

    // find a free entry in the neighbor table to track a new neighbor
    val (allocLookup, allocResult, _) = neighborDb.makePort(NoData(), NoData(), "allocNew") { (v, _, _) =>
      v.state === IpNeighborEntryState.none
    }
    allocLookup.valid := True
    allocResult.ready := True
    neighborMissDesc.setIdle()
    neighborMissDropped := False

    val (neighLookup, neighResult, neighLat) = neighborDb.makePort(Bits(32 bits), Bits(32 bits),
      "txLookup", singleMatch = true) { (v, q, _) =>
      v.state =/= IpNeighborEntryState.none && v.ipAddr === q
    }
    neighResult.setBlocked()
    val destMac = Reg(Bits(48 bits))

    md.translateInto(neighLookup) { case (lk, md) =>
      lk.query := md.daddr
      lk.userData := md.daddr
    }

    // upstream expected to fill out:
    // - destination address
    // - protocol of payload
    val nextIpHdr = IpHeader()
    nextIpHdr.daddr := md.daddr
    nextIpHdr.saddr := host[IpDecoder].logic.ipAddress
    nextIpHdr.proto := md.proto

    // we only send 20-byte headers
    nextIpHdr.len := EndiannessSwap(md.pldLen + 20).asBits
    nextIpHdr.ihl := 5

    nextIpHdr.version := 4
    nextIpHdr.tos := 0

    // we do not implement fragmentation
    nextIpHdr.id := 0
    nextIpHdr.flags := EndiannessSwap(B("16'x4000")) // DF, offset = 0

    nextIpHdr.ttl := 64
    nextIpHdr.csum := 0

    val savedIpHdr = Reg(IpHeader())
    val savedPacketId = Reg(UInt(PacketID.width bits))
    val savedPldLen = Reg(UInt(16 bits))
    val missIpAddr = Reg(Bits(32 bits))
    val missNeighTblIdx = Reg(UInt(log2Up(NUM_NEIGHBOR_ENTRIES) bits))
    val csumNext = nextIpHdr.calcCsum()
    val csumLat = LatencyAnalysis(nextIpHdr.ihl, csumNext)

    // cycle budget: lookup latency + idle -> sendDownstreamMd -> sendEncoderHdr
    val csumUsedIn = neighLat + 2
    // make sure header is fully saved before lookup result is back
    assert(csumLat <= csumUsedIn,
      s"IP checksum calculation will take $csumLat cycles, longer than required ($csumUsedIn cycles)")

    when (md.fire) {
      savedIpHdr := nextIpHdr
      savedPacketId := md.packetId
      savedPldLen := md.pldLen
    }
    when (Delay(md.fire, csumLat)) {
      savedIpHdr.csum := csumNext
    }

    // TODO: support default gateway i.e. lookup failed then send to gateway MAC address
    // TODO: some kind of differentiation between link-local and via default gateway (via subnet mask?)
    // val gatewayMacAddress = Reg(Bits(48 bits))

    // XXX: We don't implement ARP in hardware; the bypass core is expected to populate
    //      the neighbor table in software.  Packet will be dropped if an entry is not
    //      found in the neighbor table
    val dropped = Counter(REG_WIDTH bits)
    val neighTblFull = Counter(REG_WIDTH bits)

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          neighResult.ready := True
          when (neighResult.valid) {
            when (!neighResult.matched) {
              // matching entry not found:
              // - create incomplete entry
              // - notify bypass core
              neighborDb.update.valid := True
              neighborDb.update.idx := allocResult.idx
              neighborDb.update.value.state := IpNeighborEntryState.incomplete
              neighborDb.update.value.ipAddr := neighResult.userData

              // if the alloc lookup failed, all neighbor table slots are occupied;
              // allocResult.idx will be 0.  keep a counter for this case
              when (!allocResult.matched) {
                neighTblFull.increment()
              }

              missIpAddr := neighResult.userData
              missNeighTblIdx := allocResult.idx
              goto(sendNeighborMissDesc)
            } elsewhen (neighResult.value.state === IpNeighborEntryState.reachable) {
              // IP address REACHABLE in neighbor table:
              // - send IP header to encoder
              // - save neighbor-lookup result
              destMac := neighResult.value.macAddr
              goto(sendDownstreamMd)
            } otherwise {
              // IP address INCOMPLETE in neighbor table: deliver every missed
              // packet to the kernel slow path.
              missIpAddr := neighResult.userData
              missNeighTblIdx := neighResult.idx
              goto(sendNeighborMissDesc)
            }
          }
        }
      }
      val sendNeighborMissDesc: State = new State {
        whenIsActive {
          neighborMissDesc.valid := True
          neighborMissDesc.isBypass := True
          neighborMissDesc.desc.ty := lauberhorn.net.PacketDescType.neighborMiss
          neighborMissDesc.desc.metadata.assignDontCare()
          neighborMissDesc.desc.metadata.neighborMissRx.packetId := savedPacketId
          neighborMissDesc.desc.metadata.neighborMissRx.ipAddr := missIpAddr
          neighborMissDesc.desc.metadata.neighborMissRx.neighTblIdx := missNeighTblIdx
          neighborMissDesc.desc.metadata.neighborMissRx.saddr := savedIpHdr.saddr
          neighborMissDesc.desc.metadata.neighborMissRx.proto := savedIpHdr.proto
          neighborMissDesc.desc.metadata.neighborMissRx.payloadLen.bits := savedPldLen
          when (neighborMissDesc.ready) {
            when (savedPldLen === 0) {
              goto(idle)
            } otherwise {
              goto(sendNeighborMissPld)
            }
          }
        }
      }
      val sendNeighborMissPld: State = new State {
        whenIsActive {
          pld >> neighborMissPld
          when (pld.lastFire) {
            goto(idle)
          }
        }
      }
        val sendDownstreamMd: State = new State {
        whenIsActive {
          outMd.valid := True
          outMd.packetId := savedPacketId
          outMd.etherType := EndiannessSwap(B("16'x0800"))
          outMd.dst := destMac
          when (outMd.ready) {
            goto(sendEncoderHdr)
          }
        }
      }
      val sendEncoderHdr: State = new State {
        whenIsActive {
          encoder.io.header.valid := True
          encoder.io.header.payload := savedIpHdr.asBits
          when (encoder.io.header.ready) {
            goto(sendEncoderPld)
          }
        }
      }
      val sendEncoderPld: State = new State {
        whenIsActive {
          pld >> encoder.io.input
          when (pld.lastFire) {
            goto(idle)
          }
        }
      }
    }
  }
}
