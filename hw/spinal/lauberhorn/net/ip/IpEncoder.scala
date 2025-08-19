package lauberhorn.net.ip

import jsteward.blocks.axi.AxiStreamInjectHeader
import lauberhorn.MacInterfaceService
import spinal.core._
import spinal.lib._
import lauberhorn.net.Encoder
import lauberhorn.net.ethernet.{EthernetEncoder, EthernetRxMeta, EthernetTxMeta}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream
import spinal.lib.fsm._

import scala.language.postfixOps

class IpEncoder extends Encoder[IpTxMeta] {
  def getMetadata: IpTxMeta = IpTxMeta()

  lazy val axisConfig = host[MacInterfaceService].axisConfig

  val logic = during setup new Area {
    val md = Stream(IpTxMeta())
    val pld = Axi4Stream(axisConfig)

    awaitBuild()

    collectInto(md, pld, acceptHostPackets = true)
    md.setBlocked()
    pld.setBlocked()

    // FIXME: does not support IP options yet
    val encoder = AxiStreamInjectHeader(axisConfig, IpHeader().getBitsWidth / 8)
    encoder.io.input.setIdle()
    encoder.io.header.setIdle()

    // The previous stage can be:
    //  - from the CPU over bypass
    //  - from another encoder stage (e.g. UDP)
    // Neither will supply us with information on Ethernet (i.e. ethMeta is empty).
    // We look up the destination MAC address from our neighbor table.
    val outMd = Stream(EthernetTxMeta())
    to[EthernetTxMeta, EthernetEncoder](outMd, encoder.io.output)
    outMd.setIdle()

    // XXX: We don't implement ARP in hardware; the host is expected to populate
    //      the neighbor table in software.
    //      Packet will be dropped if an entry is not found in the neighbor table
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          md.ready := True
          when (md.valid) {
            when (False) {
              // IP address in neighbor table:
              // - send IP header to encoder
              // - save neighbor-lookup result

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
            goto(idle)
          }
        }
      }
      val sendDownstreamMd: State = new State {
        whenIsActive {
          outMd.valid := True
          outMd.payload
          when (outMd.ready) {
            goto(sendEncoderHdr)
          }
        }
      }
      val sendEncoderHdr: State = new State {
        whenIsActive {

        }
      }
      val sendEncoderPld: State = new State {
        whenIsActive {

        }
      }
    }
  }
}
