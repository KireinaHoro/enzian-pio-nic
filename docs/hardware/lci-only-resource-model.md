# LCI-only endpoint in the scheduling resource models

The [module review](model-review.md) made LCI-only completion the sole active
contract and removed the `Nonlocking` switch and UL state. Source now lives under
`model/tla`, configuration under `model/config`, and recorded evidence under
`model/evidence`; start with the [model index](../../model/README.md).

The results below describe the pre-review selected-endpoint update. They remain
historical evidence with their original source hashes. Fresh cleanup results are
linked from the review.

For the selected endpoint:

- Processing an LCI retains any hit directory entry while invalidation/dirty
  writeback completion and acknowledgment delivery remain pending. An absent
  target does not allocate a directory entry, even when its set is full.
- LCIA acceptance removes the response from the modeled DCS queue, releases the
  retained entry and advances the client's dependency counter. There is no
  client UL phase, queued UL, or retained acknowledgment credit afterward.
- The THX acknowledgment admission guard remains an explicit conservative resource
  assumption. It is not proof of actual THX credit topology. Dirty writeback
  service is abstracted by the processing event; the RTL test separately checks
  delayed WDDA. Modeling those resources and delays independently is still needed.
- DCS response backpressure, held reads, retained dirty cache lines, conflict
  replacement and eviction-delivery queues remain modeled. Removing UL does not
  assume a clean cache or make blocked response service fair by definition.

`CachedProgress` releases its `RetainedCache.retiring` charge on LCIA acceptance.
`CombinedScheduling` composes the same event with PreemptInfo, reservations,
claims, host notifications and retirement. `Handoff` selects the same endpoint
for ordinary, RX/TX and stalled-peer transition cases, including its Goal 1
post-handoff instance. The cleanup also converts `Steady` and `WakeHandoff` to the selected endpoint.

The combined model's optional prefix-reuse certificate still assumes autonomous
closed-domain eviction. That is stronger than current monotonic-no-reuse kill
completion; it must not be mistaken for a required kernel cache flush or a new
commit write. No production scheduling pivot or address change accompanies this
model update.

Reproduction uses pinned `tlaplus18` through `nix develop`:

```sh
nix develop -c python3 tools/scheduling/check_models.py \
  --transport-only --output out/scheduling/lci-only --timeout 900 --workers 4
nix develop -c python3 tools/scheduling/check_models.py \
  --case combined-scheduling --safety-only \
  --output out/scheduling/lci-only-combined-safety --timeout 1200 --workers 4
```

The Nix `scheduling-transport` check selects focused cases; combined temporal
runs remain explicit `--case combined-scheduling-one-slot` / `lci-only-combined`
invocations. Full combined liveness is not claimed: the initial 2 GiB attempt
exhausted its heap before a result, and a 4 GiB retry was stopped for workstation
memory pressure. These are incomplete runs, not protocol counterexamples.

## Checked results

[Evidence, source hashes and exact commands](../../model/evidence/steady/lci-only-model-evidence.json)
record these bounded runs using TLC 1.8.0:

| Case | Distinct states | Result |
| --- | ---: | --- |
| Ordinary no-UL handoff | 8,256 | Safety and temporal properties pass |
| Handoff with indefinitely stalled peer | 33,024 | Safety and temporal properties pass |
| RX/TX handoff | 28,800 | Safety and temporal properties pass |
| Retained dirty cache, independent delivery | 8,592 | Safety and progress pass |
| Color-separated delivery with blocked peer downgrade | 8,592 | Safety and progress pass |
| Full directory reached from empty cache | 8,616 | Safety and progress pass |
| Shared blocked eviction queue | 4,364 | Expected progress counterexample |
| Replacement disabled | 8 | Expected progress counterexample |
| Combined scheduling, two destination slots | 2,528,455 | State invariants pass; temporal properties not checked |

The combined bound has two workers, two domains, one directory way per modeled
set, one notification budget, and no repeated-RX loop. Both reservations/claims
and close/wake paths interleave with transport and retained cache state. The
focused handoff cases include repeated independent peer operations and legal
parities. These finite bounds are not architectural worker/domain limits.
Legacy handoff, retained-cache, host-wake handoff, steady progress and the
generated selected-address witness regressions also pass. The new Nix check
evaluates; the constituent model cases ran through `nix develop`, rather than
rebuilding that recipe a second time. Full combined temporal
properties, including snapshot stability, handoff completion, retirement and
stable progress, remain open in this update.
Remaining obligations include earlier/in-flight AXI reload fencing, independent
THX replacement/response service, actual TX/overflow and buffer cleanup during
kill, and whole-slice/hardware correspondence. A passed abstract case does not
establish these implementation contracts.
