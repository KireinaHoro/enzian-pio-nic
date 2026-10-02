# Pending features and design decisions

Survey: 2026-09-08, base `b86c68c`. Maintainer priority: Dandelion and other first-paper applications come before TCP/Protobuf integration. Merge the finished protocol projects soon to limit drift; fully offloaded gRPC is a potential later paper. Other ordering below is a survey recommendation, not an approved schedule. Keep unresolved choices explicit; do not implement a proposal merely because it appears here.

The nested RPC integration is approved for master as of 2026-09-23. Its
[merge disposition and remaining tests](../software/nested-rpc-blockers.md#merge-disposition--2026-09-23)
record accepted timing limitations, the cross-process late-reply race to test,
and the startup workflow explanation. Dandelion validation is reserved until
the maintainer's scheduler/critical-section design changes are integrated.

## Work supported by repository evidence

| Work | Current evidence / gap | Completion evidence to seek |
| --- | --- | --- |
| Scheduling robustness under pressure | `hw/src/lauberhorn/Scheduler.scala`: queue-full drop has buffer-free FIXME; fairness and non-retrying-core recovery TODOs | No buffer leak under saturation; bounded progress/recovery; documented policy and multi-process stress |
| Scalable service/process/session tables | `docs/hardware/lookup-spill.md` proposes hardware caches and typed software spill ownership | End-to-end admission, eviction, ownership and packet lifetime tests beyond table capacity |
| Nested RPC usable from applications | Call encoder/reply decoder and nested simulation exist; hardware support does not imply C-runtime completion support | Runtime API and continuation lifetime, reply dispatch, error/timeout cleanup, application demo |
| Coherence progress and queue-pair placement | Goal 1 complete as a bounded modeling/layout milestone: selected 8 KiB physical-worker blocks, domain encoding and reproducible positive/negative models | Goal 2 scheduling model and replacement implementation; targeted DCS RTL validation below, followed by adversarial board traces |
| TCP and Protobuf integration | Finished external student projects in `../flavian-tcp` and `../elena-protobuf`; unmerged here | Adapt code/dependencies and validate host contracts after higher-priority applications; [follow-on roadmap](grpc-roadmap.md) |
| Data movement and decode extensions | Current C runtime performs XDR; generator TODO suggests coupled worker protocol | Chosen ABI and workloads; measured benefit; large-message strategy independently validated |
| Reproducible evaluation | Trace/latency tooling and descheduling microbenchmark exist; historical examples are not a full evaluation | [Paper evidence matrix](paper-goals.md), reproducible raw data and controls |
| Development consistency | Build/CI/manual instructions have drift; PCIe contains unfinished preemption | See [workflow](../development/workflow.md) and [validation](../development/validation.md); repair only within a separately scoped implementation task |

The kernel/control bypass and runtime-owned request spill planes are intentionally distinct in the spill proposal. Preserve that distinction when designing new descriptors; establish who frees packet storage and owns retry before adding a message type.

## Confirmed design intent (maintainer, 2026-09-08)

- Build the full RPC system on published 2F2F; demonstrate low latency and efficiency while retaining OS flexibility and security. [Paper scope and application portfolio](paper-goals.md).
- Primary baseline: Demikernel. Planned applications: Dandelion, RPC server, userspace NFS, memcached, and consensus such as etcd/Raft.
- `../sven-dandelion/{report,platform,dandelion}` contains the student report, mock platform/runtime extensions and Dandelion port. See [source map and limitations](dandelion.md); these are external reference trees, not merged support in this checkout.
- Worker cores are isolated and never shared with ordinary Linux tasks. RPC tasks run to finish. A timer should kill a task stuck in the 2F2F receive critical section, or an application timeout should bound it; precise policy is still open. Current `BUG_ON(killed)` is an implementation gap, not that intended recovery guarantee.
- TCP and Protobuf student platform/report trees are located and documented in [TCP](tcp.md) and [Protobuf](protobuf.md). Merge soon to avoid drift, at lower priority than Dandelion and the other first-paper applications. Fully offloaded gRPC is a possible subsequent paper.
- Updated 2026-09-30: physical-worker-owned QPs and the [8 KiB layout/domain encoding](../hardware/coherence-progress.md#concrete-domain-qualified-pa-encoding) are selected for the router-free replacement. Current thread-owned routing remains the implementation baseline.

## Scheduling verification milestones (maintainer, 2026-09-30)

**Goal 1 is complete as the bounded modeling and layout milestone**, accepted to
proceed with Goal 2 modeling and implementation. The [verification report](../hardware/steady-state-verification.md)
and [reproducible evidence](../../model/evidence/steady/nix-progress-evidence.json) record
what passed, the incomplete four-client run, and the remaining assumptions.
This disposition does not claim full hardware verification or whole-system HoL
freedom. [Goal 2 modeling](../hardware/scheduling-verification.md) now has modular
handoff/resource, reservation, thread-claim, host-wake, retirement and interrupt
contracts, with the original 26 baseline outcomes and stalled-worker extension checked.
The [race/checklist work](../software/scheduling-implementation-checks.md) adds
delayed wake acceptance/delivery, notification/ACK and fixed-pool reservation/claim cases;
local handoff sequencing is tracked as implementation obligations. The expanded
33-outcome Nix regression passed before the fixed-pool clarification. The updated
12-outcome focused suite passes; individual withdrawal and ordinary unavailable
dispatch are excluded. Goal 2 remains incomplete.
A joined host-driven handoff checks the Goal 1 resource-entry predicate. The
[scheduling composition](../hardware/scheduling-composition.md) now connects
wake/claim races and kill to RX/TX cleanup; its selected bounded matrix
is merged into `master`. Endpoint freshness and concrete
control/transport correspondence remain assumptions. Goal 2 must establish the settled entry contract and preserve unaffected
workers' progress during scheduling transitions, reusing the resource model.

### Active Goal 2 workstreams and compaction handoff (2026-10-01)

**The selected bounded modeling round for items 1 and 2 is complete.** Their
broader/refinement extensions remain open; item 3 remains an explicit
hardware-service contract, and item 4 is implementation and validation.
The completed modeling branch is merged into `master`. The selected LCI-only endpoint and its
checks are recorded below. The [module review](../hardware/model-review.md)
consolidates cache/transport composition, removes UL from the active models and
separates source/configuration/evidence; see the [model index](../../model/README.md).
The new [scheduling composition](../hardware/scheduling-composition.md) joins the
focused wake/claim contracts with kill and transport. Eleven positive cases, eleven
expected faults, three reachability witnesses and all 33 module checks completed.
The committed-TX kill/handoff case with a recurring peer completed by checkpoint
continuation after its original timeout; the report distinguishes that result
from the 24 completed Nix case derivations. This permits the next implementation
round under the listed service assumptions; it does not mark Goal 2 or hardware
verification complete.

| Item | Remaining work | Status / completion evidence |
| --- | --- | --- |
| 1 | Connect kill completion to actual transport resources, including TX/overflow and packet/buffer cleanup. | The [kill/transport composition](../hardware/kill-transport-composition.md) now imports kill control, cached transport and response properties, adding TX control/overflow writeback and packet ownership. Separate RX, committed-TX and abandoned-TX cases include a stalled worker and live peer. Exact RTL correspondence, multiple closing workers and shared packet queues remain open. Initial-buffer bookkeeping is not a proof of safe pool reuse: enforce protocol release or kernel no-return plus access drain (H20). |
| 2 | Complete broader combined liveness checks and stalled-peer transition cases. | Selected bounded round complete in [SchedulingComposition](../hardware/scheduling-composition.md): A → B → A, generation/ACK races, fixed-pool claims, whole-domain close and RX/TX cleanup. Selected matrix: eleven positive cases, eleven expected faults and three reachability witnesses complete, including committed-TX cleanup with a recurring peer and indefinitely held RX. Larger combinations, closing B, multiple closing workers and transition-time full-duplex bypass remain outside this matrix. [Combined state invariants](../hardware/retained-cache-composition.md) have bounded passing evidence; historical nonallocating combined temporal runs remain incomplete; the new auto-lock one-slot temporal runs complete and fail handoff/progress due to absent-target allocation. The new matrix bounds migration to A → B → A, notifications to two and TX to one control plus two overflow lines; it is not an unbounded proof. |
| 3 | Establish THX replacement and independent coherence-service guarantees. | **Blocked in the current RTL integration: the October 2 board trace reaches RX writeback `WDD` with no AXI write service.** The LCI-only ROM is selected; board acceptance has not passed. Resolve the allocation/provisioning contract below, then establish CPU replacement initiation and independent delivery through THX/ECI/DCS shared resources. Neither matched cache capacity nor per-DCU input arbitration alone establishes end-to-end progress. |
| 4 | Implement the serialization checks, then validate actual DCS RTL and hardware. | Pending. Implement the [kmod/HW checklist](../software/scheduling-implementation-checks.md), including close versus claim/activation, kill issue/ack versus reassignment, callback/command lifetime and immutable MMIO responses. Run the whole-slice cases below and matched-artifact hardware tests. Existing targeted RTL checks are partial evidence, not completion of this workstream. |

Settled context: use the selected router-free 8 KiB worker blocks and monotonic
19-bit domain-code allocation without reuse. Prefix-reuse certification is deferred;
exhaustion must not wrap and driver reload must not reuse codes while coherence
state survives. Ordinary handoff still uses PREEMPT/`lh_yield()` and one blocking
PreemptInfo read, with no kernel 2F2F CL access, ordinary cache flush or activation
commit write. Interrupts are for whole-domain kill, not cooperative scheduling.

#### Resume here: RX writeback integration blocker before the implementation plan

Hardware validation used revision `2699f58`,
[pipeline 515621](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/515621).
Hardware job [2861585](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2861585)
succeeded in CI. This is the existing routed/addressed design with the simplified DC and UL removed,
not the scheduling/address pivot. The [October 2 matched board trial](../development/hardware-test-results.md#simplified-lci-only-dc-trial--2026-10-02)
passed driver/revision checks but failed RPC discovery. The frozen trace shows a
data-bearing HAKD for RX `0x100`, followed by `WDD` without `WDDA`/LCIA; the RX
AXI router blocks write channels. The [trace comparison](../development/hardware-test-results.md#rx-regression-comparison-ra2-to-ra3--2026-10-02)
identifies the newly selected R12→RA3 permission change: earlier RX used
PSHA/SINV_H/no-data HAKD; exclusive RX now uses FLDX_EH/data-bearing HAKD. No
software store is required. The [actual-RTL reproducer](../development/validation.md#rx-writeback-regression-reproducer)
now confirms AW queues but WREADY stays low, and a test-only completion sink
releases WDDA/LCIA and the pending read. Preserve the board capture. Resolve RX
permission/writeback service and data disposition, then repeat board tests before starting the address/PREEMPT
implementation. Detailed implementation planning is deferred to the maintainer
switching to plan mode; no production pivot work has started.

The [selected LCI-only ROM](../hardware/lauberhorn-rom.md) is now generated by
Nix from a dedicated Perl source in the DC submodule and consumed by real-DCU
simulation and the Vivado input bundle. It retains Jasmin's A22 equations, uses
RA3 for R12, and removes LC/F32 and UL. NIC UL emission is removed. Directory
miss-to-invalid writes are suppressed and clean HAKD uses outstanding F31 context.
The selected ROM has 14 states and 35 permitted transitions. The linked document
records the precise checks and limitations; board validation failed at the RX
writeback integration boundary above.

The [LCI-only resource-model update](../hardware/lci-only-resource-model.md) now
composes LCIA release without UL with the existing scheduling modules. Focused
handoff/cache safety and liveness checks passed, as did combined state invariants
over 2,528,455 states. Full combined temporal verification remains open.

The [AXI response-contract checks](../hardware/axi-response-contract.md) now cover
earlier/queued/buffered reads, stop fencing and shared response service: five
positive cases, seven deliberate faults and two witnesses. THX same-line response
ordering, independent service and exclusion of target reads behind the held
doorbell remain hardware obligations, not established guarantees. The [kill-tail composition](../hardware/kill-transport-composition.md) connects
resource cleanup to the existing kill control model. The [new composition](../hardware/scheduling-composition.md) now joins broader
wake, reservation and repeated-handoff liveness by reusing component properties
and adding cross-component control/transport relations. Its selected bounded matrix
is checked; the report lists larger combinations not checked and hardware
correspondence obligations (including control freshness, MMIO/IRQ ordering and
RX-loan reuse). Reconcile the composed resource model with this
selected endpoint; earlier auto-lock counterexamples and conditional nonallocating
runs are not a new proof for the integrated ROM. No automatic ICI eviction policy is introduced.
The following auto-lock results remain historical motivation and regression
cases, not evidence that the selected non-locking endpoint has the same failure.

The [new directory RTL test](../hardware/directory-pressure.md#auto-lock-reachability-through-the-actual-directory)
reaches a full-set absent-LCI stall from reset through R12/RDDA fills and a clean
V21 eviction, using actual TSU/tag RAM/controller/auto-lock ROM and a test XPM
primitive model. It does not force full/hit outputs. Full-set **hit** invalidation
works; its entry remains charged until UL. That UL frees a way and permits the
absent-target LCI to complete. The test checks unrelated entries survive.

The [TLA+ rerun](../hardware/directory-pressure.md#tla-rerun-with-the-rtl-allocation-contract)
now finds the same problem after a from-empty worker/cache history (320 states)
and in complete one-slot combined scheduling checks (33,740 states). Safety
holds, but handoff completion and stable progress fail. Full-set hit and
independent-release recovery controls pass. This is a confirmed model-level
blocking condition to resolve, not merely an untested assumption.

1. Close the full NIC/runtime reachability gap: a fresh first RX does not itself
   invalidate the opposite parity. Check subsequent RX, overflow lines never
   read, retained old-domain lines, and preemption/kill against the actual engine.
The [existing DC bench audit](../hardware/directory-pressure.md#existing-dc-testbench-coverage-and-ici-entry-points)
finds an upstream `test_lci_stall_tsu_full` regression: it expects stalling and
supplies voluntary CPU evictions before retry. ICI has ROM transitions but no
current producer or explicit bench stimulus. A forced-eviction solution therefore
requires new control-path implementation and tests; it is not enabling an existing
controller feature.

2. Evaluate the non-locking control-path repair and response-fence contract;
   alternatively establish/enforce an auto-lock allocation guarantee for absent targets, or
   prove an independent release is always pending for the permitted sequences.
   Matched CPU/directory size does not by itself cover FPGA-created locks.
3. Then compose the guarantee with shared writeback/VC/credit service. The AXI
   gate model supplies a narrow candidate non-locking contract; extend it to the
   missing transition cases before selecting that protocol.

**No sidecar lock, extra way, forced eviction, cache flush or protocol switch is
selected or implemented. Only trace instrumentation has changed in production RTL.** Component reachability is
now simulation-tested; complete Lauberhorn/THX execution and board deadlock are
not established. Do not restart timing optimization or claim Goal 2 complete.

### Targeted DCS RTL simulation after replacement RTL is available

The main ECI suites use Scala `DcsAppMaster` in
[`NicSim.scala`](../../hw/test/src/lauberhorn/host/eci/NicSim.scala), abstracting
away the DCS RTL. Retain those fast suites, and add a separate targeted harness
that instantiates the actual DCS slice/DCU RTL with the replacement address path
once that RTL is available. Existing DCS sources alone do not constitute this
integration test. Cover:

- Slice read/write routing to DCUs: even/odd slices, scrambled color selection,
  selected worker/domain addresses, both control parities and all overflow lines;
  verify full address identity through responses and invalidations.
- Held reads with concurrent writes/invalidation traffic: stall one and
  then multiple workers while a peer and full-duplex bypass run; include retained TX/overflow responses alongside a held RX, and same-DCU versus independent-DCU traffic.
- Shared-path backpressure: exercise VC arbitration, read/write transaction
  managers, data-buffer gates, RTG occupancy, CDC and AXI queues; check whether a
  blocked DCU prevents another eligible DCU from admission or completion.
- Accounting and framing: check allocation/release, queue occupancy, beat counts,
  transaction identity and LCI/LCIA handshakes; map observed events to the model
  and turn its negative cases into directed sensitivity tests.

Record exact RTL/configuration revisions and bounded progress observations under
explicit environmental readiness assumptions. These simulations must exercise
DCS RTL rather than use `DcsAppMaster` as its substitute. They will test the
resource correspondence; finite passing traces still do not prove infinite-stall
liveness or undocumented ThunderX internals. Carry forward broader concurrent
model checks, THX credit assumptions and eventual board conformance separately.

## Remaining decisions

1. Model scheduling transitions (Goal 2) and implement the selected physical-worker/domain design. Establish concrete admission/resource guarantees and validate supported concurrency; the color-compatible seven-worker capacity is not yet a hardware progress guarantee.
2. Specify receive-critical-section versus whole-application timeout, timer start/reset points, kill granularity and safe resource/reply cleanup. Define service allocation fairness if a guarantee is needed; run-to-finish alone does not define it.
3. Validate the [managed outcome contract](../software/programming-model.md), including local reply forwarding for tail calls. Direct downstream-to-original-client replies remain separate protocol work. Implement [service-owned events/timers and resident-worker integration](../software/service-execution-design.md) in later runs.
4. Consensus targets a runtime-managed raft-rs embedding; memcached uses resident transport-only adoption. Select NFS implementation, Demikernel backend/version and comparison platform, target venue/cycle, and ordered feature milestones.
5. Choose the TCP/Protobuf merge order and follow-on gRPC scope without displacing first-paper applications. Any separate Linux/full-paper source trees still need identification if required; student reports are not the full-system paper source.

Keep maintainer intent separate from source-backed implementation status. Do not silently turn these remaining choices into requirements or reopen the settled isolated-worker policy.

The [control/retirement interface](../software/scheduling-control-interface.md)
now specifies one blocking PreemptInfo read per handoff, local userspace activation
and separate whole-domain close/quiescence/retirement. Combined race checking must
preserve response lifetime and pending wake ownership across close. Retained
old-domain cache generations now have explicit resource accounting in the
composition below; no ordinary kernel CL flush is specified.

[Retained cache and composed scheduling](../hardware/retained-cache-composition.md)
now models full dirty sets, conflict replacement and delayed directory release,
then joins reservations, claims, wake ownership and whole-domain close. DCS waits
for CPU downgrades on a full set; concrete replacement-path independence is an
explicit hardware correspondence obligation, not an empty-cache assumption.

## Kill/exit follow-up (2026-10-01)

The [focused kill/exit check](../hardware/kill-exit-verification.md) separates
application stopping from prefix reclamation. The first implementation allocates
domain codes monotonically without reuse; prefix-reuse certification is deferred.
The focused model checks uncooperative userspace, outstanding RX, IRQ ordering,
close/dispatch races and reference cleanup. It does not complete the larger
combined temporal or concrete resource/refinement obligations. The linked report
lists the remaining implementation and verification work.

## Directory-capacity finding (2026-10-01)

The [sequential replacement/delivery audit](../hardware/directory-pressure.md)
found an absent-LCI/full-set progress counterexample in the corrected abstract
resource model and reproduced the controller/table's allocation dependency in
controlled RTL tests. Complete hardware-state reachability remains unestablished.
The selected address layout is unchanged. Follow the active item 3 investigation
above: retain auto-lock, close the whole-NIC reachability gap and establish its
allocation guarantee. The new actual-directory test confirms the component-level
obstruction; the selected non-locking build now has focused real-DCU regression coverage. Early controller/arbiter/data-gate checks do not replace the planned
whole-slice routing tests or establish shared release-path progress.
