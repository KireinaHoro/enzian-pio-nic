# Payload isolation blocks cooperative scheduling acceptance

October 3, 2026, `feat/cooperative-yield`, candidate RTL `8cc9ad58`.

A focused full-engine RTL regression demonstrates cross-domain TX disclosure.
Domain A sends a valid nested-call descriptor and 64 payload bytes of `0x5a`.
After ordinary PREEMPT, cleanup and A→B activation, B receives its own packet
and reads the payload half of its own valid TX control alias. All 64 returned
bytes are A's `0x5a` bytes. No stale address, foreign PID or unauthorized read is
needed. Killing unauthorized readers therefore does not fix this failure.

Reproduce with the pinned environment:

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.CooperativeYieldSim -- \
  -t new-owner-cannot-read-previous-owner-tx-payload \
  -DsetupSeed=-869944361 -DsimSeed=1089418806
```

The [machine-readable record](../../data/eci/board-evidence/cooperative-20261003-isolation-blocker.json)
records seeds, log hash, timeout disposition and the stale CI follow-up.
The test failed twice, including the deterministic run above. Its assertion
prints the 64 disclosed bytes. This is simulation evidence, not a board exploit.
The regression is intentionally failing until the implementation is corrected.

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
