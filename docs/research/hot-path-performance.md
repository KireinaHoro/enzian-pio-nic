# Hot-path performance analysis

This topic connects executed software instructions with the hardware/software
cache-line layout and coherence work needed for a hot RPC. The optimization
objectives are fewer instructions on defined paths and less transport work,
while preserving request semantics, ownership, isolation and progress.
Instruction counts, CPU time and end-to-end latency are separate outcomes.

## Analysis scope and comparison method

The [current transport layout](../hardware/architecture.md#eci-protocol-and-routing)
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

The [hardware path analysis](../hardware/rx-writeback.md) covers coherence and
writeback stages, cycle-accounting methodology and the proposed coupled RX-return
response path. That proposal is not the selected ABI. Its independent-response
service, message identity and large-response fallback questions remain decision
criteria. Analyze instruction savings and protocol savings together without
counting either as a measured gain before a matched comparison.

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
