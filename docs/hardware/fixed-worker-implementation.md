# Fixed-worker implementation progress

October 2, 2026, branch `feat/fixed-worker-cooperative-yield`. **Incomplete;
production routing and scheduling are unchanged.** This branch begins the
[current implementation contract](../software/scheduling-control-interface.md).
It is not ready for hardware acceptance or merge.

Preparatory source commit: `6a415a2`.

## Implemented components

`tools/address-map/generate.py` reads `model/data/selected-8k.json` and emits
matched C and Scala constants and encoding/decoding helpers. It checks the
selected basis pivots, physical-worker bases, stride and overflow geometry.
The 19-bit PA lifetime namespace has 524288 codes; the default sixteen active
scheduler slots do not restrict that namespace. Helpers support one through
seven physical workers, reject invalid arguments/high bits, and do not allocate
or recycle codes. Generated C leaves output arguments unchanged on rejection.

`FixedWorkerDecoder` is a standalone combinational RTL component. It identifies
physical worker and domain, validates enabled/current-owner admission, and
restricts fresh reads to aligned RX/TX transport lines. Bypass remains outside
the domain namespace. It does not translate the input address. It is **not
connected to the production AXI or LCI paths**. Its current-owner admission
result must not be used to discard old writebacks or LCIA; those require retained
transaction matching in the integration.

`IdleDispatch` checks one initial activation followed by one ordinary handoff,
with repeated/delayed kicks, sticky notification consumption, per-worker reader
serialization, a shared single-outstanding control bus, and delayed autonomous
cleanup. It contains no host-work interface. Cleanup fairness is a premise;
there is no DCS/THX, Linux, packet allocator or thread-pool refinement here.
The positive case checks no ordinary scheduling IRQ, no unauthorized reader,
no-return ordering, cleanup before completion, eventual handoff and eventual
unrelated control completion. Other register accesses may wait behind the info
read. Four fault controls must fail their named property rather than merely
crash or time out.

## Checks actually run

All commands below ran through the locked Nix environment on October 2:

| Check | Result and scope |
| --- | --- |
| `nix develop -c python3 tools/address-map/test_map.py -v` | Five tests passed. C compilation uses warnings as errors. All 524288 codes round-trip on all seven workers (3,670,016 combinations). Independent capacity comparisons cover worker counts 1–7, all basis bits, sampled combined codes, both parities and every overflow line. Ten thousand arbitrary addresses check rejection and decoding against the independent utility. |
| `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.FixedWorkerMapTest` | Two Scala arithmetic/argument tests passed. |
| `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.FixedWorkerDecoderSim` | Standalone seven-worker RTL elaborated and passed Verilator simulation, including owner mismatch, disable, bypass, high bits and line bounds. No actual DCS slice. |
| `nix build .#checks.x86_64-linux.fixed-worker-map --no-link -L` | Passed generated-collateral and C/oracle checks as a Nix derivation. |
| `nix build .#checks.x86_64-linux.scheduling-cooperative --no-link -L` | Positive model passed; four expected violations reproduced. |
| `nix develop -c python3 tools/model/check_modules.py --output out/scheduling/cooperative-module-check` | All 42 inventoried modules passed boundary and SANY checks. This is parsing/structure, not TLC verification of all modules. |

The focused single-worker TLC run has 41 distinct positive states. Deliberate
faults reproduce `AuthorizedReader`, `StickyNotification`, `NoOrdinaryIrq` and
`Completes` violations. Direct-run model configs, source hashes, logs and
summary are preserved under `out/scheduling/cooperative-20261002/`; build checks
also record results in Nix outputs. The hardware helpers have no new production
ABI identifier because they are not yet selected by production generation.

Final Nix outputs for the committed components:

- `/nix/store/cisbi4k5dzbshsza671qbks7m5ph870f-lauberhorn-fixed-worker-map`
- `/nix/store/c61i11mmm3yywkcz9kladq70jqjmbj7b-lauberhorn-scheduling-idle-dispatch`

The cooperative output includes the detailed summary, generated configurations,
and TLC logs for all five cases.

## Remaining implementation and acceptance

1. Replace thread-prefix routing in the actual AXI/LCI integration. Preserve
   full accepted addresses, retained owner identities, old writeback/LCIA
   service, final RX invalidation and the independent RX write sink. Validate TX
   descriptor/reply-context ownership. Connect matched generated collateral.
2. Implement NIC reservation/idle notification/PreemptInfo and RX PREEMPT delivery,
   preserving packet-driven policy and the watermark/background scan. Replace
   the control cache line and READY/BUSY path as one matched ABI change.
3. Implement monotonic lifetime allocation that survives module reload and ABI
   mismatch rejection. Add fixed-pool declaration/ready registration, mapping-only
   mmap, pinned idle dispatch threads, shared activation/claim helper, bootstrap
   and ordinary yield, authoritative alias/parity replacement and fail-closed
   lifecycle. Remove the shared parity page and kernel parity programming.
4. Compose the focused model with existing claim, capacity and transport checks;
   run concurrent claims, initialization failure, unrelated wakeup and failed
   copy tests, repeated A→B→A/migration, full address/isolation/exhaustion tests,
   actual DCS slice routing and shared AXI-Lite contention tests. Build matched
   kernel/runtime/deployment artifacts.
5. Launch hardware CI only after the replacement is integrated. Follow the
   30-minute/five-hour collection workflow and retain referencing branches.
   Recheck reservation before Enzian access. Perform the requested fresh RX-stall
   boundary search with matched clocks/register values, trace evidence and every
   exposed L2C_TAD status; preserve failures before reset. Repeat the highest
   tested exception-free setting under contention across two cold boots, with
   active-peer handoffs and full-duplex bypass, including all-but-one stalls.

No hardware CI, kernel/deployment build, board access, timeout re-probe or merge
has been performed for this branch. No historical timeout threshold is used as
new evidence. The pre-existing `deps/thx-fsms` worktree revision change was
preserved and is not part of these changes.
