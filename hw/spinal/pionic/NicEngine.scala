package pionic

import jsteward.blocks.axi._
import spinal.core._

abstract class NicEngine extends Component {
  implicit val config: PioNicConfig

  // allow second run of elaboration to work
  config.allocFactory.clear()

  // rename ports so Vivado could infer interfaces automatically
  noIoPrefix()
  addPrePopTask { () =>
    renameAxi4IO
    renameAxi4StreamIO(alwaysAddT = true)
  }
}
