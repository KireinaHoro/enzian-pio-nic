# RX return-half service and possible future use

The simplified DC grants exclusive permission for an RX load (R12 → RA3 /
PEMD). A later LCI sends FLDX_EH; THX may return HAKD with data even when
software only loaded the line. The DC writes those bytes through AXI and waits
for B before completing WDDA and LCIA. The next RX control read can already
be waiting for that LCIA. RX write service therefore must run independently
of the RX read state machine.

`DcsRxAxiRouter` now accepts each 128-byte write as two 512-bit beats and sends OKAY with the accepted AW ID only after both
beats. It checks WLAST framing, retains B under backpressure and includes
queued/in-flight writes and held B responses in its quiescence accounting.
The contiguous borrowed-RX implementation retains payload bytes in a per-packet
shadow with byte strobes. Dirty eviction/reload must preserve parser mutations;
discarding returned bytes would revert those edits. Descriptor writes remain
ignored. Retirement clears validity after old-line invalidation completes, and
no packet-buffer contents are changed. This is private replay storage, not a
command or response channel.

The returned half of **RxTxDecoupledProtocol** is useful protocol capacity.
Software could intentionally dirty the RX line with a command, status, or a
small response; a response fitting there could provide a very fast TX path.
The replay shadow does not interpret these writes as messages. Before adding
command/response semantics, analyse:

- Which fields/lines carry the return payload, byte validity and framing;
  distinguish intentional messages from unchanged cache contents and reloads.
- How completion, ownership, replay, cancellation and preemption identify each
  return exactly once, including partial strobes and speculative overflow lines.
- Whether copying/packetization and resource dependencies can always service
  the return without waiting for the next RX read or creating a cycle.
- Latency and throughput benefits relative to the independent TX path, cache
  traffic and contention, and the useful response-size distribution.

This optimization remains open; no command/response ABI or performance claim
is selected here. Schedule it alongside [application performance collection](../research/roadmap.md#application-performance-and-coupled-rxtx-analysis),
after measuring the independent RX/TX baseline. Application benefit determines
whether to adopt it; it does not gate the address/PREEMPT implementation.

## Historical proposal: borrow RX payload instead of staging it

This initial two-span sketch is superseded by the contiguous layout and mutable
replay implementation described above and in the [runtime contract](../software/runtime.md#request-and-transport-ownership).
The following records the earlier layout and reasoning, not the current API.

October 9 source inspection identified a separate optimization to evaluate
alongside response transmission through the invalidate/return half-cycle:
remove the full-packet copy from 2F2F cache lines into the worker's `rx_buf`.
This is a proposal, not an implemented or measured improvement.

With cooperative PREEMPT, the delivered RX packet remains available until the
next RX doorbell or worker yield/reassignment retires it. `core_eci_rx_traced`
currently copies the payload but only toggles a software parity bit before
returning; hardware release occurs when the opposite control CL is read.
A borrowed view must expire before that read or a yield. Deferred handlers and
future results still need owned data: a suspended fiber cannot retain a pointer
into a worker's RX mapping while that worker receives other packets.

The current layout prevents a universal contiguous pointer. Relative to the
RX base, native UDP's first 64 payload bytes occupy `0x040..0x07f` for parity 0
or `0x0c0..0x0ff` for parity 1; overflow starts at `0x100` for both. ONC-RPC
also carries 48 inline argument bytes immediately before those 64 bytes.
Parity 1 is contiguous, while parity 0 has a 128-byte gap. Do not stitch that
gap by accessing the opposite control CL: that access is the RX doorbell.

A candidate software interface returns two read-only spans (control-line
payload and overflow), with lengths and an explicit borrowing lifetime.
Parity 1 can coalesce to one span; existing codecs can retain a linearization
fallback. No hardware layout change is needed for this interface. Preserve
the existing ordering barriers and verify ownership/replay before adoption.

Application implications from the inspected sources:

- Adder/microbenchmarks use the runtime XDR adapter, which currently requires
  contiguous input and decodes into owned arguments. Small requests can decode
  directly from the control CL; larger parity-0 requests need a segmented XDR
  reader or a copy fallback.
- Available local/cached Dandelion codecs use contiguous Rust slices and BSON
  decoding into owned objects. Split-input decoding needs codec work; the exact
  current pinned source was not resolved during this inspection.
- The pinned native memcached adapter copies worker `rx_buf` into mutable
  `c->rbuf`, which upstream parsing modifies and moves. Copying the RX spans
  directly into `c->rbuf` would remove one of today's two full-packet copies
  without changing parser ownership.
- Nested-validation decoding copies into an owned message; forwarded RPC
  replies also copy RX staging into TX staging. Both can consume spans without
  retaining RX pointers beyond the receive lifetime.

Measure transport staging separately from decoding, application storage and TX
copies. Compare both parities, inline/overflow boundaries, packet sizes and
application workloads against the existing baseline. Validate next-receive
invalidation, PREEMPT/reassignment and deferred/future ownership; if borrowing
extends through TX, include combined RX/TX coherence-progress checks. The
existing return-half regression below does not validate borrowed RX buffers or
response transmission through returned data.

## Existing return-half service validation

Validation uses `RxWritebackSim` for independent AW/W channels, delayed W,
queued IDs, partial/zero strobes, held B and quiescence. The actual DCU +
`axi_wr_cl` + generated RX router regression replays the board's data-bearing
HAKD and checks that WDDA, LCIA and the next control read complete. Its delayed
B control still blocks those events until B is released. The harness supplies
test RAM, synthetic returned payload/read completion and direct LCIA-to-invDone
wiring; it is not a full DCS/NIC/THX simulation or board acceptance.


[Board regression, October 2](../development/hardware-test-results.md#rx-writeback-fix--2026-10-02):
pipeline 515802 at `a51139e` passed two cold-reset trials, 200 correct RPCs total.
The matched trace shows RX `0x100` completing `WDD → WDDA/LCIA` and the pending
`0x80` read completing afterward. This closes the reproduced RX service blocker
for the checked workload; broader contention/refinement checks remain separate.
