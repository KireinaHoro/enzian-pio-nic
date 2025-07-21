package lauberhorn

import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable

/**
 * Make signals available at the top level for inspection with e.g. ILA.
 */
class DebugPlugin extends FiberPlugin {
  def postDebug(name: String, data: Data) = {
    CombInit(data).setName(name).asOutput()
  }
}
