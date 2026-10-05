# Hot-RPC PMU profiling

The optional runtime profiler uses task-bound Linux perf events, pinned as one
non-multiplexed group, with direct AArch64 userspace counter reads. Events are
`INST_RETIRED` (0x08), native 64-bit `CPU_CYCLES` (0x11), `L1D_CACHE_REFILL`
(0x03) and `L2D_CACHE_REFILL` (0x17), in that CSV index order. Counts exclude
kernel and hypervisor execution. The runtime image includes `lh-pmu-probe`.

## Validate before interpreting

On October 5, zuestoll14's instruction event **failed independent validation**.
Do not publish instruction counts or IPC from this campaign, even for intervals
whose deltas are positive. See the evidence below. Counter availability and a
successful simple loop are insufficient validation.

After checking reservations and staging the cross-built, matched deployment:

1. Enable `/proc/sys/kernel/perf_user_access` as root for the experiment. Record
   its previous value. Do not disable access while a profiling worker is still
   executing counter reads; restore it after that process has stopped or rebooted.
2. Run `taskset -c 0 lh-pmu-probe` before launching workers. It checks a known
   instruction loop, 100,000 adjacent snapshot pairs, and small/large memory
   footprints, including comparison against kernel perf `read()` counts.
   Exit 2 means instruction validation failed while the cache-response check
   passed; exit 1 means another validation failed. This is not an RPC failure.
3. For a buffered traced service, set `LAUBERHORN_PMU=1` and the existing
   `LAUBERHORN_SERVER_TRACE_CSV`/application CSV path. Initialization happens
   before worker registration. Unavailable or unscheduled counters fail closed.
4. `LAUBERHORN_PMU_SKIP_ROWS=N` skips snapshots for the first N completed rows
   of each thread. `LAUBERHORN_PMU_WHOLE_ROWS=M` then takes only RX-entry,
   RX-return and TX-return snapshots for M rows; subsequent rows have detailed
   snapshots. Counts are per thread, include warmups, and do not count unsuccessful
   RX polls. Set both to zero for detailed profiling throughout.
5. Stop traffic, then export once with SIGUSR2. Never dump while measuring.
   Retain CSV, status, calibration, client timings and software/FPGA provenance.

`tools/enzian/pmu_breakdown.py DIRECTORY` validates three consecutive cohorts
(`off.csv`, `coarse.csv`, `detailed.csv`, plus `server/server.csv` and its status).
Defaults are 1,000 measured requests and 16 warmups per cohort. It checks IDs,
CPU/worker identity, success, snapshot masks and additive conservation. Negative
counts are errors. Only after independent instruction validation fails may
`--exclude-instructions` withhold that entire event while retaining every request
for cycles/cache statistics. It never clamps negatives or silently drops samples.

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

DUT zuestoll14, DPDK client zuestoll07, all 3,048 requests correct on CPU44 / worker0
(3 × 1,000 measured plus 16 warmups each). One measured `add` thread and a separate
four-thread `mul` domain were registered; no flush traffic or deliberate reschedule
was issued. Kernel activation tracing was off. All 3,048 buffered rows exported
once, zero drops. TAD/kernel health passed. The matched hardware was pipeline
516232 SHA `6f5fa076adbad2002681d3051c7056522929b7a1`, with corrected module
`af0f64fe`; timing remains open. No FPGA or scheduling changes were made. Tracing remained armed through module
load and was frozen after the campaign. The final FPGA `sample_lost` flag was
set (DMA error zero), so this is not a loss-free FPGA capture; PMU attribution
uses the loss-free runtime records and does not depend on FPGA trace events.

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

Evidence: [summary/provenance](../../data/eci/board-evidence/hot-pmu-516232-20261005.json).
Local full records: `out/pmu-hot-516232/data/{server/server.csv,per-request.csv,pmu-summary.json}`;
independent probes and matched boot evidence are under `out/pmu-hot-516232/`.

The follow-up [static executed-path count](hot-static-instructions.md) uses the
exact pre-PMU binaries and successful hot `add` branch directions. It provides
instruction counts independently of the blocked retired-instruction counter.
