package pionic

import jsteward.blocks.misc.{CycleClock, RegBlockAlloc}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps
import Global._
import spinal.lib.misc.plugin.FiberPlugin

class GlobalCSRPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ctrl = new Bundle {
      val rxBlockCycles = Reg(UInt(REG_WIDTH bits)) init 10000
      val promisc = Reg(Bool()) init False

      val preemptCritSecTimeout = Reg(UInt(REG_WIDTH bits)) init 100000 // 400 us @ 250 MHz
      val preemptCoreIDOffset = Reg(UInt(REG_WIDTH bits)) init 0
    }
    val status = new Bundle {
      val version = Bits(REG_WIDTH bits)
      val cycles = CycleClock(REG_WIDTH bits)
      val rxOverflowCount = UInt(REG_WIDTH bits)
      val rxSchedDroppedCount = UInt(REG_WIDTH bits)
    }

    println(f"Git version: ${GIT_VERSION.get}%x")
    status.version := B(GIT_VERSION)
    status.cycles.bits := CounterFreeRun(REG_WIDTH bits)

    host[DebugPlugin].postDebug("cycles", status.cycles)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.ctrl.elements.foreach { case (name, data) =>
      val addr = alloc(name)
      if (name == "workerCoreMask") {
        busCtrl.driveFlow(data.asInstanceOf[Flow[Bits]], addr)
      } else {
        assert(data.isReg, "control CSR should always be register")
        busCtrl.readAndWrite(data, addr)
      }
    }
    logic.status.elements.foreach { case (name, data) =>
      busCtrl.read(data, alloc(name, attr = RO))
    }
  }
}
