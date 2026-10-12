# Domain PAs, fatal access and packet isolation

The active [`DomainIsolation`](../../model/tla/composition/DomainIsolation.tla)
contract covers a domain using its own mapping for a worker it does not own.
This is distinct from two PAs aliasing: two lifetime PAs can share the same
worker and CC color while remaining different cache lines. The model uses the
selected address generator's actual RX/TX control addresses and checks both
address distinctness and shared worker colors.

## Checked behavior

The bounded harness has two domains and two workers, separate RX/TX addresses,
one ownership change, and variants with an initially unassigned peer. Domain
labels 1 and 2 select generated PA lifetime codes 0 and 1. It permits fresh
reads through inactive PAs, asynchronous fault notification and kernel kill,
TX dirty-data collection overlapping a fault on another PA, cleanup writebacks,
and TX scrubbing after installation. RX can proceed during TX scrub. Scrub
completion means the last B response, not acceptance of the last write.

An arbitrary payload byte carries a provenance label (zero, domain A or domain
B). RX padding may contain another domain's old bytes before masking. The
checks require:

- Rejected fresh accesses record a sticky fault against the PA lifetime.
- Every offending domain is eventually killed, assuming IRQ/workqueue/SIGKILL
  service; a nonoffending domain is not killed.
- Host reads and newly admitted TX cannot disclose another domain's byte.
- A TX descriptor collected after its domain fault is rejected, even if its
  trigger read was accepted earlier. Previously admitted DMA may finish.
- Never-installed workers do not inherit faults from their reset owner bits.

Intentional negative controls remove read admission, fault recording, kill,
TX fault admission, scrub, RX tail masking, TX writeback admission or the
installed-owner qualification, or blame the victim worker owner instead of the
rogue PA lifetime. Each must produce its named counterexample.
All cases are in the existing `scheduling-cooperative` CI gate.

## RTL findings and corrections

`EciAxiIngress` already rejects inactive fresh reads and reports the **PA's
lifetime**, rather than the current worker owner. `EciDomainFaults` makes that
fault sticky, `EciOwnership` fences its assigned workers, and the driver's
fault work calls `fail_domain`, sends SIGKILL and retains domain resources.
DECERR alone is insufficient: production DCS ignores RRESP. Cleanup AW is not
a fresh-access violation; RX writes drain into the discard sink, and TX writes
must match the installed owner.

Two missing qualifications were reproduced and corrected:

1. `DmaControlPlugin` checked the trusted TX owner and descriptor but omitted
   the owner's sticky hardware fault. With dirty-data invalidation replies
   delayed, a domain fault could arrive before metadata collection completed;
   the old RTL still returned TX success. Admission now also checks
   `failedWorkers`. Rejection uses the existing status-3 completion path, so
   accepted read/writeback obligations can drain without creating a packet.
2. `EciOwnership.failedWorkers` compared owner registers even for workers that
   had never received an assignment. Their reset code is zero, also the first
   real domain's code. Faulting that domain stranded an unused worker and
   prevented an already-registered healthy peer from activating there. Fault
   attribution now requires `enabled(w)` as well as a matching failed domain.

`DomainIngressSim` connects real ingress, crossbar and sticky fault RTL: A's
inactive PA on B's worker returns two zero DECERR beats, faults A, fences A's
otherwise-current PA and preserves B's endpoint service. Interrupt consumption
does not clear the fault. This test observes a kill notification, not Linux
SIGKILL execution.

`InactivePaSim` uses the full engine, delays TX invalidation responses, injects
the same sticky domain fault through its software input, and delays the
simulated kernel handler. It checks TX rejection without packet emission and
subsequent healthy activation on the never-installed worker. The software
fault input is deliberate: the existing coherent CPU simulator aborts on
DECERR, unlike production DCS. The separate ingress test checks the actual
rogue-PA path. Both RTL bugs failed their regression before correction.

## Local validation — October 3

The complete cooperative model suite passed **24 expected outcomes**: the 13
existing cases, two positive domain-isolation configurations and nine negative
controls. The positive configurations exhausted **105,984** and **274,050**
distinct states. The model inventory, import boundaries and SANY checks passed.
Both directed RTL regressions passed, followed by the complete fast ECI suite:
**52 passed, one existing ignored test**, in 570 seconds. These are current
local results, not historical hardware evidence. Commands, hashes, counterexample
statistics, pre-fix reproduction seeds and CI job provenance are retained in the
[evidence record](../../model/evidence/scheduling/domain-isolation-20261003.json).

## Boundary and remaining evidence

This is a bounded contract check plus directed RTL tests, **not** a refinement
proof of the complete CPU/cache/RTL/software system. It assumes trusted kernel
mappings: a process can address its own domain's worker PAs, not another
domain's PAs. ECI carries no independent requesting process identity with
which to enforce that assumption. Kernel notification and kill require fair
service; neither DECERR nor a hardware fence proves timely Linux termination.

The harness abstracts AXI/LCIA drain before owner installation, using the
existing transport contracts rather than re-proving them. Payload provenance
is a representative byte, not all packet lengths and strobes. It does not
model arbitrary stale CPU cache hits, every coherence state, or simultaneous
same-edge fault/descriptor acceptance: the TX cutoff is the registered sticky
fault visible at metadata admission. Actual byte masks, allocation bounds,
repeated handoffs and delayed scrub responses have separate RTL regressions.
An already-failed reserved target remains quarantined by the existing owner
fence; complete recovery of failed reservations remains outside this contract.

Pipeline 516080 (`dae5cf23`) predates these two admission/fence corrections.
Its preflight found job 2865583 (`fast-tests-eci`) failed in
`rx-independent-progress-2-stalls`: DCS reload timeout at bypass TX PA `0x1300`
(aliased `0x1780`), setup seed `-1760455454`, simulation seed `1699615821`.
The other 12 gates passed; preparation 2865596, hardware 2865597 and report
2865598 were skipped. Thus there is no running hardware build from that
pipeline and no board result. The failed stage and exact revision are retained
in the evidence record. The exact recorded seeds passed locally on the revised
worktree; that is not a reproduction at the pipeline SHA and does not explain
the CI failure. No replacement hardware build was launched for this unresolved
nontrivial gate failure; this source correction is pushed with `ci.skip`.
No address
layout, timing pipeline, DESCHED ABI or generated software header changed in
this round. Matched revised hardware and board acceptance remain required.
