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

## Address ingress follow-up and pending rejection decision

The continued implementation adds `FixedWorkerAxiIngress`, tested through the
actual `AxiCrossbar`. The internal address selects a physical endpoint; ARUSER
and AWUSER retain the original unaliased address. Admission is captured before
backpressure. Accepted transactions remain charged until their final R or B
response is consumed. Late RX writeback remains serviceable independently of
current owner; rejected TX writes are absorbed without reaching a successor's
buffer. This component is not connected to the production engine yet.

Blocks previously forced AXI USER ports to one bit. Dependency commit `1eddae0`
on local branch `feat/preserve-axi-user-widths` preserves configured widths and
makes the crossbar declare its arbiter/priority-encoder RTL dependencies. The
full `nix develop -c mill 'blocks[2.13.12].test'` regression passed. The platform
`FixedWorkerAxiIngressSim` passed with the real crossbar, high domain bits,
backpressure, owner changes, writeback response retention, and DECERR tests.

A **design decision is pending before production admission is selected**:
`desc_to_axi/axi_rd_cl/rtl/axi_rd_cl.sv` in the DCS dependency explicitly ignores
`p_axi_rresp` (line 116). It captures returned data and emits an ordinary read
completion, with no error status on its DCS-facing response. The crossbar test
confirms rejection returns two zero-data DECERR beats; it does not establish an
architectural ECI fault or prevent cache allocation.

Consequently, a domain touching an inactive alias after final control
invalidation could cache rejected data and later hit it when reassigned. This
freshness consequence is inferred from the read path and coherent-cache
semantics, not a newly reproduced THX/board execution. The current plan does not
specify whether inactive-alias access is a domain-local protocol violation
(with no data disclosure, but no freshness promise to that misbehaving domain),
or must receive a non-cacheable/architectural ECI rejection. The latter requires
an additional DCS/ECI error-path design. The implementation question has been
put to the maintainer; neither disposition is claimed as accepted.

`EciFixedWorkerRouter` is a compiled but **not elaborated or integrated draft**
of ownership, lifetime allocation and fail-closed controls. Its lifetime counter
and reset-required latch use FPGA initialization, surviving application reset.
This matters because VIO `probe_out5` drives `app_aux_reset`, separately from
THX coherence (`vivado/eci/bd/design_1.tcl`, lines 578 and 712). An application
reset after allocation latches reset-required rather than resetting allocation.
Programming the FPGA still requires the full coherence-reset boundary. Reset,
allocation exhaustion, binding and dispatch integration tests remain outstanding;
compilation alone is not validation of this draft.
