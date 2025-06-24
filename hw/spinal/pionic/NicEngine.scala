package pionic

import jsteward.blocks.axi._
import spinal.core._
import spinal.core.fiber.Fiber.awaitPatch
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin._

class PatchSignalNames extends FiberPlugin {
  during build {
    awaitPatch()

    // strip plugin prefixes from IO ports
    Component.current.getAllIo.foreach { io =>
      val pattern = "^.*Plugin_logic_(.*)$".r
      for (pm <- pattern.findFirstMatchIn(io.getName())) {
        io.setName(pm.group(1))
      }
    }

    // rename ports so Vivado could infer interfaces automatically
    renameAxi4IO()
    renameAxi4StreamIO(alwaysAddT = true)
  }
}

class NicEngine extends Component {
  val database = new Database
  val host = database on new PluginHost
}