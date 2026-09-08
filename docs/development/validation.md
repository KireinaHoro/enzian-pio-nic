# Validation and simulation

Real-machine setup and execution are covered by [real hardware testing](hardware-test.md).

Scope: source-inspected test entry points and coverage limitations, 2026-09-08. No simulation or hardware build was run to create this document.

## Select the smallest relevant check

```sh
# All untagged/fast Lauberhorn tests (broader than CI).
nix develop -c mill gen.test -- -l org.scalatest.tags.Slow

# Exact CI scope: only ECI package, exclude Slow.
nix develop -c mill gen.test -- -l org.scalatest.tags.Slow -m lauberhorn.host.eci

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

`lauberhorn.host.eci.NicSim` is a shared **trait**, not a runnable suite. The shell's `repeat-test` default incorrectly names that trait; always provide a concrete suite:

```sh
nix develop -c repeat-test rx-tx-interleaved lauberhorn.host.eci.OncRpcSim
```

This loops until failure; bound/stop it deliberately. ScalaTest parallel execution is available with `mill gen.test -- -P8`; Mill module-level test parallelism is disabled to avoid repeat Verilator compilation. Start with one reproducer when investigating nondeterminism.

## Evidence to retain from a failure

- Simulation config uses Verilator, FST waves, and a `verilator-cache` path; emitted paths depend on the Mill test working directory. Use the printed workspace path instead of assuming a fixed root-level `simWorkspace`.
- The shared `DutSimFunSuite` writes `sim_transcript.log.gz` and prints a reproducer including `-DsetupSeed=... -DsimSeed=... -DprintSimLog=true`. Preserve both seeds, test name, revision/submodule revisions, and generated config.
- ECI tests using `testWithDB` emit `lauberhorn_trace.pcapng` from a `finally` block. This supports causal event inspection even on failure; load the repository Lua dissector as described in the [trace tools](../../data/eci/sys_trace/README.md).
- PCAP replay tests load `data/eci/iladata/rx-lockup{,-2}.pcap` and use libpcap through pcap4j. Nix configures `LD_LIBRARY_PATH`; missing native libpcap can fail before an RTL assertion.
- For simulator changes in dependencies, inspect/update the submodule rather than copying its implementation into the platform. CI only runs blocks regression when its submodule path changes.

## Coverage limits that affect claims

- `OncRpcSim` has registered but empty TODO test bodies: `rx-hol-blocking-free`, `rx-preempt-no-leaking`, and `rx-sched-crit-timeout`. A passing suite does **not** validate those properties.
- `rx-tx-interleaved` is tagged `Slow` with an address-map FIXME; fast CI excludes it. RX size scans and TX scan tests also use `Slow`.
- `PacketAllocSim` explicitly leaves multi-configuration and block-until-free checks incomplete.
- The Scala host model approximates software IRQ/scheduling behavior. Passing RTL simulation does not validate the actual Linux module, aarch64 atomics, or real ECI cache behavior.
- `model/lauberhorn.tla` and Toolbox models describe isolation/preemption; the checked-in small model config names type, cacheline-exclusion, preemption and isolation invariants plus temporal properties. Generated model outputs are historical artifacts, not proof that current RTL/software satisfies the model. No TLC build/CI task is wired here; reconcile source and model snapshots before using results in a paper.
- Existing `data/` captures and plots are debugging/measurement evidence with their own provenance. They are not a current benchmark result merely because they are checked in.

After a behavioral change, run the relevant suite and elaborate the affected hardware; require real target validation for scheduler/ECI deployment claims. For docs-only changes, link/path and command-definition checks suffice.

Evidence: [test build graph](../../build.mill), [simulation config](../../hw/src/lauberhorn/Config.scala), [shared fixture](../../deps/blocks/tester/src/jsteward/blocks/DutSimFunSuite.scala), [ECI fixture](../../hw/test/src/lauberhorn/host/eci/NicSim.scala), [trace fixture](../../hw/test/src/lauberhorn/sim/DbFactory.scala), [RPC tests](../../hw/test/src/lauberhorn/host/eci/OncRpcSim.scala), [model](../../model/lauberhorn.tla), [small model config](../../model/lauberhorn.toolbox/lauberhorn_model_isolation/MC.cfg).
