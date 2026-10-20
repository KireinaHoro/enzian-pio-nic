# Runtime and application continuation

## Accepted direction

The [October 13 Go/etcd design](../docs/research/etcd.md) selects HOST_WORK
(formerly HOST_WAKE) to return assigned RX waiters to host work. Allocation for
unassigned domains is separate. Preserve worker-core–endpoint affinity.
The handoff authorizes documentation and naming only, not runtime/kernel/RTL
implementation. Planned work is Go adaptation, HOST_WORK with memcached
hash-expansion validation, native Go null-RPC with nested calls, then Go Raft
and etcd. Historical HOST_WAKE holds do not override this selected direction.

Caladan is the primary baseline; follow its [port and calibration plan](../docs/research/caladan-enzian.md)
when that work resumes. Port and measurement acceptance are pending in this
handoff. Integrate external TCP/Protobuf work after higher-priority paper
applications, following the [protocol roadmap](../docs/research/grpc-roadmap.md).

## Scheduling and isolation evidence

The bounded Goal 2 models, 8 KiB physical-worker address layout and cooperative
DESCHED implementation are complete within their recorded scope. Pipeline
516112 passed all CI jobs and two cold-boot 100-RPC trials after payload
disclosure and inactive-domain corrections. Never substitute vulnerable 515944.
Corrected 516232 module `af0f64fe` passed two fresh cold boots (200 measured plus
32 warmup RPCs) after its first idle-activation failure; preserve that original
failure capture. See the [consolidated evidence](../docs/research/roadmap.md#current-status--october-8-consolidation).

October 5 GSYNC one-shot early NACK tests covered 240 controlled RPCs and one/two
observed stalled peers. See the [GSYNC record](../docs/hardware/gsync-worker-reproduction.md).
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
