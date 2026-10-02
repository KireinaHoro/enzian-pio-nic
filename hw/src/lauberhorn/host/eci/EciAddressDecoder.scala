package lauberhorn.host.eci

import spinal.core._
import spinal.lib._

/** Decode unaliased slice-relative addresses without rewriting them.
  *
  * Admission is for fresh host reads only. Writebacks and LCIA must instead
  * match retained accepted-transaction records; checking the current owner on
  * those paths would obstruct old-address cleanup after reassignment.
  */
case class EciAddressDecoder(workers: Int = EciAddressMap.default_workers) extends Component {
  require(workers >= 1 && workers <= EciAddressMap.max_workers)
  val io = new Bundle {
    // Preserve the full upstream width: reject bits outside the 38-bit aperture.
    val address = in UInt(64 bits)
    val owners = in Vec(UInt(EciAddressMap.domain_bits bits), workers)
    val enabled = in Bits(workers bits)
    val known = out Bool()
    val bypass = out Bool()
    val worker = out UInt((log2Up(workers) max 1) bits)
    val domain = out UInt(EciAddressMap.domain_bits bits)
    val offset = out UInt(13 bits)
    val tx = out Bool()
    val transportLine = out Bool()
    val admitted = out Bool()
  }
  EciAddressMap.domainPivots.zipWithIndex.foreach { case (pivot, bit) =>
    io.domain(bit) := io.address(pivot)
  }
  io.offset := io.address(12 downto 0)
  val block = io.address & U(BigInt("ffffffffffffe000", 16), 64 bits)
  val decodedBase = EciAddressMap.domainBasis.zipWithIndex.foldLeft(block) {
    case (value, (vector, bit)) => value ^ Mux(io.domain(bit), U(vector, 64 bits), U(0, 64 bits))
  }
  io.known := False
  io.bypass := io.address < EciAddressMap.block_bytes
  io.worker := 0
  val ownerMatches = Bool()
  ownerMatches := False
  for (w <- 0 until workers) {
    when(decodedBase === U(EciAddressMap.workerBases(w), 64 bits)) {
      io.known := True
      io.worker := w
      ownerMatches := io.enabled(w) && io.owners(w) === io.domain
    }
  }
  val rxLine = io.offset >= EciAddressMap.rx_control &&
    io.offset < EciAddressMap.rx_overflow + 128 * EciAddressMap.overflow_lines
  val txLine = io.offset >= EciAddressMap.tx_control &&
    io.offset < EciAddressMap.tx_overflow + 128 * EciAddressMap.overflow_lines
  io.tx := txLine
  // DCS AXI commands are whole, aligned cache lines. Packet inline bytes are
  // the second beat of those commands, not independently admitted addresses.
  io.transportLine := (rxLine || txLine) && io.address(6 downto 0) === 0
  io.admitted := io.transportLine && (io.bypass || (io.known && ownerMatches))
}
