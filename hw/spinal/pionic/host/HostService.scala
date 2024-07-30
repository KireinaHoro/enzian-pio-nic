package pionic.host

import spinal.core.fiber.Retainer

trait HostService {
  def retainer: Retainer
}
