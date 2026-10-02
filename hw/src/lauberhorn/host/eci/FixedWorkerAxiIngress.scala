package lauberhorn.host.eci

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

/** Captures admission and original addresses before the routing crossbar.
  * Address on the internal bus is only a physical endpoint selector. ARUSER and
  * AWUSER carry the immutable unaliased address to the protocol endpoint.
  * One outstanding transaction of each kind per DCS ID is required by the DCS.
  */
case class FixedWorkerAxiIngress(config: Axi4Config, workers: Int) extends Component {
  require(config.addressWidth == FixedWorkerMap.address_bits)
  require(config.useId && config.useLast)
  require(!config.useArUser && !config.useAwUser)
  val routedConfig = config.copy(arUserWidth = config.addressWidth, awUserWidth = config.addressWidth)
  val io = new Bundle {
    val input = slave(Axi4(config))
    val routed = master(Axi4(routedConfig))
    val owners = in Vec(UInt(FixedWorkerMap.domain_bits bits), workers)
    val enabled = in Bits(workers bits)
    val fenced = in Bits(workers bits)
    val busy = out Bits(workers bits)
  }

  def decoder(address: UInt): FixedWorkerDecoder = {
    val d = FixedWorkerDecoder(workers)
    d.io.address := address.resized
    d.io.owners := io.owners
    d.io.enabled := io.enabled
    d
  }
  val ar = decoder(io.input.ar.addr)
  val aw = decoder(io.input.aw.addr)
  val readFence = if (workers == 1) io.fenced(0) else io.fenced(ar.io.worker)
  val readAllowed = ar.io.admitted && (ar.io.bypass || !readFence)
  // RX writeback is discarded by an independent sink. It must remain serviceable
  // even when its domain is no longer installed. Old TX writebacks take the
  // crossbar's rejecting sink, never the successor's packet buffer.
  val writeAllowed = aw.io.transportLine &&
    (aw.io.bypass || (aw.io.known && (!aw.io.tx || aw.io.admitted)))

  def route(address: UInt, d: FixedWorkerDecoder, allowed: Bool): UInt = {
    val selector = UInt(config.addressWidth bits)
    val core = UInt(log2Up(workers + 1) bits)
    core := d.io.worker.resize(core.getWidth) + 1
    when(d.io.bypass) { core := 0 }
    selector := ((core << 13).resize(config.addressWidth) | address(12 downto 0).resized)
    when(!allowed) { selector := U(BigInt(1) << (config.addressWidth - 1), config.addressWidth bits) }
    selector
  }
  val arCapture = Stream(Axi4Ar(routedConfig))
  arCapture.arbitrationFrom(io.input.ar)
  arCapture.payload.assignSomeByName(io.input.ar.payload)
  arCapture.addr.allowOverride
  arCapture.addr := route(io.input.ar.addr, ar, readAllowed)
  arCapture.user := io.input.ar.addr.asBits
  io.routed.ar << arCapture.m2sPipe()

  val awCapture = Stream(Axi4Aw(routedConfig))
  awCapture.arbitrationFrom(io.input.aw)
  awCapture.payload.assignSomeByName(io.input.aw.payload)
  awCapture.addr.allowOverride
  awCapture.addr := route(io.input.aw.addr, aw, writeAllowed)
  awCapture.user := io.input.aw.addr.asBits
  io.routed.aw << awCapture.m2sPipe()
  io.routed.w << io.input.w
  io.input.r << io.routed.r
  io.input.b << io.routed.b

  val ids = 1 << config.idWidth
  val reads = Vec.fill(ids)(RegInit(False))
  val writes = Vec.fill(ids)(RegInit(False))
  val readWorker = Vec.fill(ids)(Reg(UInt((log2Up(workers) max 1) bits)))
  val writeWorker = Vec.fill(ids)(Reg(UInt((log2Up(workers) max 1) bits)))
  val readCharged = Vec.fill(ids)(RegInit(False))
  val writeCharged = Vec.fill(ids)(RegInit(False))
  when(io.input.r.fire && io.input.r.last) {
    assert(reads(io.input.r.id), "DCS read response has no accepted address")
    reads(io.input.r.id) := False
  }
  when(io.input.b.fire) {
    assert(writes(io.input.b.id), "DCS write response has no accepted address")
    writes(io.input.b.id) := False
  }
  when(io.input.ar.fire) {
    assert(!reads(io.input.ar.id) ||
      (io.input.r.fire && io.input.r.last && io.input.r.id === io.input.ar.id),
      "DCS reused outstanding read ID")
    reads(io.input.ar.id) := True
    readWorker(io.input.ar.id) := ar.io.worker
    readCharged(io.input.ar.id) := readAllowed && !ar.io.bypass
  }
  when(io.input.aw.fire) {
    assert(!writes(io.input.aw.id) ||
      (io.input.b.fire && io.input.b.id === io.input.aw.id),
      "DCS reused outstanding write ID")
    writes(io.input.aw.id) := True
    writeWorker(io.input.aw.id) := aw.io.worker
    writeCharged(io.input.aw.id) := writeAllowed && !aw.io.bypass
  }
  for (w <- 0 until workers) {
    io.busy(w) := (0 until ids).map { id =>
      (reads(id) && readCharged(id) && readWorker(id) === w) ||
        (writes(id) && writeCharged(id) && writeWorker(id) === w)
    }.reduce(_ || _)
  }
}
