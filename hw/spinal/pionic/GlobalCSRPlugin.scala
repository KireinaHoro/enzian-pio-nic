package pionic

import jsteward.blocks.misc.CycleClock
import spinal.core._
import spinal.lib.CounterFreeRun
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

class GlobalCSRPlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val numCores = host.list[CoreControlPlugin].length
  val logic = during build new Area {
    val ctrl = new Bundle {
      val rxBlockCycles = Reg(UInt(config.regWidth bits)) init 10000
      val dispatchMask = Reg(Bits(numCores bits)) init ((1 << numCores) - 1)
    }
    val status = new Bundle {
      val cycles = CycleClock(config.regWidth bits)
      val rxOverflowCount = UInt(config.regWidth bits)
    }

    status.cycles.bits := CounterFreeRun(config.regWidth bits)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: String => BigInt): Unit = {
    logic.ctrl.elements.foreach { case (name, data) =>
      assert(data.isReg, "control CSR should always be register")
      busCtrl.readAndWrite(data, alloc(name))
    }
    logic.status.elements.foreach { case (name, data) =>
      busCtrl.read(data, alloc(name))
    }
  }
}
