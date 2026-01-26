package lauberhorn.host.eci

import spinal.core._
import spinal.lib.misc.plugin._
import spinal.lib.fsm._
import spinal.lib._

import jsteward.blocks.eci.TracePort
import lauberhorn.Global.REG_WIDTH

import scala.language.postfixOps

case class TraceEvent() extends Bundle {
  val event = TracePort()
  val src = UInt(2 bits)
  val ts = UInt(REG_WIDTH bits) // valid is implied if ts != 0
}

/** Capture traces from DCS interfaces in a buffer and stream them out to ILA
  * on trigger from VIO.  Also records timestamps of each trace event.
  */
class DcsTraceBuffer(numSlots: Int = 256) extends FiberPlugin {
  val logic = during build new Area {
    val tracePorts = host[EciInterfacePlugin].logic.dcsIntfs.flatMap(_.tracing)
    println(s"DCS trace buffer: ${tracePorts.length} ports, $numSlots buffer entries")
    assert(TraceEvent().src.getBitsWidth >= log2Up(tracePorts.length), "trace buffer source field cannot represent all sources")

    val cycleCount = CounterFreeRun(REG_WIDTH bits)
    val storage = Mem(TraceEvent(), numSlots)

    val dump = in(Bool())
    val data = out(TraceEvent())
    val sampleLost = out(RegInit(False))
    data.clearAll()

    val dumpAddr, captureAddr = Counter(numSlots)
    val readoutFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          dumpAddr.clearAll()
          when (dump.rise(False)) {
            goto(readout)
          }
        }
      }
      val readout = new State {
        whenIsActive {
          data := storage(dumpAddr)
          dumpAddr.increment()
          when (dumpAddr.willOverflow) {
            goto(idle)
            sampleLost.clear()
          }
        }
      }
    }

    // add delay to simplify PNR
    val allPorts = Vec(tracePorts.map { p =>
      DelayWithInit(p, 3) { dp => dp.valid init False }
    })

    val catPorts = Flow(allPorts)
    catPorts.valid := allPorts.map(_.valid).orR
    catPorts.payload zip allPorts foreach { case (cp, p) => cp := p }

    // use a small FIFO to resolve conflicts, and also keep a flag when we overflow
    val overflow = Bool()
    val bufferedPorts = catPorts.toStream(overflow, 8, 8)
    bufferedPorts.ready := False
    sampleLost.setWhen(overflow)

    val savedPorts = bufferedPorts.toFlowFire.toReg()
    savedPorts.foreach { sp => sp.valid init False }
    val nextPort = OHToUInt(OHMasking.first(savedPorts.map(_.valid)))

    // create one write port for both init and capture
    val writeData = CombInit(savedPorts(nextPort).map { p =>
      val ret = TraceEvent()
      ret.event := p
      ret.src := nextPort
      ret.ts := cycleCount
      ret
    })
    val writeEn = CombInit(False)
    storage.write(captureAddr, writeData, writeEn)

    val captureFsm = new StateMachine {
      val init: State = new State with EntryPoint {
        whenIsActive {
          writeEn := True
          writeData.clearAll()
          captureAddr.increment()
          when (captureAddr.willOverflow) {
            goto(idle)
          }
        }
      }
      val idle: State = new State {
        whenIsActive {
          bufferedPorts.ready := True
          when (bufferedPorts.valid) {
            goto(captureEvent)
          }
        }
      }
      val captureEvent = new State {
        whenIsActive {
          writeEn := True
          captureAddr.increment()
          savedPorts(nextPort).valid := False
          when (CountOne(savedPorts.map(_.valid)) === 1) {
            // that was the last one, we're done
            goto(idle)
          }
        }
      }
    }
  }.setName("trace")
}
