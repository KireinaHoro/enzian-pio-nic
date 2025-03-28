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
      // mask for all non-bypass cores
      val workerCoreMask = Reg(Bits(numWorkerCores bits))
    }
    val status = new Bundle {
      val version = Bits(regWidth bits)
      val cycles = CycleClock(regWidth bits)
      val rxOverflowCount = UInt(regWidth bits)
    }
    
    // used to notify scheduler that core mask changed
    val workerCoreMaskFlow = Flow(ctrl.workerCoreMask)
    workerCoreMaskFlow.payload := ctrl.workerCoreMask
    workerCoreMaskFlow.valid := False

    val gitVersion = c[BigInt]("git version")
    println(f"Git version: ${gitVersion}%x")
    status.version := B(gitVersion)
    status.cycles.bits := CounterFreeRun(regWidth bits)

    host[DebugPlugin].postDebug("cycles", status.cycles)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.ctrl.elements.foreach { case (name, data) =>
      assert(data.isReg, "control CSR should always be register")
      val addr = alloc(name)
      busCtrl.readAndWrite(data, addr)
      
      if (name == "workerCoreMask") {
        busCtrl.onWrite(addr) {
          logic.workerCoreMaskFlow.valid := True
        }
      }
    }
    logic.status.elements.foreach { case (name, data) =>
      busCtrl.read(data, alloc(name, attr = RO))
    }
  }
}
