# Validation and simulation

Real-machine setup and execution are covered by [automated hardware test quickstart](hardware-test.md).

Scope: source-inspected test entry points and coverage limitations, 2026-09-08. No simulation or hardware build was run to create this document.

## Select the smallest relevant check

```sh
# All untagged/fast Lauberhorn tests (broader than CI).
nix develop -c mill gen.test -l org.scalatest.tags.Slow

# Exact CI scope: only ECI package, exclude Slow.
nix develop -c mill gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci

# One suite / one test.
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim -- -t rt-timestamped

# Full suite, including Slow; intentionally broader and potentially expensive.
nix develop -c mill gen.test

# Dependency regression when touching/updating blocks.
nix develop -c mill 'blocks[2.13.12].test'
```

| Changed area | Start with suite(s) |
| --- | --- |
| Packet allocation | `lauberhorn.PacketAllocSim` |
| RPC RX/TX, scaling/preemption | `lauberhorn.host.eci.OncRpcSim` |
| Nested RPC descriptors and reply routing | `lauberhorn.host.eci.OncRpcNestedSim` |
| Bypass receive / transmit | `lauberhorn.host.eci.RxBypassSim`, `lauberhorn.host.eci.TxBypassSim` |
| RX pipeline/replay regressions | `lauberhorn.host.eci.RxReplayPcapSim` |
| Trace dump responder | `lauberhorn.LauberhornTraceDumpTests` |
| Legacy PCIe | `lauberhorn.host.pcie.NicSim` |

`lauberhorn.host.eci.NicSim` is a shared **trait**, not a runnable suite. The shell's `repeat-test` defaults to `OncRpcSim`; use a concrete suite when overriding it:

```sh
nix develop -c repeat-test rx-tx-interleaved lauberhorn.host.eci.OncRpcSim
```

This loops until failure; bound/stop it deliberately. ScalaTest parallel execution is available with `mill gen.test -P8`; Mill module-level test parallelism is disabled to avoid repeat Verilator compilation. Start with one reproducer when investigating nondeterminism.

## Evidence to retain from a failure

- Simulation config uses Verilator, FST waves, and a `verilator-cache` path; emitted paths depend on the Mill test working directory. Use the printed workspace path instead of assuming a fixed root-level `simWorkspace`.
- The shared `DutSimFunSuite` writes `sim_transcript.log.gz` and prints a reproducer including `-DsetupSeed=... -DsimSeed=... -DprintSimLog=true`. Preserve both seeds, test name, revision/submodule revisions, and generated config.
- ECI tests using `testWithDB` emit `lauberhorn_trace.pcapng` from a `finally` block. This supports causal event inspection even on failure; load the repository Lua dissector as described in the [trace tools](../../data/eci/sys_trace/README.md).
- PCAP replay tests load `data/eci/iladata/rx-lockup{,-2}.pcap` and use libpcap through pcap4j. Nix configures `LD_LIBRARY_PATH`; missing native libpcap can fail before an RTL assertion.
- For simulator changes in dependencies, inspect/update the submodule rather than copying its implementation into the platform. Blocks regression gates every pipeline; see the [CI checks](ci.md#pipeline-and-handoff).

## Coverage limits that affect claims

- `OncRpcSim` has registered but empty TODO test bodies: `rx-preempt-no-leaking` and `rx-sched-crit-timeout`. A passing suite does **not** validate those properties.
- `rx-tx-interleaved` is tagged `Slow` with an address-map FIXME; fast CI excludes it. RX size scans and TX scan tests also use `Slow`.
- `PacketAllocSim` explicitly leaves multi-configuration and block-until-free checks incomplete.
- The Scala host model approximates software IRQ/scheduling behavior. Passing RTL simulation does not validate the actual Linux module, aarch64 atomics, or real ECI cache behavior.
- `model/legacy/tla/lauberhorn.tla` and Toolbox models describe isolation/preemption; the checked-in small model config names type, cacheline-exclusion, preemption and isolation invariants plus temporal properties. Generated model outputs are historical artifacts, not proof that current RTL/software satisfies the model. The separate steady-state model now has a Nix check; it does not validate this older scheduling model. Reconcile source and model snapshots before using results in a paper.
- Existing `data/` captures and plots are debugging/measurement evidence with their own provenance. They are not a current benchmark result merely because they are checked in.

After a behavioral change, run the relevant suite and elaborate the affected hardware; require real target validation for scheduler/ECI deployment claims. For docs-only changes, link/path and command-definition checks suffice.

Evidence: [test build graph](../../build.mill), [simulation config](../../hw/src/lauberhorn/Config.scala), [shared fixture](../../deps/blocks/tester/src/jsteward/blocks/DutSimFunSuite.scala), [ECI fixture](../../hw/test/src/lauberhorn/host/eci/NicSim.scala), [trace fixture](../../hw/test/src/lauberhorn/sim/DbFactory.scala), [RPC tests](../../hw/test/src/lauberhorn/host/eci/OncRpcSim.scala), [model](../../model/legacy/tla/lauberhorn.tla), [small model config](../../model/config/legacy/lauberhorn_model_isolation/MC.cfg).

For routed checkpoint analysis and source-level timing closure, use the
[physical implementation guide](../hardware/physical-implementation.md).

### Trace DMA reset-startup regression (September 17)

Combined timing pipeline 511830 failed `TraceBufferDMATests`'s “writes events to
DRAM” case with setup seed −383376567 and simulation seed −348068956.
The shared `sleepCycles` waits for clock edges, including synchronous-reset
edges. Setup returned after ten edges while the stimulus reset lasts sixteen
cycles; producers accumulated batches before the FlowDrivers began sampling.
A local instrumented reproduction observed capture occupancy 4 and overflow.
The blocks test fix waits for ten reset-qualified sampling cycles instead,
leaving RTL, traffic generation and all data/loss assertions unchanged.

The fixture seeds Scala's RNG; Spinal derives its default simulator seed from
that RNG (1193543006 for this reproducer). The printed fixture seed is therefore
not the simulator's displayed seed. No seed-handling change was required.

### Settled progress checks

See the [verification contract and reproducible commands](../hardware/steady-state-verification.md).
`OncRpcSim` tests `rx-independent-progress-1-stalls` and
`rx-independent-progress-2-stalls` replace the historical empty RX HoL test with
finite observations of active peer and bypass RX progress. They do not prove
infinite-stall liveness, full-duplex bypass safety or the new decoder. Python
capacity tests and the independent TLC runner live under `tools/progress/`.

The steady-state model uses **`tlaplus18` from the locked nixpkgs**, with Python
and Java supplied by Nix. No external jar download is needed:

```sh
mkdir -p out/progress
nix build .#checks.x86_64-linux.steady-progress --out-link out/progress/nix-check
nix develop -c python3 tools/progress/check_models.py --case selected-two-stalls --case bypass-overflow --case selected-bypass --case selected-peer-bypass --workers 4 --heap-mib 3072 --timeout 1200 --output out/progress/extended
```

Read the result's `summary.json` first. Expected negatives count as passing only
when TLC reports the intended property failure; parser errors and timeouts do not.
The extended cases are opt-in finite bounds, not a proof for all worker counts or
undocumented THX resource behavior. See the report for current results.

The [Goal 2 scheduling contract checks](../hardware/scheduling-verification.md)
are separate from the steady-state regression:

```sh
nix build .#checks.x86_64-linux.scheduling-progress --out-link out/scheduling/nix-check
```

They model the proposed cooperative handoff, pending wake ownership and interrupt
ordering. They do not test the current kernel scheduler or instantiate DCS RTL.
The optional stalled-third-worker case and remaining composition work are
listed in the report.

For the focused concurrent-race subset, run
`nix build .#checks.x86_64-linux.scheduling-races --out-link out/scheduling/races-nix`.
See the [implementation checklist](../software/scheduling-implementation-checks.md)
for bounds, negative controls and required serialization points.

For retained-cache pressure and the combined scheduling race model, run
`nix build .#checks.x86_64-linux.scheduling-composed-invariants --out-link out/scheduling/composed-invariants-nix`.
This checks combined state invariants, cache liveness and expected faults. The
larger `scheduling-composed` target additionally checks combined temporal
properties; its result must be reported separately.
See [bounds and assumptions](../hardware/retained-cache-composition.md); this is
separate from the original component baseline.

For monotonic-prefix kill/exit checks, run
`nix build .#checks.x86_64-linux.scheduling-kill-exit --out-link out/scheduling/kill-exit-nix`.
The [kill/exit report](../hardware/kill-exit-verification.md) describes three
positive temporal cases, five deliberate faults, three ordering/reachability
witnesses, and the endpoint assumptions still requiring hardware correspondence.

For the cache/directory admission contracts and full-set LCI counterexample, run
`nix build .#checks.x86_64-linux.scheduling-cache-contracts`.
`nix build .#checks.x86_64-linux.dcs-pressure` checks actual controller/table,
arbiter and data-gate RTL with controlled surroundings. See the
[directory-pressure report](../hardware/directory-pressure.md) for scope and the
distinction between a passing diagnostic and a reproduced design progress failure.

The `dcs-pressure` target also runs the reset-to-full auto-lock sequence through
actual TSU/tag RAM and its reset wrapper, using a test XPM RAM primitive model.
`directory-summary.json` and `directory-events.log` record naturally reached
full-set absent-LCI stalls, successful hit invalidation, and UL-enabled recovery.
These checks establish directory-component reachability, not the complete
Lauberhorn/THX execution. Auto-lock remains selected; the nonlocking diagnostic
is retained only as a regression of the unsafe wholesale-swap experiment.

### Generated Lauberhorn DCS ROM

See [the selected ROM contract and reproducible checks](../hardware/lauberhorn-rom.md).
`dcs-rom-thx` checks extracted-THX compatibility; `dcs-lauberhorn` runs actual DCU
full-set/LCI tests using `dcsRom`. Both gate the Vivado input bundle. They do not
replace whole-slice routing tests or hardware validation.

## TLA+ source and module checks

Start with the [model index](../../model/README.md) and
[module review](../hardware/model-review.md). The active model is LCI-only;
source, configuration, address data and historical evidence are separate.
`nix build .#checks.x86_64-linux.tla-modules` checks the inventory/import boundaries
and SANY semantics. `scheduling-transport` checks focused transport/handoff cases;
`steady-progress`, `scheduling-races` and `scheduling-kill-exit` retain their
separate scopes. Full combined temporal verification remains open.

The [AXI/coherence response suite](../hardware/axi-response-contract.md) runs with
`nix build .#checks.x86_64-linux.scheduling-axi` or the scheduling runner
`--axi-only`. It checks earlier and buffered reads, stop fencing and response
service with deliberate faults. These are bounded contract checks, not RTL refinement.

The [kill/transport composition](../hardware/kill-transport-composition.md) runs
with `nix build .#checks.x86_64-linux.scheduling-kill-transport` or the scheduling
runner `--kill-transport-only`. It joins existing control/resource properties,
RX/TX cleanup, packet ownership and stalled/live peers.

The [joined scheduling composition](../hardware/scheduling-composition.md) runs
with `nix build --max-jobs 3 .#checks.x86_64-linux.scheduling-full` or the scheduling runner
`--full-only`. It composes the focused wake/claim models with kill/transport,
checks cross-component relations, and retains per-case TLC logs and configs in
the Nix output. Cases are separately cacheable derivations, each with four TLC
workers and a 4 GiB heap. Timeouts are two hours, or six hours for the larger
`full-stalled-progress` case. See its report for current
validation status and finite bounds.

### RX writeback regression reproducer

The October 2 board failure is reproduced by connecting the actual generated RX
AXI router to the actual DCU and `axi_wr_cl` using the selected ROM. Run from the
repository root; output directories must be new:

```sh
nix build .#dcsRom --out-link out/rx-writeback-rom
nix develop -c mill gen.test.runMainLocal \
  lauberhorn.host.eci.GenRxWritebackFixture "$PWD/out/rx-writeback-rtl"
nix develop -c python3 tools/progress/check_dcs_pressure.py \
  --rom out/rx-writeback-rom \
  --rx-router out/rx-writeback-rtl/RxWritebackFixture.v \
  --output out/rx-writeback-check
```

The regression now requires the production RX sink to accept both write beats,
return B and release WDDA, LCIA and the next RX control read. Controls check a
no-data HAKD and an independent test sink which deliberately withholds B: the
latter must block completion until B is released. The test uses synthetic
returned data, manual initial DC read completion, test XPM RAM and direct
LCIA-to-`invDone` wiring. It excludes the full slice data buffer/crossbar, THX
and higher-level RX protocol. The [return-half design note](../hardware/rx-writeback.md)
describes the current discard policy and possible future uses.

The independent-channel test also runs in the fast ECI CI suite:

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.RxWritebackSim
```

It checks W-before-AW, delayed W, queued IDs, zero/partial strobes, stable B under
backpressure and quiescence. The historical failing diagnostic and board
comparison remain in the [October 2 report](hardware-test-results.md#rx-regression-comparison-ra2-to-ra3--2026-10-02).

## Layered coherence contracts

Run `nix build .#checks.x86_64-linux.coherence-layers` for generated DC/THX
line exchanges, repeated worker operations, fault cases and the detailed
steady-state projection. `tla-modules` enforces layer imports and parses every
module. See [bounds, optional products and remaining obligations](../hardware/layered-verification.md).

## Cooperative scheduling and slice routing

`checks.x86_64-linux.scheduling-cooperative` checks the idle notification,
interrupt-free handoff, claim exchange and pool-bootstrap models. The focused
`CooperativeYieldSim` and `CooperativeAllocationSim` suites cover delayed cleanup,
shared AXI-Lite contention, zero ordinary IRQs, idle-first allocation and migration.
`OncRpcSim` checks one/two/three stalled workers with independent worker and
simultaneous bypass RX/TX progress. See the [evidence ledger](../hardware/eci-implementation.md)
for current results and remaining hardware obligations.

The pinned `dcs-lauberhorn` check now runs both the real DCU tests and
`tools/progress/check_slice_routing.py`. The latter sends 812 selected-map
address trials through the production 32-DCU slice control array, routers and
arbiters. It verifies address/ID preservation through read completion, dirty
write completion and backpressured LCIA. The XPM RAM and passive trace outputs
are test models; the payload datapath and ThunderX are not instantiated. The
runner removes one unmatched `%d` from an upstream unsupported-size diagnostic
in a temporary compile copy; it does not alter protocol logic or the dependency.
Source hashes and that normalization are recorded in `slice-summary.json`.
