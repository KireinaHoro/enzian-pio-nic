# AXI and coherence response contract

The bounded [AxiReloadGate model](../../model/tla/composition/AxiReloadGate.tla)
checks earlier reads, buffered coherence responses, a held doorbell and a stop
without a doorbell. It identifies implementation requirements for Goal 2;
it does **not** establish THX conformance or a hardware bug. This extends the
later-read diagnostic reviewed on 2026-10-01. No RTL is changed by this work.

## Components and boundaries

The harness instantiates the unchanged `DcsResources` and the new generic
[ReadResponses](../../model/tla/common/ReadResponses.tla) channel. The latter has
no scheduling, domains, parity or 2F2F semantics. `Safety` references their typing,
capacity and response-identity properties directly; only cross-component tracking
and harness state are added. Full scheduling composition should reuse these
properties and `CachedTransport`/`ProgressSystem` properties, adding only the
relations between control and transport. This harness is not yet joined to
`CombinedScheduling` or concrete kill cleanup.

| Port/event | Contract |
| --- | --- |
| `D.ReceiveRead` | Accept a transaction/address record and acquire its color and slice slot; backpressure before acceptance retains producer responsibility. |
| `Respond` / `PeerRespond` | Complete the abstract DCS read and atomically transfer a typed `ReadData` record to the finite response channel. If full, neither release nor enqueue occurs. |
| `R.Send` / `R.Receive` | Ordered FIFO, preserving equal-valued records; identity cannot be reused while queued. Receive removes exactly its head and releases channel capacity. The containing harness supplies service fairness. |
| `Deliver` | CPU-side response consumption, distinct from router completion; installs a generation ghost for target lines. |
| `SubmitInv`, `Invalidate`, `Ack` | Existing DCS submission, processing and LCIA acceptance. Processing requires no target read slot and, under the endpoint contract, no older target response. No UL exists. |
| `Publish` | Both old target lines have acknowledged invalidation before publishing the next generation. |

`Respond` collapses several physical stages; it is **not** an assertion that AXI
RLAST, RDDA, RA3 transmission and CPU consumption coincide. The response queue
is a conservative shared-path experiment with depths one and two, not an audited
THX queue topology. Values 0/1 are old/new generation ghosts, not payload semantics.
The cached-line invalid value is 2. Directory replacement is covered separately.

## Implementation evidence and assumptions

Source inspected for this contract:

- [RX router](../../hw/src/lauberhorn/host/eci/DcsRxAxiRouter.scala) and
  [TX router](../../hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala): each read FSM
  finishes its current response before processing the next read. Their `queue(8)`
  helper buffers AR/AW, not R data. TX also has an independent write FSM.
- [2F2F engine](../../hw/src/lauberhorn/host/eci/EciDecoupledRxTxProtocol.scala):
  doorbell handling waits for invalidation completion; stop/preemption can begin
  without a new doorbell. Router serialization alone therefore does not cover stop.
- [Selected ROM generator](../../vivado/eci/directory-controller-slice/gen_dc_protocol/dc_stmc_lauberhorn/eci_stmc_auto.pl):
  R12 enters `3pRA3`, local LCI stalls there, RDDA advances to state 3 and sends
  RA3. This explains why a target read can obstruct local invalidation.
- [Read manager](../../vivado/eci/directory-controller-slice/dcs/dcu/rd_trmgr/rtl/rd_trmgr.sv)
  and [read data path](../../vivado/eci/directory-controller-slice/dcs/dcs_data_path/rd_data_path/rtl/rd_data_path.sv):
  AXI data, DCU response acceptance and outgoing coherence data have distinct
  handshakes. Router completion does not prove CPU consumption.

Positive checks require the following **undischarged contracts**:

1. An older same-line response cannot reinstall old data after invalidation.
   The harness enforces response retirement before processing invalidation;
   a real endpoint could instead cancel/retry. Prove the appropriate correspondence.
2. A target refill does not acquire a DCS read resource behind a held doorbell
   before publication. Software serialization alone is not proof of this CPU
   issue/speculation constraint. Establish it or model an implementable alternative.
3. Stop fences new old-generation reads while existing reads/responses finish.
   This is NIC/endpoint sequencing, not a kernel access to 2F2F lines or cache flush.
4. Eligible read-data service and LCIA acceptance operate independently of the
   worker waiting for those very completions. Never gate them on publication or
   an empty AXI request queue containing the held doorbell.

Weak fairness covers eligible start, publication, response delivery and each
invalidation stage. Strong fairness covers read admission/service that can be
repeatedly enabled by a competing peer. No fairness clause forces a blocked
transaction to complete. Physical arbitration and CPU coherence service still
need evidence for these fairness assumptions.

## Checked cases and counterexamples

Bounds: four addresses/colors, two target lines (control/overflow alternatives),
one doorbell and one independent repeating peer; one slice with three read slots,
one invalidation slot per color and one/two response slots. Both target choices
are explored. Initial queued/buffered states are interface-entry scenarios, not
claims of demonstrated THX reachability. One exchange/stop is checked; this is
not repeated scheduling, full packet cleanup or arbitrary numbers of workers.

Five positive configurations check `Safety`, `NoStaleRefill`, eventual exchange
completion and recurring peer completion:

- Later read after publication.
- Earlier read queued before the doorbell.
- Earlier response buffered when the doorbell is reached.
- Stop with an outstanding earlier read and no doorbell.
- Buffered response with channel depth reduced to one.

Seven fault configurations expose the named failures:

| Removed protection | Observed failure |
| --- | --- |
| Router head ordering | Later read bypasses the doorbell and returns old generation data. |
| Independent LCIA acceptance | Doorbell waits for LCIA while LCIA waits for an empty request queue. |
| Same-line response ordering | Invalidation clears a line, then a buffered old response reinstalls it before publication. |
| Independent response service | Publication waits for target invalidation, which waits for a response whose delivery waits for publication. |
| Same response-service fault, checking peer | The shared response queue blocks peer completion as well. |
| No target read behind doorbell | Later read owns the target DCS slot but cannot pass the doorbell; target LCI waits for that slot; doorbell waits for LCI. The observed lasso still services the independent peer. |
| Stop admission fence | A new old-generation read after target invalidation reinstalls old data before publication. |

Two deliberately false invariants witness buffered data at doorbell entry and
admission of a target read behind it. All faults/witnesses also check `Safety` up
to the reported violation; they are not exhaustive safety proofs of fault modes.
A passing negative requires the named violation, not a timeout or parser error.

The target-read cycle is a **conditional model counterexample**. Determining
whether THX can issue this sequence is outstanding. The peer lasso in that trace
is an example, not a universal peer-progress proof with the fault enabled.

## Reproduction and next composition

```sh
nix build .#checks.x86_64-linux.scheduling-axi
nix build .#checks.x86_64-linux.tla-modules
nix develop -c python3 tools/scheduling/check_models.py \
  --axi-only --output out/modeling/axi-response --timeout 180 --workers 2
```

The locked `tlaplus18` toolchain checks 14 expected outcomes (five positive,
seven faults, two witnesses). [Recorded evidence](../../model/evidence/steady/axi-response-evidence.json)
contains source/configuration/log hashes and state counts. Source and effective
configurations are snapshotted beside ignored run logs for reproduction.

Next connect kill completion to concrete RX/TX/overflow and packet ownership,
then compose transition liveness and stalled peers. Reuse existing component
properties through instances; add only admission/ownership/retirement relations
at the scheduling boundary. Keep the THX service assumptions visible until
source, directed RTL tests or hardware evidence establish them. Full formal
RTL refinement remains a separate strengthening step.
