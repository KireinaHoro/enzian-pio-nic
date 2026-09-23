package lauberhorn.net.oncrpc

import jsteward.blocks.axi.AxiStreamInjectHeader
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._

/** The shared injector requires a nonempty payload beat. RPC void calls and
  * accepted errors instead consist solely of their header. Serialize both
  * paths through packet completion, preserving metadata/payload order. */
case class RpcHeaderInjector(config: Axi4StreamConfig, headerBytes: Int) extends Component {
  require(headerBytes <= config.dataWidth)
  val io = new Bundle {
    val header = slave(Stream(Bits(headerBytes * 8 bits)))
    val empty = in Bool()
    val input = slave(Axi4Stream(config))
    val output = master(Axi4Stream(config))
  }
  val injector = AxiStreamInjectHeader(config, headerBytes)
  val savedHeader = Reg(Bits(headerBytes * 8 bits))
  io.header.setBlocked()
  io.input.setBlocked()
  io.output.setIdle()
  injector.io.header.setIdle()
  injector.io.input.setIdle()
  injector.io.output.setBlocked()
  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        io.header.ready := True
        when(io.header.fire) {
          savedHeader := io.header.payload
          when(io.empty) { goto(headerOnly) } otherwise { goto(injectHeader) }
        }
      }
    }
    val headerOnly: State = new State {
      whenIsActive {
        io.output.valid := True
        io.output.data := savedHeader.resize(config.dataWidth * 8) |<< ((config.dataWidth - headerBytes) * 8)
        io.output.keep := B(((BigInt(1) << headerBytes) - 1) << (config.dataWidth - headerBytes), config.dataWidth bits)
        io.output.last := True
        when(io.output.fire) { goto(idle) }
      }
    }
    val injectHeader: State = new State {
      whenIsActive {
        injector.io.header.valid := True
        injector.io.header.payload := savedHeader
        when(injector.io.header.fire) { goto(packet) }
      }
    }
    val packet: State = new State {
      whenIsActive {
        injector.io.input << io.input
        io.output << injector.io.output
        when(io.output.lastFire) { goto(idle) }
      }
    }
  }
}
