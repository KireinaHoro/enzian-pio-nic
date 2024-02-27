package pionic

import jsteward.blocks.axi._
import spinal.core._
import spinal.lib.misc.plugin._

class NicEngine extends Component {
  val host = new PluginHost

  // rename ports so Vivado could infer interfaces automatically
  noIoPrefix()
  addPrePopTask { () =>
    renameAxi4IO
    renameAxi4StreamIO(alwaysAddT = true)
  }
}

object NicEngine {
  def apply(plugins: Seq[Hostable])(implicit config: PioNicConfig): NicEngine = {
    config.allocFactory.clear()

    val n = new NicEngine
    n.host.asHostOf(plugins)
    n
  }
}