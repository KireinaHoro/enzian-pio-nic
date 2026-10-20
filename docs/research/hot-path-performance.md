# Hot-path performance analysis

This topic connects executed software instructions with the hardware/software
cache-line layout and coherence work needed for a hot RPC. The optimization
objectives are fewer instructions on defined paths and less transport work,
while preserving request semantics, ownership, isolation and progress.
Instruction counts, CPU time and end-to-end latency are separate outcomes.

## Analysis scope and comparison method

The [current transport layout](../architecture/hw-sw-interface.md)
and [runtime ownership contract](../software/runtime.md#request-and-transport-ownership)
define the implementation being optimized. The historical audit below is an
exact-binary reference for one successful inline `add` path. It predates the
current transport and active schema-defined echo workload; its counts must not
be presented as counts for the current runtime or echo cases.

For each comparison, pin the application/runtime/library binaries, compiler
options, generated ABI, hardware revision, workload and instrumentation mode.
Audit selected executed branches and callees, including PLT/TLS and library
paths. Record assumptions and separately count success, NACK, overflow, deferred
and exceptional paths where relevant. Compare matching boundaries: complete
worker iteration, RX-to-TX, and RX-unblock-to-TX-doorbell. Retain an ordered
instruction ledger so attribution changes cannot masquerade as savings.

Evaluate instruction reductions alongside CPU durations, latency distributions,
throughput and coherence traffic. A blocking load is one instruction regardless
of its wait; fewer instructions alone do not establish lower RPC latency. Use
[hot measurement methodology](hot-measurement.md) for current wire/client
boundaries and [benchmark campaigns](../development/benchmark-campaign.md) for
matched workloads and instrumentation.

## Software and cache-line layout questions

| Area | Question to resolve | Evidence needed for a candidate |
| --- | --- | --- |
| Dispatch and codecs | Which instructions implement required semantics, and which come from allocation, generic dispatch, copying or tracing? | Per-callee counts with identical workload and compiler options; separate pure XDR from surrounding runtime work. |
| Descriptor access | Can field placement, alignment or hardware byte-order conversion reduce host loads, stores and shifts? | Generated layout and disassembly comparison; matched RTL, headers and runtime; preserved wire and host field semantics. |
| Payload placement | Can inline/overflow placement and direct encoding reduce copies and cache-line accesses? | Both parities, boundary sizes, byte strobes, mutable RX replay and deferred-data ownership checks. |
| Coherence exchange | Can a layout or protocol change reduce doorbells, returned bytes or invalidations without introducing a dependency cycle? | Per-RPC transaction/cycle accounting and progress/isolation checks, followed by matched performance measurements. |

The current transport uses 128-byte coherence lines with a 64-byte payload half
for complete small messages and contiguous overflow storage for larger ones.
RX replay preserves parser mutations; borrowed payloads cannot survive the next
receive, yield or reassignment. Keep those ownership rules explicit when comparing
layouts. Generated descriptors, kernel mappings, runtime accesses and FPGA
interpretation must change together; do not optimize one side against an
unmatched ABI.

The [hardware path analysis](#hardware-shorten-the-coherence-and-writeback-paths) covers coherence and
writeback stages, cycle-accounting methodology and the proposed coupled RX-return
response path. That proposal is not the selected ABI. Its independent-response
service, message identity and large-response fallback questions remain decision
criteria. Analyze instruction savings and protocol savings together without
counting either as a measured gain before a matched comparison.

## Measured coherence-path baseline — October 14

The October 14 analysis of pipeline 518922's one-worker hot cohort uses the
same two requests as the paper's median-case bar (IDs 2298 and 2306): **4.145 µs
MAC-to-MAC**, including **2.225 µs CPU–MAC**. These are averages of the component
vectors of the two central requests, not independent component medians. See the
[tracked data, provenance and reproduction](../../data/paper/nullrpc/breakdown/README.md)
and [transport timestamp limitations](../hardware/tracing.md).
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
These are optimization hypotheses, not measured savings. Physical timing work
remains subject to the [implementation pause](../hardware/physical-implementation.md#scope-and-interpretation).

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

### Full-system RTL simulation and cycle-accounting methodology

A representative end-to-end RTL simulation is needed to attribute the hot inline RPC path,
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

The [RX-return response proposal](../proposals/rx-return-responses.md) explores
combining response submission with the next receive. Its ABI, independent
response service, ownership and large-response fallback remain design questions.
The measured timings above do not establish its achievable savings.

### Software attribution alongside the hardware baseline

The same October 14 median-case RPC-runtime segment is 1475 ns: 310 ns unmarshal,
295 ns marshal, 795 ns surrounding CPU runtime and 75 ns FPGA RPC processing.
The 605 ns in codec-containing stages is not pure XDR time. Separate codec work
from allocation, dispatch, tracing, transport staging and buffer reservation;
compare exact instruction counts with the same stage boundaries. The former
two-span/parity-gap proposal is superseded by the contiguous layout and mutable
RX replay. Borrowed pointers cannot survive another receive, yield or
reassignment; deferred handlers need owned data.

## Historical exact-binary instruction baseline — October 5

For the successful inline `add` path, the pre-PMU deployed AArch64 binaries
execute **1,865 instructions with runtime tracing disabled**, or **3,434 with
buffered latency tracing enabled**, under the steady-state conditions below.
These count selected executed branches and callees, not function sizes or both
sides of a conditional. They are nominal static path counts, not PMU results or
an assertion that every measured request executed exactly this many instructions.

The traced variant corresponds to the buffered latency baseline. The disabled
variant selects the trace-off branches in the **same binary**; it is not a
separately compiled trace-free runtime. Even disabled, trace checks, TLS lookups,
record copies and clearing remain in the code.

| Exclusive attribution | Trace disabled | Trace enabled |
| --- | ---: | ---: |
| Worker loop, timeout check, empty fiber queue and execution hook | 222 | 232 |
| RX transport, excluding timestamp callee | 61 | 81 |
| Runtime dispatch and outcome handling | 313 | 313 |
| Decode, including allocation and XDR | 367 | 367 |
| Handler, including add and correctness check, excluding trace helpers | 56 | 56 |
| Encode, including XDR | 202 | 202 |
| Reply wrapper, excluding separately attributed callees | 94 | 97 |
| TX transport, including 8-byte copy | 95 | 95 |
| Argument cleanup, including XDR_FREE and free | 228 | 228 |
| Trace helper calls, timestamps, CPU lookup and buffer append | 227 | 1,763 |
| **Total per successful loop iteration** | **1,865** | **3,434** |

Each executed instruction appears once. A call instruction belongs to its caller;
its PLT stub and callee are counted separately. Nested callees inherit the
category unless explicitly attributed elsewhere. Thus these rows are not the
same boundaries as the timestamp latency chart. In particular the trace-helper
row is not the complete cost of all tracing-related instructions: inlined
record copying and call-site setup remain in their callers' rows.

### Latency-relevant boundaries

| Inclusive boundary, unless stated otherwise | Trace disabled | Trace enabled |
| --- | ---: | ---: |
| Before RX function entry | 212 | 407 |
| RX function entry through TX function return | **1,300** | **2,498** |
| After blocking RX load through TX doorbell load | **1,275** | **2,473** |
| After TX function return through loop-back | 353 | 529 |

The middle doorbell boundary excludes the RX load itself, includes the TX load,
and excludes TX's return sequence. The blocking RX instruction is
`core_eci_rx_traced+0x28` (`0x8120`); the TX doorbell is
`core_eci_tx+0xd4` (`0x5b44`). A blocked load counts as one instruction however
long it waits. This is not a cycle or cache-miss estimate.

The handler's 56 instructions comprise 37 in `bench_handler`, 5 in
`compute_add` (one actual `add`, frame setup/teardown and return), and 14 in
`add_check`. Trace-ID/result helpers are separate. The application does not just
perform the addition: the runtime allocates the decoded 12-byte object,
decodes three XDR scalars, constructs a managed reply, encodes two scalars,
and later calls XDR_FREE on the three arguments before freeing the object.
The decode allocation itself is 70 instructions (allocation wrapper, PLT,
and 59-instruction warmed `calloc` path).

Buffered tracing adds 1,569 instructions per iteration in this binary. It makes
11 RAW timestamp reads: RX entry, RX descriptor arrival, RX exit, and before/
after decode, handler, encode and TX. The worker loop also makes one MONOTONIC
read for its timeout check, in both variants. Timestamp calls enter the vDSO,
not the kernel, on the selected fast path. Return-register clearing, stack
canary checks, frame operations, barriers, PLT stubs and TLS descriptor callbacks
are all counted. No claim of instruction/cycle equivalence follows.

### Branch selection and limits

The workload determines the application/runtime branches:

- RX valid, type ONCRPC call (3), no overflow beyond the 48-byte inline area;
  packet dispatch takes the call arm, not DESCHED or response handling.
- Allocation succeeds; three scalar decodes succeed. Current fiber equals the
  main fiber, so tracing uses the inline request record.
- `flush_spin_us == 0`; `compute_add` and the successful `add_check` are selected.
  Outcome is synchronous REPLY, with no destructor or deferred continuation.
- Two response scalars encode successfully; length is 8. TX takes the reply
  arm (4), copies 8 bytes, has no remaining payload and returns ACCEPTED.
- Cleanup uses XDR_FREE (scalar no-op success branches) and frees the allocation.
- Watchdog disabled; empty ready queue; its installed execution-flush hook
  returns without sending anything. Ordinary iteration does not sweep timeouts.

Additional steady-state library conditions are explicit assumptions: resolved
PLT/static TLS, warmed allocator tcache, valid stack canaries, available trace
buffer, registered rseq CPU lookup, and a vDSO read with no seqlock retry or
nanosecond normalization carry. Warmups and synchronous allocate/free support
the tcache selection, but the latency trace is not an instruction branch trace.
The rare vDSO normalization arm adds 8 instructions for a single carry; seqlock
retries, the once-per-second timeout sweep, NACK-only iterations, interrupts,
faults and allocator initialization are outside this nominal successful RPC.
They must not be assigned these counts without selecting their own paths.

The pinned library path uses Nix glibc `memcpy` at ELF offset `0xb1800`,
`memset` at `0xb2240`, DCZID=5 and registered rseq. The selected 288-byte zeroing
path executes four 64-byte loop iterations for its stack alignment. Resolver
observations are retained in the linked summary; a different libc or resolver
selection requires a new path audit.

### Reproduce the historical count

The inputs are the exact baseline app (SHA256 `9a74fff6…`), runtime from
`7bc9302b` (`d4cd4ed5…`, before PMU instrumentation), mapped libtirpc and Nix
glibc, matching loader, and public vDSO from the DUT kernel. Full hashes and
paths are pinned in the manifests:

- [Traced path](../../data/eci/board-evidence/hot-static-516232.json)
- [Trace-disabled path](../../data/eci/board-evidence/hot-static-516232-untraced.json)
- [Counts and resolver validation](../../data/eci/board-evidence/hot-static-516232-summary.json)

The exact app was retrieved from
`enzian-gateway:/scratch/pengxu/buffered-latency-516232/microbenchmarks`;
the similarly named older deployment app has a different hash and was not used.
Local artifacts are in `out/static-hot-count/`; public vDSO and probe output
are also retained at `/scratch/pengxu/static-hot-count/` on the shared board
scratch volume. Do not substitute the current PMU runtime for these binaries.

```sh
python3 tools/enzian/static_path_count.py \
  data/eci/board-evidence/hot-static-516232.json \
  --objdump aarch64-unknown-linux-gnu-objdump \
  --output out/static-hot-count/count.json
python3 -m unittest discover -s tools/enzian -p test_static_path_count.py
```

Repeat with the `-untraced.json` manifest for the disabled variant. The tool
checks input hashes, validates every selected control-flow edge, requires every
executed call to be resolved, and emits an ordered instruction ledger plus
branch decisions. The selected-path audit cannot prove its predicates from
machine state; those assumptions are explicit above. The
[PMU retired-instruction blocker](#instruction-counter-blocker)
explains why this baseline uses static selected-path counts.

## PMU measurement boundaries

The optional runtime profiler uses task-bound Linux perf events pinned as one
non-multiplexed group, with direct AArch64 userspace reads. CSV event order is
`INST_RETIRED` (0x08), native 64-bit `CPU_CYCLES` (0x11), `L1D_CACHE_REFILL`
(0x03), `L2D_CACHE_REFILL` (0x17). Counts exclude kernel and hypervisor execution.
Use the [PMU validation procedure](../development/validation.md#runtime-pmu-profiling)
before interpreting a new cohort. Availability and one successful simple loop
do not establish counter accuracy. The historical instruction event failed
independent checks; neither positive deltas nor IPC from that campaign are valid.

Snapshots include their own instructions, timestamps and bookkeeping. Empty
snapshot cycle calibration is a lower-cost observation, not a universal correction.
Cache refills can complete after their initiating instruction; stage attribution
is bounded by the sampling points, not precise load-PC attribution. Snapshots of
different counters are sequential. L1D/L2D events overlap and must not be summed.
RX entry includes pre-arrival blocked time; RX-return to TX-return is the measured
software service interval. Post-TX cleanup, trace-buffer append, unsuccessful RX
polls and inter-request maintenance are outside that interval. Instruction-cache
misses and sampled instruction/address attribution are not collected here.

## October 5: pipeline 516232

The October 5 historical `add` campaign used pipeline 516232 with corrected
module `af0f64fe`: three cohorts of 1,000 measured requests plus 16 warmups each.
The PMU results below use buffered runtime records; they do not require the
campaign's FPGA trace, whose final sample-loss flag was set. These measurements
include instrumentation and do not describe the current echo baseline.

| Counter snapshots | RTT P50 | P90 | P95 | P99 | P100 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Off | 12.240 µs | 12.400 | 12.470 | 16.262 | 19.160 |
| Coarse | 12.400 µs | 12.430 | 12.491 | 12.674 | 19.280 |
| Detailed | 13.350 µs | 13.360 | 13.390 | 13.670 | 20.360 |

These sequential cohorts show +0.160 µs coarse and +1.110 µs detailed median RTT
relative to snapshots off. They are not paired per-request overhead corrections;
the lower instrumented P99 is not evidence that profiling improves latency.

| Detailed stage | L1D refills P50 / P99 / max | L2D refills P50 / P99 / max | User cycles P50 |
| --- | ---: | ---: | ---: |
| RX call, including blocked wait | 1 / 2.01 / 4 | 1 / 1 / 1 | 16,583 |
| Runtime dispatch after RX | 0 / 0 / 4 | 0 / 0 / 0 | 829 |
| Unmarshal | 0 / 0 / 2 | 0 / 0 / 0 | 838 |
| Before handler | 0 / 0 / 0 | 0 / 0 / 0 | 445 |
| Handler | 0 / 0 / 0 | 0 / 0 / 0 | 614 |
| Before marshal | 0 / 0 / 1 | 0 / 0 / 0 | 487 |
| Marshal | 0 / 0 / 3 | 0 / 0 / 0 | 653 |
| Before TX | 0 / 0 / 0 | 0 / 0 / 0 | 525 |
| TX call | 1 / 1 / 2 | 1 / 1 / 1 | 4,163 |

Over RX-entry through TX-return, all 1,000 detailed requests had exactly two L2D
refills; 984 had exactly two L1D refills (mean 2.040, max 6). The coarse cohort
agrees: two L2D refills in every request, mean 2.036 L1D refills. The location is
consistent with the coherent RX read and TX doorbell/next-line acquisition;
there is no PMU address sample proving which individual access caused each event.

RX-return to TX-return cycles were 6,825 median with coarse snapshots and 8,553
with detailed snapshots. These include instrumentation and are not new estimates
of uninstrumented component latency.

### Instruction-counter blocker

The initial 200,000-instruction loop reported 200,190, apparently consistent
with probe overhead. Stronger checks then exposed:

- Seven detailed RPC stage deltas of −55 instructions, differing from common
  deltas by multiples of 256. Do not discard just these seven: positive deltas
  are not independently validated either.
- A separate non-Lauberhorn process on CPU0 produced 9,376 negative deltas in
  100,000 adjacent snapshot pairs (minimum −68). Direct `PMEVCNTRn_EL0` reads
  also reproduced it: 5,079 negative pairs, minimum −57. Thus changing from
  selected-register access is not a demonstrated fix.
- The same six-instruction memory loop, confirmed by disassembly, runs 1,048,576
  iterations for either footprint: approximately 6.291 million instructions
  expected. Kernel perf reads reported 6,291,892 for a 512-byte footprint but
  4,194,996 for 64 MiB; userspace reads agreed apart from boundary overhead.
  Other repeats gave approximately 2.097 million for the large footprint.

The cache response behaved as expected: about 524,288 L1D refills for the 64 MiB
stream with 64-byte stride (two accesses per 128-byte cache line), versus a few
refills for the tiny repeated footprint. These checks establish an instruction
measurement blocker on this setup, not its root cause. They do not establish a
new kernel bug or a named hardware erratum. PMU configuration, hardware semantics
and kernel behavior remain to be isolated. Instruction counts are withheld from
the accepted summary; raw snapshots and standalone diagnostics are preserved.


[Summary and provenance](../../data/eci/board-evidence/hot-pmu-516232-20261005.json)
retain raw counter validation and accepted statistics. The
[static executed-path baseline](#historical-exact-binary-instruction-baseline--october-5)
uses the exact pre-PMU binaries and explicit branch assumptions independently
of the unreliable retired-instruction event. It does not replace cycle or
cache measurement.

## Contiguous-layout regression analysis — October 10

This historical comparison motivates optimization; it is not the active echo
baseline. It compares 1,000 hot kernel-off add/null-RPC requests per revision:
516232 with corrected module and buffered runtime versus inline-TX software
`8a1521ee` on unchanged 518689 hardware `45cbfe52`. Runtime probes and FPGA
recording were enabled. The latter cohort comes from 4,000 measured requests
plus 32 warmups; every measured TX has one control forward/ack and no overflow
invalidation. [Report](../../data/eci/board-evidence/dpdk-inline-hot-cold-518689-20261010.json)
and [reproduction inputs](../../data/eci/board-evidence/dpdk-inline-hot-cold-518689-20261010-inputs/README.md)
retain artifacts and complete distributions. These old recorders retain the
[legacy timestamp and CDC limitations](../hardware/tracing.md#timestamp-capture-point-and-legacy-queue-bias).

The intermediate maximum-capacity reservation forced overflow for an eight-byte
reply. Exact-size inline reservation reduced hot RTT from 14.350 to 12.760 µs
and MAC median from 5.670 to 4.165 µs. TX invalidation median fell from 1,945 to
955 ns. This sequential software comparison does not assign all savings to a
single operation. The earlier 516232 MAC median was 3.700 µs; the analysis below
localizes the remaining difference. No timing closure is established.

### FPGA localization

The [FPGA-only comparison](../../data/eci/board-evidence/nullrpc-hardware-regression-20261010/summary.json)
rejoins all **1,000 hot kernel-off requests from each revision** to the actual
DCS control messages, using each capture's own map. No CPU epoch fit is used.
Every request conserves its MAC interval. Mean MAC latency rises **464.830 ns**:
**62.985 ns outside host turnaround**, and **401.845 ns between the DCS RX
response and returning TX request**. Thus most of the remaining regression is
not in the measured FPGA packet-processing intervals. The latter interval still
contains CPU execution and ECI transit; it is not a pure CPU measurement.

| FPGA-clock interval | Old median (ns) | New median (ns) | Mean delta (ns) |
| --- | ---: | ---: | ---: |
| RX MAC → internal descriptor | 215 | 215 | −1.020 |
| Descriptor → DCS RX response | 275 | 290 | +10.450 |
| DCS RX response → DCS TX request (host/link turnaround) | 1880 | 2285 | +401.845 |
| DCS TX request → internal commit | 205 | 215 | +10.030 |
| Commit → control invalidation complete | 950 | 955 | +4.750 |
| Invalidation complete → TX submission | 15 | 45 | +29.840 |
| TX submission → MAC | 155 | 165 | +8.935 |

Means provide the additive comparison; independently computed medians do not.
The source/trace findings are:

- **RX prefix assembly:** the old metadata-only short RPC took `readPktBuf →
  sendDummyData`; the contiguous implementation takes `readPktBuf → readBody →
  nextBody → sendPktData`. Its extra two FSM states are in
  [DcsRxAxiRouter](../../hw/src/lauberhorn/host/eci/DcsRxAxiRouter.scala).
  The app-side response interval rises 175 → 190 ns at the median; the measured
  app/sys crossing interval partly offsets this in the mean.
- **Inline restore:** [DcsTxAxiRouter](../../hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala)
  now runs `inlineCmd/inlineData/inlineResp` after invalidation and gates
  `txDesc.valid` with `!inlinePending`. Restoring the saved inline bytes gives
  them priority over overflow aliases. This accounts for the clear **30 ns**
  completion-to-submit increase; it is not an overflow CL invalidation.
- **Short replies now traverse packet DMA:** old `EciHostTxCtrlInfo.unpackTo`
  supplied the eight bytes in metadata with `buffer.size = 0`; the new version
  sets `buffer.size = len`. The trace confirms `DmaLength=0 / TxNoDmaRead` before,
  versus `DmaLength=8 / TxAfterDmaRead` now. RPC payload handling and UDP metadata/
  payload arbitration also changed. Their net submission-to-MAC increase is
  about **9 ns mean / 10 ns median**, not a separately additive DMA-roundtrip cost.
- **Small DCS ingress increase:** source-delay-corrected app/sys joins place
  almost all of its +10 ns after app-side request acceptance. A bounded DCS
  state check shows the same `R12`, `s1__3pRA3`, `RDD` action in both revisions;
  sys request-to-RDD is 125 ns in both representative requests, then RDD-to-commit
  grows 80 → 90 ns. No corresponding added state was found in the unchanged
  interface routing or the endpoint read FSM. Its exact two-cycle root cause
  remains unresolved; the trace localizes it rather than proving a source cause.

The forward/ack median is **245 ns in both**. Representative requests have no
additional ECI request between RX response and TX doorbell. This rules out an
extra CL operation in those examples, but does not turn the 402 ns host/link
interval into a calibrated CPU-only difference. The old 3.700 µs and new 4.165 µs
MAC medians remain valid. No board reset, new measurement, hardware change or
timing optimization was performed for this analysis.

### CPU localization

The [CPU-side comparison](../../data/eci/board-evidence/nullrpc-software-regression-20261010/summary.json)
uses the same 1,000 measured hot kernel-off requests per revision. Raw CPU
intervals from RX-read unblock to TX entry increase **356.930 ns**, directly
accounting for most of the **401.845 ns** FPGA-observed host-turnaround growth.
There is no CPU/FPGA clock fit in this subtraction. All eight adjacent CPU
intervals conserve the CPU interval for every request.

| Raw CPU interval | Old mean (ns) | New mean (ns) | Increase (ns) |
| --- | ---: | ---: | ---: |
| RX unpack after read unblock | 150.69 | 145.17 | −5.52 |
| RX return → unmarshal entry | 291.12 | 362.27 | +71.15 |
| Unmarshal | 295.72 | 304.53 | +8.81 |
| Unmarshal exit → handler entry | 83.83 | 97.36 | +13.53 |
| Handler-labelled interval | 189.63 | 245.64 | +56.01 |
| Handler exit → marshal entry | 121.01 | 130.47 | +9.46 |
| Marshal-labelled interval | 197.68 | 392.95 | +195.27 |
| Marshal exit → TX entry | 134.43 | 142.65 | +8.22 |
| **RX unblock → TX entry** | **1464.11** | **1821.04** | **+356.93** |

Source and deployed AArch64 assembly identify substantial additional work:

- **Marshalling now traverses the response twice.** `xdr_response_capacity()`
  calls `xdr_sizeof`, then `xdr_marshal_resp()` encodes it. The marshal probes
  also now enclose `core_eci_tx_get_buffer`, including its control-bit write and
  `dmb sy`. The old code encoded directly into an already available staging
  buffer. The measured +195 ns belongs to this whole interval; it is not an
  isolated measurement of `xdr_sizeof`, and some work moved from the TX stage.
- **Request setup copies much more tracing state.** `rpc_handle_call` zeroes
  672 bytes instead of 288; the trace copy grows from 224 to 608 bytes and
  changes from inline vector loads/stores to a `memcpy` call. The new row carries
  eleven 32-byte PMU snapshots even though every measured row has `pmu_mode=0`
  and `pmu_mask=0`. This extra setup lies in the +71 ns pre-unmarshal interval.
- **The handler label includes added instrumentation.** The benchmark handler
  source is unchanged. The runtime now calls
  `server_trace_event(LH_TRACE_ENTRY_RETURN)` before the handler-exit timestamp,
  even with event output disabled; the early-return call/check still executes.
  Stage timestamps now pass through `server_trace_stage` PMU checks in mode zero,
  and entry timestamp stores were added. Therefore the +56 ns is not evidence
  that the arithmetic handler itself became slower.

The remaining **44.915 ns** is the combined change outside these common CPU
boundaries: RX response transit/completion/probe tail and TX entry-to-doorbell
preparation/probe/request transit. The new pre-doorbell clock call is in that
remainder, alongside savings and work moved out of the old copying TX path.
The old capture has no pre-doorbell probe, so its individual contributions
cannot be separated exactly from existing data.

The per-run minimum clock-call calibration changed from 60 to 80 ns. The table
intentionally uses raw deltas: this calibration does not remove disabled-PMU
wrapper checks, row copying or the disabled event call, and subtracting it from
some phases while comparing raw MAC latency would mix accounting conventions.
These are instrumented runtime results, not tracing-free application latency.

The concrete optimization candidates are a known encoded-size/bound for the
fixed-size reply (avoiding the extra XDR sizing traversal), compact hot-path
trace state without unused PMU arrays, and disabled-instrumentation fast paths.
Exact nanosecond attribution to each candidate still needs an isolated control;
this analysis neither implements those changes nor claims a new board result.
