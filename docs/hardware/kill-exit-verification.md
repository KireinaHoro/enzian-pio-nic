# Kill/exit with non-reused domain prefixes

Selected contract and bounded verification, 2026-10-01. This extends Goal 2;
it does not implement the replacement NIC/kernel ABI or complete hardware
verification. See the [control interface](../software/scheduling-control-interface.md),
[implementation checklist](../software/scheduling-implementation-checks.md) and
[combined resource evidence](retained-cache-composition.md).

## Selected lifetime policy

Allocate the selected layout's 19-bit domain codes monotonically, without reuse
within a coherent machine lifetime. There are 524288 codes before exclusions,
not merely 524288 individual cache-line addresses: each code identifies the
whole domain's worker blocks. Exhaustion must reject new registrations, not wrap.
Driver unload/reload must not reset the allocator while old cache state or traffic
can survive. Resetting it requires a separately established system-wide reset/
quiescence boundary. Reserved codes reduce the allocation budget.

Application exit does not await eviction of old cached lines and does not call
`RetireDomain` to reclaim a prefix. Kernel objects can be reclaimed after their
callbacks, dispatch responses, IRQs and transaction references have finished.
A surviving old-address coherence request must still be handled safely without
accessing freed application objects. Keep address decoding/coherence response
service independent of those objects, or retain the minimal tombstone required
by that service. Not reusing addresses prevents aliasing with a new application;
it does not eliminate finite cache-directory occupancy or late writebacks.

## Kill ordering and serialization

Close fences new callbacks, claims and resumes. NIC close fences new scheduling
admission. For each affected physical worker, serialize the kill latch with its
assignment and gate new blocking RX. An RX submitted before the gate can still
arrive afterward and must receive the kill completion. Assertion of the kill IRQ
and completion of the held RX can occur in either order. Neither completion nor
its delivery may wait for IRQ handler entry or application execution.

The kernel establishes a no-resume certificate for each thread. A parked or
already-kernel thread can be certified directly; it needs no new IRQ. A running
thread can be stopped by IRQ after its blocking load returns, even if it never
yields voluntarily. Ordinary exit uses the same close path from kernel context.
No thread of the domain may restart after certification.

The selected simple kill policy **consumes/acknowledges the old kill notification
before successor activation**. The decision to issue it is closed as well: an
unissued but latched IRQ cannot appear later. There is no normal-handoff commit
write and no scheduling IRQ on the cooperative path. A tagged/revalidated IRQ
scheme could permit more overlap, but has not been checked here.

This introduces only kill-local sequencing. NIC transaction completion/unlock
must still precede reuse of untagged physical-worker engine state. It does not
require eviction of stable old-domain cache lines. Do not hold a lock across an
IRQ/control wait if the IRQ handler or completion path needs that lock.

## Model and checked bounds

[`KillExit.tla`](../../model/scheduling/KillExit.tla) checks two physical workers
of one closing domain, each with one possible successor. Initial cases start
both workers in userspace, kernel, or parked state. Before close, optional
kernel entry and resumption admit running/parked mixtures and delayed dispatch.
One callback can race close; an info response has separate offer and consumption
boundaries and remains immutable. Each worker has one RX slot with distinct
submission, admission, held-load, returned-load and final-unlock states. Requests
may be submitted repeatedly before fencing. A held RX has **no normal completion**:
only kill releases it. IRQ assertion and service are separate actions. All old
cache markers remain forever, including after metadata release.

The checked properties are:

- No IRQ kills a successor; certified threads never resume; no resume occurs
  after close; the RX gate prevents reblocking; offered responses stay stable.
- Metadata release requires the NIC fence, callback join, all no-resume
  certificates, consumed responses, drained IRQs and completed RX/unlock work.
- Close eventually stops both threads, releases those metadata references, and
  allows both successors to execute their modeled operation.

Only eligible NIC/kernel/service actions are weakly fair. Close, userspace RX
submission, voluntary kernel entry and old-thread resumption are **not fair**.
Thus kill completion cannot rely on voluntary cooperation. IRQ service requires
the blocking load to have returned. RX release requires neither IRQ service nor
packet arrival. Callback joining and delivery of an already returned CPU load
are explicit environment contracts. The CPU must permit interrupt service after
load release; permanently masked interrupts or unbounded kernel critical sections
are outside that contract.

The successor operation is an abstract fair CPU action, not a coherence workload
or proof of resource-independent peer progress. This focused harness does not
instantiate DCS/THX resource modules. Its RX release and unlock steps are endpoint
contracts to connect to the existing resource model and actual RTL. Retained
cache markers establish that this control path does not depend on flushing;
the separate retained-cache model checks finite occupancy/replacement.

The pinned Nix check and development run passed all **11 expected outcomes**.
The positive full state graphs contain 31782 states for initially running workers,
31782 for initially kernel workers, and 43163 for initially parked workers.
All configured temporal properties completed. The existing 16-outcome focused
race suite also passed after the runner change.

Three positive cases check safety and liveness. Five deliberately broken cases
must produce the named counterexample: removed RX gate, release waiting for IRQ
service, early successor activation, resume after certification, and early
metadata free. Three additional negated reachability checks must produce witnesses
for IRQ-before-RX-release, RX-release-before-IRQ, and exit without any IRQ.
Witnesses are reachability evidence, not safety failures of the selected protocol.

## Reproduction and evidence

```sh
nix develop -c python3 tools/scheduling/check_models.py --kill-only --output out/scheduling/kill-exit --timeout 300 --workers 4 --heap-mib 2048
nix build .#checks.x86_64-linux.scheduling-kill-exit --out-link out/scheduling/kill-exit-nix
```

The tools use `tlaplus18` from locked nixpkgs. The runner snapshots the model and
records jar/source/config/log hashes, explored states, and expected violations.
The [tracked evidence](../../model/scheduling/kill-exit-evidence.json) records
current outcomes. Parse errors, timeouts and unexpected violations are failures.
Generated full logs and counterexample traces remain in ignored `out/` directories.

## Remaining work before implementation confidence

1. Connect kill release to actual 2F2F/DCS resources: CPU load completion,
   invalidation/ACK/unlock traffic, in-flight TX and overflow, and full-address
   preservation. Verify the release/control path cannot be blocked by the load
   it is trying to release or by an unrelated stalled worker. The focused model
   checks sequencing, not this structural progress guarantee.
2. Complete the larger combined temporal checks and relevant transition bounds:
   repeated migration/notifications, TX/overflow/bypass and a stalled unaffected
   peer. Existing combined state invariants pass; its full liveness runs remain
   incomplete. Prefix-reuse liveness is deferred under the selected allocation
   policy and should not be mistaken for required application-exit liveness.
3. Discharge the retained-cache hardware contracts: full-directory-set victim
   generation and independent downgrade/writeback service. Monotonic PAs do not
   remove these requirements. THX endpoint protocol correctness is still assumed.
4. Implement the listed local serialization: close vs claim/activation, per-worker
   kill issue/ack vs reassignment, callback/command reference joining, immutable
   MMIO responses, and allocator exhaustion/reload handling. Select the Linux
   domain-wide signal/exit hook and IRQ acknowledgment mechanism. Check actual
   bus framing and IRQ delivery, including kernel-context interruption.
5. Run the planned targeted actual-DCS RTL tests, then matched hardware tests.
   Also define disposal of network packets, DMA/buffer/reply ownership and external
   effects of a killed RPC. These are outside the control-only kill model.

No counterexample in the selected focused protocol requires a new workaround:
ordinary fences and kill-local serialization suffice at this abstraction. This
is bounded evidence of implementability under the listed contracts, not proof
that the current hardware supplies them. Prefix reclamation, arbitrary-size
proofs and whole-system refinement remain separate work.
