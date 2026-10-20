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
a fresh-access violation; RX writes drain through the independent replay-shadow sink, and TX writes
must match the installed owner.

TX admission checks the trusted owner, descriptor and registered sticky fault;
status-3 rejection drains accepted obligations without emitting a packet.
`failedWorkers` requires an enabled assignment before comparing the owner code,
so reset owner bits cannot fence a never-installed worker.

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
rogue-PA path.

## Recorded bounded results — October 3, 2026

The complete cooperative model suite passed **24 expected outcomes**: the 13
existing cases, two positive domain-isolation configurations and nine negative
controls. The positive configurations exhausted **105,984** and **274,050**
distinct states. The model inventory, import boundaries and SANY checks passed.
Both directed RTL regressions passed, followed by the complete fast ECI suite:
**52 passed, one existing ignored test**, in 570 seconds. These are recorded local results for that source revision, not board results. Commands, hashes, counterexample
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
