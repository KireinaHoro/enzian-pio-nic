package pionic

import jsteward.blocks.misc.{CycleClock, RegBlockAlloc}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps

class GlobalCSRPlugin extends PioNicPlugin {
  val logic = during build new Area {
    val ctrl = new Bundle {
      val rxBlockCycles = Reg(UInt(regWidth bits)) init 10000
      val promisc = Reg(Bool()) init False

      val preemptCritSecTimeout = Reg(UInt(regWidth bits)) init 100000 // 400 us @ 250 MHz
      val preemptCoreIDOffset = Reg(UInt(regWidth bits)) init 0
    }
    val status = new Bundle {
      val version = Bits(regWidth bits)
      val cycles = CycleClock(regWidth bits)
      val rxOverflowCount = UInt(regWidth bits)
      val rxSchedDroppedCount = UInt(regWidth bits)
    }

    val gitVersion = c[BigInt]("git version")
    println(f"Git version: ${gitVersion}%x")
    status.version := B(gitVersion)
    status.cycles.bits := CounterFreeRun(regWidth bits)

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
