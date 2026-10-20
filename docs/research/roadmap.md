# Research roadmap and open decisions

The [paper goals](paper-goals.md) define the claims, baselines and application
portfolio. This roadmap records unresolved decisions and their dependencies.
Implemented contracts and measured coverage live in the linked topic pages.

## Evidence and remaining claims

| Area | Established scope | Remaining claim or decision |
| --- | --- | --- |
| ECI ownership and scheduling | The 8 KiB physical-worker map, domain checks, cooperative DESCHED and mutable RX replay are implemented. [Implementation](../hardware/eci-implementation.md) and [finite board evidence](eci-progress.md). | Adversarial isolation, resource pressure and shared-path progress beyond the recorded finite cases; full-system refinement. |
| Formal verification | Selected bounded resource/scheduling/kill compositions and negative controls are recorded in [formal verification](../formal/README.md). | Larger compositions, concrete RTL correspondence, independent service and justified fairness assumptions. |
| Hot-path evaluation | [DPDK baseline](dpdk-baseline.md), [measurement methodology](hot-measurement.md) and [CPU/coherence analysis](hot-path-performance.md) define the reproducible comparisons and limitations. | Controlled paper acquisition, loaded tails, instruction-count and HW/SW cache-line optimization guided by measurements. |
| Caladan baseline | [CS-A64](caladan-enzian.md) has finite ARM, directpath, shared-echo and CPU-trace acceptance. | Controlled wire/loaded evaluation, busy-owner reclamation and the memcached comparison. |
| Memcached | [Native integration](../applications/memcached.md) has bounded compatibility, multi-tenant switching and qualified latency results. | Compare against Caladan with matched semantics/resources; broader workload/pool matrix and corrected timestamp analysis. |
| Go and etcd | [Go scheduling](../proposals/go-runtime.md), [HOST_WORK](../proposals/host-work.md) and [Raft/etcd](../applications/etcd.md) are designs. | Implement and validate dependencies before application integration; notification/allocation ABI and persistence semantics remain open. |
| Recovery | [Failure policy](../software/scheduler.md#failure-and-reset-boundary) fences unsupported exits and fatal activation failures; lifetime codes are not recycled. | Whole-domain resource cleanup, safe reuse and application timeout semantics. |
| Physical implementation | [Routed results and constraints](../hardware/physical-implementation.md) distinguish functional testing from timing closure. | Timing closure; optimization remains paused. |

The dedicated scheduling campaign is closed at its recorded finite scope.
Additional concurrency, stalled-peer and tail cases belong to application-driven
validation; the closeout is not evidence that unrun cases passed. Dandelion is
[bracketed for the paper](../applications/dandelion.md) because sandbox overhead
dominates the end-to-end result.

## Application and runtime dependencies

Native Lauberhorn versus CS-A64 memcached is a main paper comparison. Match
application versions, bounded maintenance, operations, load generation and core
budgets before interpreting throughput or tails. The Linux FPGA-bypass control
is not the Caladan baseline. Userspace NFS still needs an implementation/version
and filesystem semantics selected.

The selected consensus direction is Go Raft followed by etcd. First establish
Go native null-RPC and nested calls with owned data, GC-safe transport waits and
bounded admission. HOST_WORK is a shared mechanism proposal: assigned RX
completion, unassigned-domain allocation, targeted maintenance and host-only
pause/resume are distinct requirements. Real memcached hash expansion is a
concrete acceptance case. The earlier raft-rs service sketch remains an
alternative reference, not the selected application implementation.

Service-owned events/timers and general resident-worker support remain in the
[service-execution proposal](../proposals/service-execution.md). Direct
downstream-to-original-client replies have no complete design; retain the brief
[tail-call boundary](../software/nested-rpc.md) rather than assuming forwarding
provides that protocol. Ordinary tasks remain outside isolated worker cores.

## Progress, isolation and recovery

Keep directory provisioning, CPU replacement initiation, independent writeback
and response delivery, and shared VC/credit resources explicit when assessing
progress. Coloring or matching cache/directory capacity alone cannot establish
end-to-end service. The selected [LCI-only ROM](../hardware/lauberhorn-rom.md)
and [formal resource model](../formal/resource-progress.md) replace the historical
auto-lock design; no forced-eviction policy or extra directory way is selected.

Use the [implemented slice checks and remaining integration coverage](../development/validation.md#remaining-dcs-integration-coverage)
to separate control routing from the full payload/THX path. Broader CPU
eviction/request races, independent VC order and simultaneous response/data
resource pressure remain relevant. Extend models when implementation exposes a
missing dependency; an old bounded result does not certify a changed contract.

Specify receive-critical-section versus whole-application timeouts, timer
start/reset points, kill granularity, reply/resource cleanup and lifetime-code
reuse certification. Reuse must follow protocol release or kernel no-return
plus access drain, not just initial-buffer bookkeeping. Keep close/claim,
activation, notification and transport ownership consistent through recovery.
Fairness guarantees require an explicit policy and evidence; run-to-finish does
not bound an unbounded handler or imply service-allocation fairness.

## Scaling and optional transport work

The [lookup/spill proposal](../proposals/lookup-spill.md) addresses finite tables.
Keep kernel/control bypass distinct from runtime-owned request spill, with packet
ownership, retry and freeing specified for each. Evaluate beyond table capacity
before claiming scalable admission. Scheduler queue-full requests enter the
implemented discard path; overload still needs buffer/resource and isolation
checks, not an assumption of losslessness.

The [RX-return response proposal](../proposals/rx-return-responses.md#evaluation-and-decision-criteria)
is an optional application-performance experiment after the correct independent
RX/TX baseline. Measure response-size fit, CPU work, latency, throughput and
coherence contention; preserve independent return service and large-response
fallback. It is not a prerequisite for the implemented address/DESCHED contract.

[TCP and Protobuf integration toward gRPC](../proposals/grpc.md) remains below
active first-paper applications. Choose integration order from concrete
application dependencies. Fully offloaded gRPC is a potential later paper.
Venue/cycle, NFS implementation and final workload/resource budgets remain
research decisions rather than implementation guarantees.
