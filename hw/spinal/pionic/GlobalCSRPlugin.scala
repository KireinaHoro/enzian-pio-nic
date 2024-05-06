package pionic

import jsteward.blocks.misc.CycleClock
import spinal.core._
import spinal.lib.CounterFreeRun
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps
import scala.collection.mutable

class GlobalCSRPlugin(implicit config: PioNicConfig) extends FiberPlugin {
  lazy val numCores = host.list[CoreControlPlugin].length
  val logic = during build new Area {
    val ctrl = new Bundle {
      val rxBlockCycles = Reg(UInt(config.regWidth bits)) init 10000
      val dispatchMask = Reg(Bits(numCores bits)) init ((1 << numCores) - 1)
    }
    val status = new Bundle {
      val version = Bits(config.regWidth bits)
      val cycles = CycleClock(config.regWidth bits)
      val rxOverflowCount = UInt(config.regWidth bits)
      val dispatchMaskChanged = Bool()
    }

    println(f"Git version: ${config.gitVersion}%x")
    status.version := B(config.gitVersion)
    status.cycles.bits := CounterFreeRun(config.regWidth bits)

    host[DebugPlugin].postDebug("cycles", status.cycles)
  }

  private val implRegs = new mutable.HashMap[String, (Data, Boolean)]
  private def roImplRegs = implRegs.filter(!_._2._2).map { case (n, (d, _)) => (n, d) }.iterator
  private def rwImplRegs = implRegs.filter(_._2._2).map { case (n, (d, _)) => (n, d) }.iterator
  def pushImplReg(name: String, data: Data, isWritable: Boolean = false) = {
    assert(!implRegs.contains(name), s"implementation-specific global reg $name already exists!")
    implRegs(name) = (data, isWritable)
  }

  def readAndWrite(busCtrl: BusSlaveFactory, alloc: String => BigInt): Unit = {
    (logic.ctrl.elements ++ rwImplRegs).foreach { case (name, data) =>
      assert(data.isReg, "control CSR should always be register")
      val addr = alloc(name)
      busCtrl.readAndWrite(data, addr)

      // special case: assert dispatchMaskChanged when dispatchMask is updated
      name == "dispatchMask" generate {
        val changed = False
        busCtrl.onWrite(addr) {
          changed := True
        }
        logic.status.dispatchMaskChanged := RegNext(changed)
      }
    }
    (logic.status.elements ++ roImplRegs).foreach { case (name, data) =>
      busCtrl.read(data, alloc(name))
    }
  }
}
