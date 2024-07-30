package pionic.net

import spinal.core._

case class OncRpcCall() extends Bundle {
  val funcID = Bits(8 bits)
}
