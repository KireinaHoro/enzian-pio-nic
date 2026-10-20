# Runtime and application continuation

## Accepted direction

The [October 13 Go/etcd design](../docs/proposals/go-etcd.md) selects HOST_WORK
(formerly HOST_WAKE) to return assigned RX waiters to host work. Allocation for
unassigned domains is separate. Preserve worker-core–endpoint affinity.
The handoff authorizes documentation and naming only, not runtime/kernel/RTL
implementation. Planned work is Go adaptation, HOST_WORK with memcached
hash-expansion validation, native Go null-RPC with nested calls, then Go Raft
and etcd. Historical HOST_WAKE holds do not override this selected direction.

Caladan is the primary baseline; follow its [port and calibration plan](../docs/research/caladan-enzian.md)
when that work resumes. Port and measurement acceptance are pending in this
handoff. Integrate external TCP/Protobuf work after higher-priority paper
applications, following the [protocol roadmap](../docs/proposals/grpc.md).

## Scheduling and isolation evidence

The bounded Goal 2 models, 8 KiB physical-worker address layout and cooperative
DESCHED implementation are complete within their recorded scope. Pipeline
516112 passed all CI jobs and two cold-boot 100-RPC trials after payload
disclosure and inactive-domain corrections. Never substitute vulnerable 515944.
Corrected 516232 module `af0f64fe` passed two fresh cold boots (200 measured plus
32 warmup RPCs) after its first idle-activation failure; preserve that original
failure capture. See the [consolidated evidence](../docs/research/roadmap.md#current-status--october-8-consolidation).

October 5 GSYNC one-shot early NACK tests covered 240 controlled RPCs and one/two
observed stalled peers. See the [GSYNC record](../docs/research/eci-progress.md#controlled-gsync-results).
The October 8 direction closes the dedicated multi-worker campaign at that
finite coverage; additional four-worker cases belong to application validation.
Do not report three stalled peers, adversarial isolation/recovery or full-system
progress as established by those tests. Selected QP/DCU coloring is implemented;
its board progress acceptance needs a concrete follow-up test. Service
events/timers and complete kill/exit resource recovery are implementation work.

## Scheduling control validation and continuation

The [control contract](../docs/software/scheduling-control-interface.md) is the
human-facing protocol reference. Historical source integration originated on
`feat/cooperative-yield`; that branch name is not current work state.

The [October 3 finite board record](../data/eci/board-evidence/cooperative-20261003-contention.json)
records two cold boots at a 133.281505 ms RX hold. Its loss-free measured
interval contains 5,926 ordinary handoffs with matching activation parities and
zero scheduling IRQ counter change,
including 4,710 overlapping three full-length peer stalls. Concurrent control
reads and bypass traffic completed with clean TAD/kernel checks. This record
has its own finite exposure and does not extend the October 5 GSYNC test's
one/two observed-stalled-peer scope or establish infinite-stall progress.

The October 12 naming-validation record in the former control-page preamble
reported regenerated `devHdrs`, a `kmod` build, and `runtime-tests` plus
`runtime-interface`. All 23 generated headers matched after symbol normalization.
`CooperativeYieldSim`/`CooperativeAllocationSim` passed six tests; Enzian,
memcached and latency-analysis suites passed 69, 13 and 14 tests. Historical-map
and saved-event compatibility suites subsequently passed nine and five tests.
These are recorded checks for the rename, not new tests or a new board/timing
result from this documentation scrub. The original record is retained in Git
at `e68535ee:docs/software/scheduling-control-interface.md`.

The former acceptance list requested matched CI and lifecycle/timeout/progress
trials, including an RX-hold boundary search at timer resolution, every exposed
L2C_TAD unit, and contention across two cold boots with one/multiple/all-but-one
stalled workers, active-peer handoffs and simultaneous bypass RX/TX. Do not
restart that dedicated campaign: the October 8 closeout assigns further cases
to application validation. Before proposing a specific check, reconcile it with
[the implementation ledger](../docs/hardware/eci-implementation.md) and later
application results. Retain matched artifacts/clocks, actual stall exposure,
nearest failing bound and fault traces for any authorized boundary trial.
The recorded finite RX-hold setting does not resolve the maximum-timeout
boundary or the earlier baseline kernel lockup. Before resuming either question,
check subsequent evidence for a resolution and select an application-driven
case; neither is permission to reopen the closed dedicated campaign.
The unresolved `lscpu`/`find_inode_fast` baseline lockup predates application
activation. Its preserved 132,696,064-byte trace has SHA-256
`de51121b463c8ac129a8cba416488979cc8b79fb3152dbef8dfcdcf48898b054`;
full original context is `3cd5f968:docs/hardware/eci-implementation.md`.
The trace showed bypass traffic and 347 paired invalidations, with no decoded
loss; no postfailure TAD snapshot was available. Forty stock-Linux queries and
a later matched baseline passed, which did not identify the cause. Check later
evidence before planning any reproduction.

Shared-control traffic must be included when assessing progress behind a
blocking info read; model independence alone does not establish bus progress.

HOST_WORK documentation does not authorize adding fields, cookies, discriminators,
subscriptions or dead stubs. PCIe scheduling remains outside this stage. Timing
work remains subject to the [physical-implementation pause](physical-implementation.md).

## Application evidence and next checks

- Dandelion's October 8 zuestoll14/zuestoll07 campaign passed atomic, DAG and
  concurrent-chain gates plus 9,000 measured requests across allocation and
  tracing controls. Depth-2 children overlapped on CPUs 45–47 with a parent on
  CPU 44 after bounded allocation warmup. Use the [integration plan](../docs/research/dandelion.md#active-integration--october-8)
  and [waterfalls and limits](../docs/research/dandelion-measurement.md) when
  resuming application work; this does not establish broader paper claims.
- Native memcached passed two five-instance/one-worker cohorts sharing four
  physical workers, with ordinary yields and process switching in traces.
  [Evidence](../docs/research/memcached.md#five-instances-on-four-workers-october-10).
- Five instances declaring four workers each passed 61,440 synthetic requests
  across single/pair/all-tenant bursts and tracing controls. The complete batched
  trace shows each tenant reaching four assigned workers and 2,477 process
  switches. [Evidence](../docs/research/memcached.md#four-workers-per-tenant-and-variable-bursts-october-10).
  This bounded campaign is closed; broader pool-size/performance measurements
  need separate workloads and controls.
- Joined request tracing subsequently produced results, superseding the earlier
  blanket tracing follow-up. Corrected memcached timing and CDC bounds are the
  specific remaining checks in [measurements](measurements.md), with the
  [campaign closeout](../docs/research/memcached.md#campaign-closeout-and-per-request-tracing-follow-up)
  retaining its historical scope.

Read [hardware state](hardware-state.md) before reusing a board. No historical
cohort or reservation here describes the live DUT.

## Historical imported-runtime probes

September 10 probes used job 2812450 / `aa32b1af` runtime and generated headers,
Sven application source `9e251814`, and Dandelion snapshot `69c3088f` (MMU engine).
Peer add, mul and standalone adder each passed 100 calls; Dandelion registered
and returned the expected 1×1 matrix in a 148-byte reply. This single-function
smoke did not test composition or reconcile worker-index/CPU-ID mapping; later
Dandelion integration supersedes that scope. Nested adder timed out with zero
peer calls: the generated unaligned destination accessor truncated the address,
producing ARP for 129.129.0.0. Cookie/XID/program packing and endpoint/byte-order
integration also needed coordinated fixes. These are resolved historical import
findings, not instructions to patch generated headers or reopen merge blockers.

A separate standalone-adder setup failed in probe_versions before application
execution; its retry passed. SIGTERM subsequently left zombie/idle worker state,
so shutdown was not clean; final verified power-off ended that attempt. Retain
`out/hardware-tests/nested-rpc-20260910/`: `peer-campaign/`, `peer-followup/`,
`student-retry/`, per-application evidence directories, `provenance.json` and
`descriptor-probe.{c,log}`. Shared backup:
`/scratch/pengxu/nested-rt-20260910/evidence/`. Earlier gateway-only add/mul
trials are separate from peer traffic. Failed tcpdump setup attempts were excluded;
replacement PCAP timestamps are userspace observations without drop accounting,
not latency evidence. The retired packaging must be reproduced from its
historical checkout: `93ee25a6709fb8508d30e8298368cc11ecbc6f93:tools/enzian/student_build.nix`
(and its adjacent `student-*.patch` files).

## Historical B4 parking and recovery

On 512199 / `754ecb4`, two cold boots passed 100 mul RPCs each; two old-helper
boots lacking the CCPI report aborted at shell access. Six no-reset signal cases
passed before a hung-handler case hit an L2 timeout; remaining cases were unrun.
Only bounded trace head/tail windows survive in `out/enzian/b4-512199/`, not a
full lossless reconstruction. The separate DECERR diagnosis does not establish
the board's unrecorded RRESP or exact wakeup. Preserved diagnosis inputs are
`out/enzian/b4-512199/hang-capture/hang-failure-tail.jsonl`,
`hang-capture/timeout-app-ila.csv` under that same root, and `analysis/` with
`decerr_repro.sv`, `crossbar_repro.sv`, the positive control and Verilator logs.
The full retired investigation is in Git at
`692242f9:docs/hardware/b4-l2-timeout-512199.md`.

The independent read/write DECERR accounting repair (verilog-axi `5de055e`,
blocks `b527f9c`) was accepted on simulation evidence; further board reproduction
was not a merge gate. Bitstream 512199 lacks that repair. Its kernel-only parking
result below establishes prevention of premature access within the tested cases,
not board validation of the crossbar fix. The maintained
[DECERR regression](../docs/development/validation.md#axi-decode-error-regression)
checks mapped-transaction progress after an unmapped transaction.

Kernel-only parking patch `5ec614c` on the same hardware passed 100 mul RPCs and
20/20 recovery cases over two no-reset rounds: INT/TERM/KILL before/after dispatch,
hung-handler stop, dirty uncommitted TX, stuck BUSY and watchdog. All recorded
phase-2 retirement and unrelated mul checks passed without checked TAD/kernel
faults or trace drops. This was the old incoming/process recovery ABI, not full
nested recovery or current cooperative DESCHED acceptance. The old runner is
retired. Setup failures (missing lane report, ARP/link failure, serial framing,
full root filesystem) remain separate from passing cases. Evidence:
`out/worker-park-test/{provenance.json,recovery-512199-worker-park-final/summary.json,recovery-audit.json,recovery-evidence/}`;
shared `/scratch/pengxu/lh-worker-park-recovery-evidence/`.
Image SHA256 `be1f319b2d802dd1c161cea505aa9855f5e91ea5a4fbe6f5cfbb26ed95ec1ca2`;
patch SHA256 `aa7a2475d2b47b2d5ba79fe30d0e78a8cf5fe5bc3726641d4f302be599eff3be`.

## Historical bundled nested-RPC validation

September 18 retained hardware `754ecb4` and its ABI collateral, parking patch
`5ec614c`, runtime tracing `dac2450`, inline-TX repair `a4600bc`, fixture `006cce4`.
The repaired software image was `/scratch/pengxu/lh-512199-bundled-fixed.img`,
SHA256 `3d6a2578764b637eff9da44ea61e557ba34ea72749b4c80be62cd96309e57114`;
fixture SHA256 `80109e3777434c0f24ce92e75f602c49c7de34e5ff6cbd8a6abd601291711237`.
This is software validation on the old hardware, not acceptance of later RTL.

- 100 mul calls produced 100 ordered, ID-correlated server rows on CPU 44 and
  software plots; no new CPU/FPGA clock correlation was established.
- 88/88 peer checks observed 396 nested packets: remote/local calls and forwards
  at 48/512/1024 bytes, asymmetric endpoint fields, complete payloads and eight
  reordered futures. No peer packets in local cases is not a full CMAC proof.
- Default-stack rejection, explicit errors, oversized encoding and pool exhaustion
  returned expected errors with subsequent progress. Twenty cycles checked cancel,
  16-of-32 outgoing admission, retirement and reuse. Incoming saturation yielded
  16 deadline completions and 16 hardware errors. A quiet deadline completed in
  about 1.99 s (one-second deadline plus sweep); late replies did not corrupt the
  subsequent cases. The final 109 terminal rows included 89 deferred, six forwarded,
  14 inline and four expected errors; hardware admission rejections have no row.
- Killing with 16 confirmed outgoing requests completed recovery phases 0/1/2;
  replacement passed 31/31 checks and restored capacity without reset/reload.
  Old replies arrived before fresh submissions: an identical port/XID race across
  process lifetimes was not tested and remains a distinct check.
- Four-worker add/mul each passed 100 serial peer calls handled on CPU 44;
  registration of four workers does not establish simultaneous four-core work.
  A later third gated boot passed another 100 add calls.

The short-wire-length fixture error and first pending-kill trial with disabled
trace events were retained as failures/incomplete trials. The actual inline-TX
bug copied bytes after rather than inside the descriptor; repair regressions
checked lengths 1–24 and adjacent-byte preservation before the full pass.
Evidence: `out/bundled-validation/`, shared `/scratch/pengxu/lh-bundled-validation/`,
`nested-results-final.json`, `kill2/{kill-result,after-kill-results}.json`, manifests,
packet hex, failed trials and kernel traces. Historical scope excluded explicit
CSR readback, full local trace correlation, timing acceptance and the separate
startup/student/Dandelion gates. Later application results take precedence.

Full provenance and additional original hashes are in Git at
`8f05507a:docs/development/hardware-test-results.md`; these evidence summaries
authorize no new hardware work and describe no live reservation or service.

## Software documentation follow-up

The public `sw/include/lauberhorn.h` watchdog comment says a zero limit is
supported, but `sw/kmod/chrdev.c` rejects every WATCHDOG ioctl with EOPNOTSUPP.
The human API documentation now follows the kernel. Reconcile that header comment
in a source-maintenance change; this documentation pass changed no code.

## Nested RPC follow-up

The September 23 nested-runtime merge accepted the bounded historical campaign
above; it did not establish current cooperative-ECI recovery. Runtime import
provenance is Sven Glinz's platform `9e2518140ecf4423f0f01b8caded4ad1f708cf62`,
recorded in `9c3feda`; callbacks were subsequently replaced by managed outcomes.
The obsolete B1–B6 ledger is available in Git before this documentation migration.

Remaining checks: identical endpoint-port/XID reuse with old replies racing a
replacement process once lifecycle recovery supports reuse; broader mixed-process
admission/overload progress; full local-delivery trace correlation; explicit
in-flight CSR readback. Historical cancellation and quiet-deadline results do not
establish deadline activation in the current parked-domain implementation.
Dandelion composition results are recorded in its application measurement topic;
the old B6 hold is superseded. Timing optimization remains paused.

Resolved descriptor-generator and callback bugs, interim dirty-tree builds and
old merge checklists are retired to Git history. Original diagnostic evidence
pointers remain recoverable there (`out/b1/`, `out/b2-b6/`, `out/b3-20260917/`,
`out/reply-context-20260917/`, `out/merge-20260923/`); no raw evidence was removed.
