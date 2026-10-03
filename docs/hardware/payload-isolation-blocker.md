# Payload isolation blocks cooperative scheduling acceptance

October 3, 2026, `feat/cooperative-yield`, candidate RTL `8cc9ad58`.

A focused full-engine RTL regression demonstrates cross-domain TX disclosure.
Domain A sends a valid nested-call descriptor and 64 payload bytes of `0x5a`.
After ordinary PREEMPT, cleanup and A→B activation, B receives its own packet
and reads the payload half of its own valid TX control alias. All 64 returned
bytes are A's `0x5a` bytes. No stale address, foreign PID or unauthorized read is
needed. Killing unauthorized readers therefore does not fix this failure.

The original reproducer at `c2728ee2` used the pinned environment:

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.CooperativeYieldSim -- \
  -t new-owner-cannot-read-previous-owner-tx-payload \
  -DsetupSeed=-869944361 -DsimSeed=1089418806
```

The [machine-readable record](../../data/eci/board-evidence/cooperative-20261003-isolation-blocker.json)
records seeds, log hash, timeout disposition and the stale CI follow-up.
The test failed twice, including the deterministic run above. Its assertion
prints the 64 disclosed bytes. This is simulation evidence, not a board exploit.
At that revision the regression intentionally failed. The source correction and
expanded checks are described below.

`DmaControlPlugin` assigns one TX buffer per physical worker.
`DcsTxAxiRouter.doPreempt` clears the saved descriptor but not payload storage;
its packet-data response forwards the shared buffer without an ownership-epoch
validity check. Address authorization and final cache invalidation do not erase
that backing storage. A replacement must cover reads, partial write strobes,
DMA transmission, outstanding responses and A→B→A reuse. Merely masking host
reads is insufficient if a descriptor can transmit unwritten stale bytes.

RX also needs a directed reuse test: DMA writes the actual packet length using
byte strobes, while `DcsRxAxiRouter` permits reads within the allocated slot and
forwards complete beats without masking the packet tail. Its nominal 64-byte
beat accounting uses shifts by five. This RX concern is source-derived and has
not yet been independently reproduced; do not report it as a measured failure.

The hardware campaign stopped after trial 48 completed normally, before any
next board operation. The current single-worker screening bracket is
26,857,015 passing cycles (134.285075 ms) and 26,857,032 failing cycles
(134.285160 ms), at 200 MHz. Passing exposure is at least 180 seconds plus the
100-RPC postcheck. This is not an adjacent-tick boundary and the latest passing
value has not passed two-boot contention qualification. The previously recorded
26,656,301-cycle (133.281505 ms) two-boot contention evidence remains a finite
observation of the old candidate; it cannot establish isolation or acceptance.
All failing board trials were captured before reset. The DUT was then restored
to 10,000 cycles (50 microseconds), with readback and clean checks of all eight
TAD units and kernel logs. No reset was needed; tracing remains enabled.

Do not merge this candidate. Correct payload isolation, rerun the regression
and relevant transport/ownership checks, build matched hardware and software,
and repeat board acceptance. The earlier baseline kernel lockup and open timing
remain separately unresolved. No HOST_WAKE or legacy thread-router design is
introduced by this finding.

## Source correction — October 3 follow-up

The follow-up keeps the address and software ABI unchanged. TX now scrubs all
1536 bytes of the physical worker's allocation after the drained PREEMPT event,
including initial activation. Successor TX reads and writes wait until every
scrub write response has completed. Ownership installation and RX service may
proceed while this internal TX scrub runs; the scrub does not access host cache
lines or require an additional host action. Outstanding old-owner transport
must already be drained at PREEMPT, and a local assertion checks router
quiescence. The next preemption also waits for scrub completion. Progress retains the
existing assumption of eventual packet-buffer AXI write-response service; the
scrub itself never waits for successor host traffic.

Clearing the backing store protects partial writes and DMA transmission of
unwritten bytes, as well as host reads. Each allocation is 1536 bytes, whereas
the mapped control payload plus twelve overflow lines covers 1600 bytes. TX
now splits these accesses into single packet-buffer beats: reads of the final
64-byte padding return zero, and writes there issue a zero-strobe beat to the
same worker's allocation instead of reaching the adjacent allocation. DCS still
receives its original two-beat read or single write response with retained ID.

RX now retains the actual DMA-written packet length (`HostReq.len`), rather than
the larger allocation size. It masks the final partial beat and synthesizes
zero beats beyond that length. Single-beat packet-buffer reads avoid crossing
an allocation boundary. The independent RX writeback sink remains in place.
This also removes the previous 32-byte shifts from the 64-byte beat accounting.

Directed validation covers repeated A→B→A handoffs and all TX aliases, actual
DMA transmission of an unwritten successor payload through the neighbor-miss
path, partial AXI strobes, delayed scrub B responses, stable backpressured R/B
responses, final-overflow access with an adjacent-allocation sentinel, and RX
padding with deliberately stale backing bytes. These are RTL checks, not board
acceptance. The correction introduces scrub traffic and splits packet-buffer
bursts; its latency and routed timing still require measurement on a new build.
Pipeline 515944 remains the old, vulnerable image and must not be substituted
for the corrected source when collecting acceptance evidence.

The full fast ECI regression passed **50 tests**, with one existing ignored test,
after this correction. The [follow-up evidence record](../../data/eci/board-evidence/cooperative-20261003-isolation-correction.json)
retains the command, log hash, live 515944 CI status, reservation check and
pre-existing local THX submodule difference. No board operation was performed
in this source-correction round. Resume board acceptance only after the
corrected matched build passes CI; retain the old candidate and failure captures.

Pipeline 516077 (`42b5fead`) passed all 13 gates and `prepare-eci` (2865570),
but hardware job 2865571 failed synthesis: the TX scrub FSM debug output grew
from three to four bits while the VHDL wrapper retained a three-bit slice.
The wrapper and all five application ILA state probes now use 21-bit packing,
with later fields shifted by one bit. The preparation-time interface checker
now verifies generated debug widths, nonoverlapping complete packing, both
block-design port levels and ILA widths. It passes against the exact failed
pipeline's generated RTL and rejects the old wrapper with the observed mismatch.
This is debug wiring only; the payload fix and software/address ABI are unchanged.
No bitstream or board result was produced by 516077; a replacement build is required.
