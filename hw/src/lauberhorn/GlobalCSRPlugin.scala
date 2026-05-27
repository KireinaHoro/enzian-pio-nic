package lauberhorn

import jsteward.blocks.misc.RegBlockAlloc
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
    }
    val status = new Bundle {
      val gitVersion = Bits(REG_WIDTH bits)
      val magic = Bits(REG_WIDTH bits)
      val cycles = UInt(REG_WIDTH bits)
    }

    println(f"Git version: ${GIT_VERSION.get}%x")
    status.gitVersion := B(GIT_VERSION)
    // "LBERHORN" in hex
    status.magic := B("64'x4C424552484F524E")
    status.cycles := CounterFreeRun(REG_WIDTH bits)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.ctrl.elements.foreach { case (name, data) =>
      val addr = alloc("csr", name)
      if (name == "workerCoreMask") {
        busCtrl.driveFlow(data.asInstanceOf[Flow[Bits]], addr)
      } else {
        assert(data.isReg, "control CSR should always be register")
        busCtrl.readAndWrite(data, addr)
      }
    }
    logic.status.elements.foreach { case (name, data) =>
      val desc = name match {
        case "gitVersion" => "Git version of HW"
        case "magic" => "Magic number to check (should be LBERHORN)"
        case "cycles" => s"Cycle counter @ ${component.clockDomain.frequency.getValue} MHz"
        case _ => s"Status CSR $name"
      }
      busCtrl.read(data, alloc("csr", desc, name, attr = RO))
    }
  }
}
