# RX return-half service and possible future use

The simplified DC grants exclusive permission for an RX load (R12 → RA3 /
PEMD). A later LCI sends FLDX_EH; THX may return HAKD with data even when
software only loaded the line. The DC writes those bytes through AXI and waits
for B before completing WDDA and LCIA. The next RX control read can already
be waiting for that LCIA. RX write service therefore must run independently
of the RX read state machine.

`DcsRxAxiRouter` now accepts each 128-byte write as two 512-bit beats, discards
the returned bytes and sends OKAY with the accepted AW ID only after both
beats. It checks WLAST framing, retains B under backpressure and includes
queued/in-flight writes and held B responses in its quiescence accounting.
No packet-buffer contents are changed by this temporary discard policy.

The returned half of **RxTxDecoupledProtocol** is useful protocol capacity.
Software could intentionally dirty the RX line with a command, status, or a
small response; a response fitting there could provide a very fast TX path.
Discarding writes is the current implementation choice, not a claim that
RX lines must remain clean or that their returned bytes are intrinsically
irrelevant. Before using those bytes, analyse:

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

Validation uses `RxWritebackSim` for independent AW/W channels, delayed W,
queued IDs, partial/zero strobes, held B and quiescence. The actual DCU +
`axi_wr_cl` + generated RX router regression replays the board's data-bearing
HAKD and checks that WDDA, LCIA and the next control read complete. Its delayed
B control still blocks those events until B is released. The harness supplies
test RAM, synthetic returned payload/read completion and direct LCIA-to-invDone
wiring; it is not a full DCS/NIC/THX simulation or board acceptance.
