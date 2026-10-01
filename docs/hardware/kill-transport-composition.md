# Kill completion with transport and packet resources

`KillTransport` joins the existing kill control model to resource-backed cleanup.
It checks a closing worker alongside an indefinitely stalled worker and a
repeating independent peer. It is an incremental Goal 2 composition, not the
full HOST_WAKE/reservation/migration composition or an RTL refinement proof.

## Reused components and properties

The [harness](../../model/tla/scheduling/KillTransport.tla) instantiates:

- `KillExit`: close/callback lifetime, kill latch, IRQ issue/service, no-resume
  certification, immutable response, successor activation and metadata release.
  Its worker count is now a parameter; existing configurations retain two workers.
- `CachedTransport`: unchanged 2F2F, shared DCS/THX read resources, invalidations,
  retained cache entries and replacement delivery. `CT!Safety` is reused directly.
- `ReadResponses`: finite response occupancy between NIC completion and CPU
  consumption of the victim's RX. Its identity/capacity properties are reused.
- `LciBatch`: ordered finite invalidation submission/acknowledgment, independent of
  scheduling and payload values. The containing harness supplies the DCS port.
- `PacketResources`: buffer ownership and finite writeback data/response stages.
  Its no-use-after-release property is reused rather than restated in the harness.

Control safety and temporal properties are referenced through `K!…`; live-peer
admission/completion use `P!AdmissionFor({3})` and `P!ProgressFor({3})`. Those
parameterized predicates also define the existing all-live-client properties.
`TwoFClient` now supports an empty local dependency list: committed TX delegates
its invalidations to the shared batch, rather than inventing a second copy.
The new properties relate components: control cannot reuse/free the worker before
its tail is quiet, abandoned TX cannot enter output, writeback completes before
LCIA acceptance, and the control RX stage agrees with actual held/queued resources.
This does not assume the imported component `Spec`s: fairness applies to the
composed, guarded actions.

## Boundaries and implementation correspondence

| Abstract event/resource | Concrete interface / contract |
| --- | --- |
| RX buffer return | `hostRxAck.fire` in the RX `freeSlot` state. Return may precede invalidation, matching the current FSM. |
| RX completion | 2F2F dependency invalidation followed by router response and separate CPU consumption. Neither waits for IRQ handler entry. |
| TX cleanup batch | Current control CL, then two representative overflow CLs. `waitInvResp` precedes `invalidatePacketData` in the current TX FSM. |
| Writeback stages | Coherence data accepted into TX packet storage, then completion response accepted. Abstract two-stage service, not a count of physical AXI beats. |
| LCIA acceptance | May advance the batch only after the target writeback is complete. This is an explicit required correspondence with DCS/AXI sequencing. |
| Committed output | Transfer the packet to the DMA/output owner, then wait for `hostTxResult` after buffer reads finish; downstream acceptance is an environment assumption, not remote acknowledgment. |
| Abandoned output | End the old transaction’s ownership of the fixed worker TX slot without transmitting. Current `abandonTx` suppresses `txInvDone` and invalidates the full overflow footprint without trusting a partial descriptor length. |
| Reuse/metadata release | Existing `KillExit` guards plus `TailQuiet`: victim transport quiet, response consumed, TX batch acknowledged and both buffers disposed. No cache flush is implied. |

Relevant source is the [2F2F worker](../../hw/src/lauberhorn/host/eci/EciDecoupledRxTxProtocol.scala),
[RX router](../../hw/src/lauberhorn/host/eci/DcsRxAxiRouter.scala), and
[TX router](../../hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala).
The current TX write FSM has separate data and B-response completion. Its read
FSM transmits a committed descriptor and waits for the result before returning.
The [DMA controller](../../hw/src/lauberhorn/DmaControlPlugin.scala) assigns one
fixed MTU-sized TX slot per worker. `hostTxResult` follows DMA read completion
(or descriptor acceptance for a zero-DMA operation), not remote delivery.
`PacketResources` models transaction ownership of that slot, not a dynamic
allocation/free interface; its `Sent` state means local output ownership ended.
These are correspondence anchors, not proof that every concrete handshake is
represented or ordered as assumed.

The TX batch uses a second instance of **the same** `DcsResources` module, sharing
the read-slot variables with the 2F2F instance. Its invalidation queues cover
separate target colors. `TailTracking` checks the two queue arrays are never
occupied at the same color. This represents disjoint queue ownership, not a
second DC with extra read capacity. Shared-queue layouts require a different
composition; this harness does not establish their progress.

`LciBatch` owns its remaining sequence and a waiting flag: submission transfers
responsibility to DCS, acceptance of the matching LCIA advances the sequence.
Only one LCI per batch is outstanding. `PacketResources` owns each buffer from
initial allocation until return or output completion; writeback admission requires
ownership, service decrements finite work, and release requires all writes done.
The harness maps every TX line to its packet buffer and does not rely on killed
userspace to generate or service those writes. A partially admitted coherence
transaction must therefore complete independently of userspace: that endpoint
contract is assumed, not a claim about arbitrary partially issued CPU stores.

## Bounds and assumptions

Three software scenarios are separate: blocked RX/no active TX, committed TX,
and abandoned uncommitted TX. RX submission is disabled in the TX cases,
preserving half-duplex worker execution. Committed TX starts with its commit read
queued; admission acquires the same shared read resources as RX. The TX batch and
output must finish before that load can return, and IRQ service waits for CPU
response consumption. Abandoned TX has no commit read and starts cleanup only
after the imported no-resume certificate, so the CPU cannot keep modifying it. A previously delivered RX buffer can
remain owned in any case. Before the kill latch, a submission can still race
close; an already submitted RX may be admitted after the latch.

The focused kill control keeps a committed load held until the kill latch even
if output has already completed; normal TX returns before kill remain outside
this scenario. One closing worker is composed with one indefinitely held RX and one repeating
live client. The focused control regression retains its two closing workers.
The resource bounds are three shared read slots, four conservative THX credits,
one invalidation slot per color, one victim response slot, two directory ways
per set and one eviction slot per set. The TX batch contains one control and two
overflow lines. RX also invalidates control plus overflow. Parities start at zero;
the live peer subsequently exercises both. These are finite interaction bounds,
not the selected 8 KiB capacity frontier or a proof for all worker counts/parities.

Initialization includes retained cache lines and a queued dirty writeback; it
does not assume an empty or clean cache. Full-address identities survive until
completion. Stable old cache entries may remain after metadata release. As in
[the no-reuse policy](kill-exit-verification.md), servicing their later coherence
traffic must not dereference freed application metadata or reused packet storage.
That late-address/tombstone implementation is not modeled here.

The [AXI response contracts](axi-response-contract.md) remain premises: no
forbidden target refill behind a held doorbell, correct same-line response
ordering, and independent eligible coherence service. Actual THX credit topology,
replacement initiation and service fairness remain undischarged. The response
channel is explicit only for the victim RX/commit load; the live peer retains the existing
abstract 2F2F completion boundary.

Close and voluntary application actions are not fair. After close, eligible
control and cleanup actions are weakly fair; competing peer admission/preparation
is strongly fair. Fairness never forces blocked writeback or invalidation service.
No timeout, release or application execution is required of the stalled peer.

## Verification and remaining composition

```sh
nix build .#checks.x86_64-linux.scheduling-kill-transport
nix build .#checks.x86_64-linux.tla-modules
nix develop -c python3 tools/scheduling/check_models.py \
  --kill-transport-only --output out/modeling/kill-transport --timeout 900 --workers 4
```

The final development run and Nix check passed all **eight expected outcomes**:
three positive modes, four deliberate faults and a reachability witness. The
positive state graphs contain 61,200 states (abandoned TX), 55,200 (committed TX)
and 68,400 (RX/no active TX). All configured temporal checks completed. The module
check passed inventory/import boundaries and SANY for 32 modules. Existing kill
and retained-cache regressions are recorded separately in the evidence. Positives check component/cross-component safety, the existing kill
control properties, eventual stop/free/successor work after close and recurring
live-peer admission/completion. Faults remove the tail fence, transmit abandoned
TX, accept LCIA before writeback completes, or block writeback behind the stalled
peer. The witness requires metadata release while that peer's load remains held.
Expected failures must violate the named property, not time out or fail parsing.
Results and source/configuration/log hashes are recorded in
[the evidence file](../../model/evidence/scheduling/kill-transport-evidence.json).

Still separate: joining kill with full wake/reservation/repeated-handoff races,
successor coherence traffic (successor work here is the imported control action),
bypass RX/TX concurrency, multiple closing workers sharing packet queues,
arbitrary overflow depth, exact AXI framing/data values and network side effects.
Packet output already accepted before kill cannot be retracted; downstream
completion/disposal semantics must be implemented explicitly. The model adds no
normal-handoff commit write, cache-flush requirement or scheduling IRQ.
