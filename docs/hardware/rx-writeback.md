# Hot-path performance optimization

This note collects the LH hot-RPC baseline, hardware optimization hypotheses,
and the investigation needed before changing the implementation. RX return-half
response transmission remains a proposal. No optimization or new simulation
result is claimed here; timing optimization remains paused. The simulation plan
below is follow-on work, not a new hardware CI campaign.

## Measured baseline and boundaries

The October 14 analysis of pipeline 518922's one-worker hot cohort uses the
same two requests as the paper's median-case bar (IDs 2298 and 2306): **4.145 µs
MAC-to-MAC**, including **2.225 µs CPU–MAC**. These are averages of the component
vectors of the two central requests, not independent component medians. See the
[tracked data, provenance and reproduction](../../data/paper/nullrpc/breakdown/README.md)
and [transport timestamp limitations](../research/latency-measurement.md).
Tracing/probe overhead remains included.

| CPU–MAC contribution | Mean time (ns) |
| --- | ---: |
| MAC-facing queue/crossing intervals | 55 |
| RX scheduling/dispatch | 35 |
| CPU RX unpack after read unblocks | 145 |
| CPU TX descriptor/barrier preparation | 160 |
| Combined RX delivery and TX doorbell transport/FPGA ingress residual | 810 |
| TX control-line reclaim, LCI acceptance to LCIA acceptance | 965 |
| TX commit setup and final submission | 55 |
| Total | 2225 |

The 810 ns residual is the application-clock RX-descriptor-to-TX-commit envelope
(2750 ns) minus the CPU RX-unblock-to-TX-doorbell duration (1940 ns). This uses
within-clock durations, not a fitted CPU/FPGA epoch. MAC event pulses cross into
the application clock; their 55 ns difference is not an isolated measurement of
physical asynchronous-FIFO latency.

For both selected requests, the reclaim events have these recorded offsets:

| Event | Offset from LCI acceptance (ns) |
| --- | ---: |
| `EciTxCtrlInvalidate` / `EciLclLciFire` | 0 |
| `FLDX_EH` accepted at outgoing DCS/gateway system-clock interface | 245 |
| Data-bearing `HAKD` accepted at incoming DCS/gateway interface | 490 |
| `EciTxCtrlInvalidated` / `EciLclLciaFire` | 965 |

The outer 965 ns is an application-clock interval. System events cross an
untimestamped asynchronous trace FIFO before recording; subtracting recorded
pipeline stages does not remove that uncertainty. Thus the apparent 245 ns
forward/ack exchange and **720 ns surrounding residual** are approximate.
The apparent outgoing/return-side shares are 245/475 ns. These are **not CCPI
pin timestamps**, pure wire latency, or measured FIFO residence times. LCIA
follows writeback completion, not merely arrival of the returned bytes.

## Hardware: shorten the coherence and writeback paths

Source inspection identifies the following serial work:

- LCI to forward: local register slice, DCS routing/arbitration, DCU directory
  lookup and coherence-state processing, outgoing staging and app-to-system CDC.
- HAKD to writeback: 138-to-69-byte fragmentation, system-to-app CDC and
  reassembly, DCS routing, DCU event processing and write-transaction creation.
- Writeback to LCIA: AXI staging/queues, TX router descriptor/payload handling,
  packet-buffer write and B response, DCS write-completion processing (WDDA),
  then LCIA routing/staging.

See [DCS crossings](../../vivado/eci/rtl/dcs_cdc.sv),
[IP configuration](../../vivado/eci/create_project.tcl),
[DCU controller](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_controller/rtl/dcu_controller.sv),
and [TX AXI router](../../hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala).
The controller handles LCI, returned data and write completion as separate
work, with directory lookup/state processing. The TX router also sends the
inline payload beat through a packet-buffer AW/W/B transaction before returning
B to DCS. This completion chain is a concrete investigation target.

Candidates include removing unnecessary registered boundaries, reducing repeated
clock crossings/width conversions, merging compatible clock domains, shortening
DCU event processing, and avoiding unnecessary inline-payload memory round trips.
Reducing FIFO *depth* alone does not remove empty-path latency. CMAC clocks still
need valid crossings; register stages can be necessary for physical timing and
backpressure. Preserve coherence progress and AXI completion semantics.

A few hundred nanoseconds (an exploratory 200–500 ns target) was discussed as
an engineering hypothesis, not a demonstrated saving or a cycle-count-derived
bound. Neither the full 720 ns residual nor the 810 ns transport envelope can
currently be assigned to removable buffering. Attribute cycles before choosing
an optimization; do not equate the 720 ns residual with 144 removable registers.

### Required full-system RTL simulation and cycle accounting

Build a representative end-to-end RTL simulation of the hot inline RPC path,
including the actual NIC, DCS/DCU, AXI adapters and packet-buffer completion.
Use the matched measurement revision/configuration first, then compare current
RTL explicitly. Existing isolated return-half tests do not provide this timing
coverage. Investigate complementary CPU/coherence-model attachment boundaries:

1. **DCS-facing ECI gateway interface:** drive accepted requests and returned
   cache-line data with controlled delays, to isolate DCS/NIC processing.
2. **ECI gateway/transport boundary, towards CCPI:** include the real gateway,
   buffering, credits, arbitration and serialization where feasible. Define
   exactly what the endpoint model replaces; do not label its latency as a
   measured ThunderX/physical-link result.

Count cycles at each boundary, recording source-clock timestamps at handshake
acceptance rather than after a trace CDC. Record clock periods/phases and convert
cross-domain intervals to time; do not count every stage as a 200 MHz cycle.
Instrument at least:

- LH LCI, DCU input acceptance/directory lookup/action, forward before/after CDC,
  gateway and transport send/receive handshakes;
- returned HAKD before/after CDC/reassembly, DCU write issue, AXI AW and both W
  beats, packet-buffer AW/W/B, DCS B and WDDA processing, and LH LCIA;
- RX descriptor delivery and TX doorbell ingress, to resolve the separate
  810 ns residual as well as the reclaim path.

For every interval distinguish fixed pipeline latency, FSM/lookup work,
serialization, FIFO occupancy and ready/valid or credit stalls. First establish
an unloaded single-outstanding baseline, then vary clock phase, controlled
backpressure and concurrent workers/traffic. Reconcile the summed intervals
with the outer LCI-to-LCIA and MAC measurements, allowing for the board trace's
system-event timestamp uncertainty. Report cycle tables and model limitations;
change one mechanism at a time and recheck correctness and physical timing
before claiming an achievable board improvement.

## Hardware: reuse the RX return half for responses

Software could write a small response into the received RX cache line, then
read the opposite RX control line. That next receive doorbell would invalidate
the previous line, returning the response to the FPGA for transmission.

This does **not inherently remove the observed ~245 ns forward/ack exchange**
from MAC latency. Both separate TX and coupled RX/TX still require a CPU doorbell
request, FPGA invalidation, and CPU data return on the reply's critical path.
The clearer benefit is eliminating the separate TX completion and combining
response submission with posting the next receive. For the steady-state inline
case, two control reads and two reclaim exchanges per RPC can become one of
each, reducing coherence traffic and the inter-request gap. This is a protocol
counting hypothesis, not a measured throughput gain. Simpler descriptor handling
or earlier packetization may additionally improve latency; measure separately.

Before selecting an ABI or implementing this path, establish:

- Fields, payload capacity, byte validity and framing that distinguish an
  intentional response from unchanged contents, dirty replay and reloads.
- Ownership, message identity, exactly-once consumption, cancellation and
  preemption behavior, including partial strobes and speculative overflow lines.
- Independent return service: transmitting a response must not depend on the
  next request arriving or create a resource/coherence dependency cycle.
- Response-size distributions, large-response fallback, and application benefit
  across latency/tails, throughput, CPU work and coherence contention.

Schedule this with [application performance evaluation](../research/roadmap.md#application-performance-and-coupled-rxtx-analysis).
It is not a prerequisite for address/DESCHED work or a selected replacement for
independent TX. Preserve [coherence progress](coherence-progress.md).

### Existing RX return-half service

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

## Software investigation (stub)

TODO: investigate CPU hot-path work separately from FPGA/link latency. Separate
pure XDR routines from allocation, dispatch, tracing, transport staging and
buffer reservation. The current median-case RPC-runtime segment is 1475 ns:
310 ns unmarshal stage, 295 ns marshal stage, 795 ns surrounding CPU runtime,
and 75 ns FPGA RPC processing. The 605 ns in XDR-containing stages is not pure
XDR time; see the [measurement boundaries](../../data/paper/nullrpc/breakdown/README.md#lh-rpc-runtime-detail).

Evaluate copies/borrowed RX access against the current
[runtime ownership contract](../software/runtime.md#request-and-transport-ownership),
including both parities, inline/overflow sizes, mutable parsers and owned data
for deferred handlers. The former two-span/parity-gap proposal is superseded by
the contiguous layout and mutable replay implementation. Do not retain borrowed
pointers across the next receive, yield or reassignment. No software optimization
or new pure-XDR measurement is selected by this note.

## Existing return-half service validation

Validation uses `RxWritebackSim` for independent AW/W channels, delayed W,
queued IDs, partial/zero strobes, held B and quiescence. The actual DCU +
`axi_wr_cl` + generated RX router regression replays the board's data-bearing
HAKD and checks that WDDA, LCIA and the next control read complete. Its delayed
B control still blocks those events until B is released. The harness supplies
test RAM, synthetic returned payload/read completion and direct LCIA-to-invDone
wiring; it is not a full DCS/NIC/THX simulation or board acceptance.


[Board regression, October 2](../../agents/hardware-state.md#rx-writeback-regression-evidence):
pipeline 515802 at `a51139e` passed two cold-reset trials, 200 correct RPCs total.
The matched trace shows RX `0x100` completing `WDD → WDDA/LCIA` and the pending
`0x80` read completing afterward. This closes the reproduced RX service blocker
for the checked workload; broader contention/refinement checks remain separate.
