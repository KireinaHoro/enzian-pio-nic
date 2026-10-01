# TLA+ module review — 2026-10-01

The active model now has one endpoint contract: **LCI/LCIA, no UL**. The
[model index](../../model/README.md) separates readable TLA+ source from model
configuration, address inputs, and evidence, and describes every module's role.
This review refactors the existing scope; it does not close Goal 2.

## Changes and findings

- Removed the `Nonlocking` switch, UL messages, queues, client phase, retained
  acknowledgment state, release actions and associated fairness clauses. Removed
  the obsolete auto-lock allocation mode and its active configurations.
- Added `CachedTransport` as the single cache/transport adapter used by both
  `CachedProgress` and `CombinedScheduling`. LCIA acceptance, directory-charge
  release, pinning and admission preparation are no longer copied between them.
- Moved transaction quiescence into `ProgressSystem!Quiet`. This predicate says
  nothing about cached old-domain lines. Ordinary handoff still needs no flush.
- Centralized typed read/invalidation message unions in `Coherence`. The generic
  DCS interface now exposes response acceptance without a protocol-specific UL
  alias. `Probe` still exercises it independently of 2F2F.
- Renamed `KillExit`'s abstract transport tail to `CompleteTail`; its actual
  RX/TX/overflow and packet ownership implementation remains an open obligation.
- Added explicit typing of the combined scheduling control record. State tuples
  remain local explicit aliases because importing them inside some nested
  `UNCHANGED` expressions fails in TLC 1.8; this duplication is intentional.
- Removed `bad-drain` and `bad-combined-info`: after deleting delayed UL traffic,
  their weakened guards are equivalent on reachable states. Keeping them would
  produce vacuous fault coverage. Future split response/writeback resources need
  new diagnostics for their actual early-completion races.
- Removed duplicate `lci-only-*` configurations: all active configurations now use
  that endpoint. `--transport-only` / `scheduling-transport` replace the old
  nonlocking-only suite names. Bounds and selected properties live in
  `model/config/scheduling/suite.json`, rather than Python runner definitions.
- The original monolithic model and differing Toolbox snapshots are frozen under
  `model/legacy/tla`; configurations and outputs have their own directories.
  Bytes of the historical sources/results are preserved. Deleted auto-lock-only
  source and configurations are recoverable at pre-review commit `4cb1e8a`.

## Boundaries retained intentionally

The focused wake models are not interchangeable. `HostWake` checks ownership and
subscription routing; `WakeReassignment` checks delayed stale hints across two
moves; `WakeAckRace` checks a second notification arriving before the first ACK.
`WakeHandoff` composes the shared handoff and wake components. Removing those
small models would lose diagnostic coverage, especially while combined liveness
remains incomplete.

`CombinedScheduling` shares the resource components, but its control record
restates the reservation/claim/wake/close contracts. It is **not** a formal
composition or refinement of every focused scheduling model. Its event budget
and one-event-per-domain identity do not include the two-generation ACK race.
Before relying on the combined result for those races, supply a control-state
refinement mapping or compose the corresponding components with explicit ports.

`DirectoryAdmission` retains its CPU fill-reservation accounting experiment. It
checks a different boundary from `RetainedCache`, whose fills are already gated
on available directory space. Neither establishes real THX replacement/service.
The local auto-lock allocation experiment has been removed from the active model.

`Handoff`, `ControlRetirement` and the combined harness still carry optional
prefix-reuse certificates. Their endpoint-retirement assumptions are stronger
than the selected monotonic/no-reuse kill policy. `KillExit` is the no-reuse
control contract and deliberately permits old cached lines. These obligations
must not be presented as a required kernel flush or a completed transport kill
proof.

`InterruptGate` retains a release-first diagnostic; `KillExit` permits both
IRQ-before-release and release-before-IRQ. Interrupts are used for whole-domain
kill, not cooperative PREEMPT. `TimerMirror` checks command order, not a chosen
application timeout policy.

## Validation and remaining scope

The Nix `tla-modules` check verifies the canonical module inventory, reviewed
import edges, absence of legacy endpoint operators in active sources, and SANY
semantics. It does not establish safety, liveness, action framing or refinement.
The TLC runners preserve isolated source/config snapshots and log hashes. This
also avoids TLC 1.8's requirement to resolve configurations beside the root module
without mixing files in the source tree.

Fresh verification completed **81 TLC outcomes**: 34 positive checks and
47 expected fault counterexamples or reachability witnesses. This includes
all eight focused transport cases, 53 focused control/kill/cache/AXI cases,
14 steady-state cases, five combined fault cases, and combined state invariants
over **2,528,455 distinct states**. All 28 canonical modules passed SANY through
the built Nix module check. The selected-address generator check and 11 capacity
utility tests also passed. Other Nix suite derivations evaluated successfully;
their constituent TLC cases ran through `nix develop`, not a second build of
those full recipes. Full combined temporal properties were not rerun or closed.

Fresh results are recorded in
[`module-review.json`](../../model/evidence/module-review.json). Historical
results retain their original hashes and do not become new results merely
because a path moved. The review's initial imported-tuple failures and the first
separated-config lookup failure were tool/model-integration errors, corrected
before the final runs; neither was a protocol counterexample.

Remaining modeling work is unchanged: earlier/in-flight AXI response fencing,
independent THX replacement and response service, concrete kill TX/overflow and
packet/buffer cleanup, broader combined temporal checks and stalled-peer
transitions, then actual RTL/hardware correspondence. Single-transaction
coherence correctness remains an undischarged endpoint assumption.

## Follow-up: AXI/coherence response interactions

The [response-contract extension](axi-response-contract.md) adds generic
`ReadResponses` and reuses unchanged DCS invariants. Four entry scenarios plus a
one-slot boundary now cover earlier/in-flight reads and stop; faults expose the
ordering and service obligations. Its separate evidence does not supersede the
review regression results above or establish full scheduling composition.

## Follow-up: kill/resource composition

[KillTransport](kill-transport-composition.md) instantiates `KillExit` and
`CachedTransport`, with generic `LciBatch` and `PacketResources` components.
Existing component invariants, control properties and parameterized live-client
progress properties are imported. New assertions cover only the control/resource
relations, packet disposition and writeback-to-LCIA ordering. This does not yet
replace the older combined wake/reservation control representation.
