package pionic

import jsteward.blocks.misc.CycleClock
import spinal.core._
import spinal.lib.CounterFreeRun
import spinal.lib.bus.misc.BusSlaveFactory

import scala.language.postfixOps

class GlobalCSRPlugin extends PioNicPlugin {
  val logic = during build new Area {
    val ctrl = new Bundle {
      val rxBlockCycles = Reg(UInt(regWidth bits)) init 10000
      val promisc = Reg(Bool()) init False
    }
    val status = new Bundle {
      val version = Bits(regWidth bits)
      val cycles = CycleClock(regWidth bits)
      val rxOverflowCount = UInt(regWidth bits)
    }

    val gitVersion = c[BigInt]("git version")
    println(f"Git version: ${gitVersion}%x")
    status.version := B(gitVersion)
    status.cycles.bits := CounterFreeRun(regWidth bits)

    host[DebugPlugin].postDebug("cycles", status.cycles)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: String => BigInt): Unit = {
    logic.ctrl.elements.foreach { case (name, data) =>
      assert(data.isReg, "control CSR should always be register")
      val addr = alloc(name)
      busCtrl.readAndWrite(data, addr)
    }
    logic.status.elements.foreach { case (name, data) =>
      busCtrl.read(data, alloc(name))
    }
  }
}
