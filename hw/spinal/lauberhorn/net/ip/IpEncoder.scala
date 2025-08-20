package lauberhorn.net.ip

import jsteward.blocks.axi.AxiStreamInjectHeader
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.Global.{NUM_NEIGHBOR_ENTRIES, REG_WIDTH}
import lauberhorn.MacInterfaceService
import spinal.core._
import spinal.lib._
import lauberhorn.net.Encoder
import lauberhorn.net.ethernet.{EthernetEncoder, EthernetRxMeta, EthernetTxMeta}
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
      busCtrl.drive(field, alloc("ctrl", s"neigh_$name", attr = AccessType.WO))
    }

    val idxAddr = alloc("ctrl", "neigh_idx", attr = AccessType.WO)
    busCtrl.write(logic.neighborDb.update.idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      logic.neighborDb.update.valid := True
    }

    busCtrl.read(logic.dropped.value, alloc("stat", "dropped", attr = AccessType.RO))
  }

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(IpTxMeta())
    val pld = Axi4Stream(axisConfig)

    val outMd = Stream(EthernetTxMeta())
    val outPld = Axi4Stream(axisConfig)
    to[EthernetTxMeta, EthernetEncoder](outMd, outPld)
    outMd.setIdle()

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true)
    pld.setBlocked()

    // FIXME: does not support IP options yet
    val encoder = AxiStreamInjectHeader(axisConfig, IpHeader().getBitsWidth / 8)
    encoder.io.input.setIdle()
    encoder.io.header.setIdle()
    encoder.io.output >> outPld

    // We look up the destination MAC address from our neighbor table.
    // Neighbor table entries are in Big Endian
    val neighborDb = LookupTable(IpNeighborDef(), NUM_NEIGHBOR_ENTRIES) { v =>
      v.valid init False
    }

    val (neighLookup, neighResult, _) = neighborDb.makePort(Bits(32 bits), NoData()) { (v, q, _) =>
      v.valid && v.ipAddr === q
    }
    neighResult.setBlocked()
    val destMac = Reg(Bits(48 bits))

    md.translateInto(neighLookup) { case (lk, md) =>
      lk.query := md.daddr
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
    when (md.fire) {
      savedIpHdr.csum := nextIpHdr.calcCsum()
      savedIpHdr.assignUnassignedByName(nextIpHdr)
    }

    // TODO: support default gateway i.e. lookup failed then send to gateway MAC address
    // TODO: some kind of differentiation between link-local and via default gateway (via subnet mask?)
    // val gatewayMacAddress = Reg(Bits(48 bits))

    // XXX: We don't implement ARP in hardware; the bypass core is expected to populate
    //      the neighbor table in software.  Packet will be dropped if an entry is not
    //      found in the neighbor table
    val dropped = Counter(REG_WIDTH bits)

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          neighResult.ready := True
          when (neighResult.valid) {
            when (neighResult.matched) {
              // IP address in neighbor table:
              // - send IP header to encoder
              // - save neighbor-lookup result
              destMac := neighResult.value.macAddr
              goto(sendDownstreamMd)
            } otherwise {
              // IP address not in neighbor table:
              // - drop packet payload
              // - increment counter
              goto(dropPld)
            }
          }
        }
      }
      val dropPld: State = new State {
        whenIsActive {
          pld.ready := True
          when (pld.lastFire) {
            dropped.increment()
            goto(idle)
          }
        }
      }
      val sendDownstreamMd: State = new State {
        whenIsActive {
          outMd.valid := True
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
