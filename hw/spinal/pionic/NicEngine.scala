package pionic

import jsteward.blocks.axi._
import spinal.core._
import spinal.lib.misc.plugin._

class NicEngine extends Component {
  val host = new PluginHost
}

object NicEngine {
  def apply(plugins: Seq[Hostable])(implicit config: PioNicConfig): NicEngine = {
    config.allocFactory.clear()

    val n = new NicEngine
    n.host.asHostOf(plugins :+ new FiberPlugin {
      during build {
        plugins.map(_.asInstanceOf[FiberPlugin].postBuildLock).foreach(_.await())

        // rename ports so Vivado could infer interfaces automatically
        renameAxi4IO()
        renameAxi4StreamIO(n, alwaysAddT = true)
      }
    })
    n
  }
}