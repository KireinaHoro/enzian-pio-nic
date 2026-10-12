# Layered coherence, progress and scheduling verification

The October 2 RX failure exposed a missing **lower-layer contract check**. The
scheduling results assumed operation completion; they did not establish that
exclusive RX grants and data-returning invalidations were serviceable by the NIC.
The model is now organized into three independently runnable layers, plus
explicit cross-layer adapters. This is bounded verification, not a universal
refinement theorem or a proof of the RTL.

## Layers and boundaries

| Layer | Owns | Assumes from below | Independently checked |
| --- | --- | --- | --- |
| Single worker (`model/tla/worker`) | Selected DC FSM, extracted THX remote FSM, line messages, AXI read/write completion, serialized 2F2F control/overflow sequencing | Correct message framing and modeled endpoint service capabilities; no shared-resource contention inside one admitted line | Legal transitions, address identity, no premature LCIA, doorbell completion ordering, repeated worker progress, projections to the public contracts |
| Steady state (`model/tla/steady`) | Colors, queues, finite shared resources, admission, retained cache/directory accounting and stalled workers | Eligible lower-level exchanges satisfy the completion contract | Resource conservation, transaction tracking, compatible settled configurations, admission and completion of independent peers/bypass |
| Scheduling (`model/tla/scheduling`) | Assignment, reservation/claim, DESCHED, wake ownership, kill and MMIO/control races | Abstract transport readiness/retirement and progress obligations | Local safety/races/liveness; coupled obligations are checked by composition harnesses |

`model/tla/common` holds generic message/buffer helpers.
`model/tla/contracts` holds the shared interfaces. Cross-layer wiring lives in
`model/tla/composition`: Handoff, cached kill cleanup, scheduling composition,
AXI response diagnostics, and optional detailed endpoint expansions.
The structural check rejects worker→steady/scheduling, steady→worker/scheduling,
and scheduling→worker/steady/composition imports. A composition harness may
import the layers it joins. This separates ownership without pretending that
moving a file proves its assumptions.

## Public contracts

The machine-readable [contract ledger](../../model/config/contracts.json)
records providers, consumers, properties, assumptions and scope.

- **`InvalidationPort`**: ordered address/transaction records progress from
  submission to a completion certificate to acceptance. `Complete` is an
  abstract certificate that all required response/data service finished; it is
  not an implementation of that service. `DcsResources` uses this same action
  with its resource-eligibility guard. The port contains no fairness assumption.
- **`TwoFClient`**: one serialized worker operation, parity, an ordered dependency
  sequence, and return. `SingleWorker` checks the same operation relation that
  `ProgressSystem` uses. No scheduler or DC opcode enters this interface.
- **`ProgressSystem`**: `Settled`, `Quiet`, `ProgressFor` and `AdmissionFor` are
  reused by the cross-layer handoff/kill harnesses. Quiet means no outstanding
  transport, not a clean or empty CPU cache. Scheduling components do not inspect
  concrete DC/THX states.

Do not turn a provider's liveness obligation into unconditional fairness on
abstract completion. Fairness belongs on actions **after** resource/environment
eligibility guards have been applied. In particular, disabled AXI write service
cannot be repaired by fairness.

## Concrete low-level checks

`tools/model/coherence_tables.py` translates the selected Nix-generated ROM SV
into all 252 explicit entries (14 states × 18 events), and the unmodified THX
JSON into all 117 transitions. It preserves forbidden/stall entries; the harness
can detect forbidden processing. No handwritten replacement ROM or THX equations
are installed. Hashes of both inputs and the translator are retained.

`LineEndpoint` connects those tables using typed, address-qualified messages,
separate read completion, write-data acceptance and write-response acceptance.
It includes the contextual no-data HAKD decode. Link ordering is a bounded FIFO
configuration; the test does not claim that all ECI virtual channels share one
FIFO. These single-exchange histories contain no competing remote request, so
they do not cover the whole space of VC reorderings.

The configured checks cover cold RX/shared-load and TX/exclusive-load requests,
legal retained shared/exclusive states, absent LCI, and a one-slot link boundary.
They check completion soundness and the state/action projection to
`InvalidationPort!Behavior`. Negative cases expose missing RX write service and
premature acknowledgment. A witness reaches data-bearing HAKD after a load with
no intervening software store. The FSM models data/no-data, not literal dmask
bits or payload values.

`SingleWorker` composes actual line exchanges with the public worker operation
contract. Its doorbell's DC RDD stays held while opposite-control and two overflow
lines are invalidated. Required returned data must complete independently before
LCIA; the doorbell response is released only after all dependencies finish.
Both initial parities and repeated operations are checked, including serialized
RX/TX directions. Reused invalidated targets can take an actual new THX load and
DC refill before their next invalidation. This is an abstract 2F2F client, not a
transcription/refinement of the Spinal worker RTL. TX payload values, packet
semantics and speculative overlap outside the configured histories remain out
of scope.

## Mechanical composition without one mandatory large state space

TLC checks two kinds of correspondence:

1. `LineEndpoint!PortRefinement` and `SingleWorker!OperationRefinement` check
   imported abstract initialization and next-state relations under explicit state
   substitutions. Hidden coherence steps must stutter in these projections.
2. `DetailedSteady` replaces only the resource harness's abstract completion with
   the concrete endpoint exchange. It checks the imported steady-state safety,
   admission and progress properties, plus `AbstractBehavior`. The default bound
   has one indefinitely stalled worker and one progressing worker. Disabling
   write service fails progress here too.

The expanded steady check starts each target exchange in a resident exclusive
entry state and explicitly re-establishes that entry between exchanges. It does
not reproduce cache replacement; `CachedProgress` continues to check that shared
resource contract separately. Independent writes are a capability assumption;
undocumented shared THX response buffers are not proved by copying an endpoint.

An opt-in `ExpandedScheduling` product gates completion in the **unchanged**
`SchedulingComposition!Next` and advances detailed line endpoints between its
steps. It takes entry residency from the resource model and checks imported
scheduling safety plus the abstract behavior projection. It intentionally has
**no full-product liveness claim**: using the old abstract fairness clauses
unchanged would assume away a blocked concrete completion. Lower-level race
coverage and fair service through every concrete shared resource still need
separate justification before that progress claim can be made.

These finite checks are not a mechanized assume-guarantee theorem that proves
all bounds. They make each interface and the checked substitution concrete,
keep the inexpensive layer suites independent, and provide optional products
for finding mismatches. Composition imports alone are not evidence of refinement.

## Reproduce and remaining obligations

The new Nix check builds the ROM and uses pinned `tlaplus18`; generated tables,
flattened source snapshots, effective configs, logs and counterexamples stay in
ignored outputs. Existing layer suites retain their names and bounds.

```sh
nix build .#checks.x86_64-linux.tla-modules
nix build .#checks.x86_64-linux.coherence-layers
nix build .#checks.x86_64-linux.steady-progress
nix build .#checks.x86_64-linux.scheduling-races
nix build .#dcsRom --out-link out/layered-rom
nix develop -c python3 tools/model/check_layers.py \
  --rom out/layered-rom --case expanded-scheduling-safety \
  --output out/layered-full-product --timeout 7200
```

Before claiming full low-level correctness, extend histories to conflicting
CPU requests/evictions, responses in flight and legal independent VC reordering;
include actual finite shared write/data/response resources. Establish the RTL
correspondence for the DC decoder, framing, AXI sink and 2F2F worker. The extracted
THX FSM remains an evidence-backed endpoint assumption, not a proof of THX
internals. Board conformance and the RTL RX sink regression are separate evidence.
The historical large scheduling results remain conditional and are not relabeled
as newly rerun merely because their modules moved.

## Recorded validation — October 2

[Tracked summaries](../../model/evidence/layered) preserve source, generated-table,
configuration and log fingerprints. A passing negative case means TLC found the
specified violation, not that the injected design is correct.

| Run | Outcome |
| --- | --- |
| Nix `tla-modules` | Inventory, import boundaries, contract inventory and SANY passed |
| Nix `coherence-layers` | All 14 outcomes matched; includes five negative/witness cases |
| Repeated single worker / serialized RX–TX | 250 / 2,280 distinct states; safety, projection and repeated progress passed |
| Expanded steady state | 352 distinct states; stalled-peer safety, admission, progress and projection passed |
| Optional expanded scheduling | 23,612 distinct states; safety and projection passed, **kill-only, zero wake budget**, no liveness check |
| Existing steady suite | All 14 positive/negative outcomes matched |
| Focused scheduling races | All 16 outcomes matched |
| Transport/handoff suite | All eight outcomes matched |
| Focused joined scheduling | `full-roundtrip`, `full-claim-contention`, `full-reservation-roundtrip`, `bad-full-drain` matched |

The larger full scheduling rerun was stopped in favor of these focused
regressions; it supplies no new passing result. Existing historical large-suite
results remain in their original reports. Neither the optional kill-only product
nor the separate wake checks establish a detailed coherence-plus-wake liveness
result. The ROM's 252 entries and THX's 117 transitions are available to the
interpreters; these narrow software histories do not exercise all of them.
