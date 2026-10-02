# Lauberhorn agent entry point

This repository builds an OS-integrated smart NIC on Enzian. Start with
[the system map](docs/architecture/overview.md), then read only the topic needed
for your task. These notes were surveyed at `b86c68c` on 2026-09-08; check the
relevant source and subsequent changes before relying on implementation details.

## Task routing

- Routed timing / STA / pipelining / floorplanning: [physical implementation](docs/hardware/physical-implementation.md), [checkpoint findings](docs/hardware/physical-findings.md), [active CI experiments](docs/hardware/physical-experiments.md); run `tools/physical/checkpoint.tcl` on enzian-ba2, read the `tools/physical/summarize.py` digest first.
- FPGA datapaths, ECI, buffers: [architecture](docs/hardware/architecture.md), [status](docs/hardware/implementation-status.md).
- Linux scheduling/preemption: [scheduler](docs/software/scheduler.md).
- C runtime and applications: [runtime](docs/software/runtime.md), [programming model](docs/software/programming-model.md).
- Nix/Mill/Vivado/software builds: [workflow](docs/development/workflow.md), [Nix-to-Vivado CI handoff](docs/development/ci.md).
- Real Enzian hardware / adder E2E: [hardware test quickstart](docs/development/hardware-test.md) → [example manifest](tools/enzian/adder.example.json) → `python3 tools/enzian/test.py CASES.json --logs NEW_DIR --repeats 2`. Check reservation and stage matching artifacts first; read `summary.json`, not full boot logs.
- Trace capture: use the [CMAC-first agent procedure](docs/development/tracing.md#default-agent-capture-pipeline) on F_MAC1; JTAG is the fallback for unavailable Ethernet and independent spot checks.
- Simulation, trace analysis, validation selection: [validation](docs/development/validation.md).
- Research claims and evaluation: [paper goals](docs/research/paper-goals.md).
- External TCP/Protobuf projects and later gRPC scope: [protocol roadmap](docs/research/grpc-roadmap.md), [TCP](docs/research/tcp.md), [Protobuf](docs/research/protobuf.md).
- Pending features and unresolved intent: [roadmap](docs/research/roadmap.md).

## Current handoff

Timing optimization is paused at the maintainer's request. Platform master
adopts TX placement/refinement plus high-VC RX staging; selected CI revision
`1c72ea0` reached WNS -0.007 ns with 17 failing endpoints, not closure.
See the [closeout and remaining work](docs/hardware/physical-experiments.md#paused-after-consolidation--september-17).
DCS/low-VC/combined experiments remain unmerged. Toolkit commits stay on
separate feature branches/private refs pending colleague upstream approval;
never merge or push them to toolkit master. The existing ba2 local batch can
finish; no new timing work or CI polling until requested. Final master has no
new timing-closure result, and the static shell remains unchanged. The merged
CMAC trace path uses F_MAC1; revision `5cda901` passed 300 RPCs and three full
322 MB dumps, with WNS -0.062 ns (not closure). See the
[CMAC board evidence](docs/development/cmac-trace-diagnosis.md#f_mac1-board-validation-september-28).

The selected bounded Goal 2 modeling round is complete; see the
[scheduling composition report](docs/hardware/scheduling-composition.md). The
source build selects the simplified LCI-only DC with UL removed. The 8 KiB
physical-worker address layout and cooperative PREEMPT ABI are the sole ECI
implementation on `feat/cooperative-yield`; see the [implementation evidence](docs/hardware/eci-implementation.md).
Local model, simulation and software checks passed. Pipeline 515944 (`8cc9ad58`)
passed all 13 gates and hardware/report jobs. Its first driver load exposed a
preemption-table index bug, fixed at `336c65d6`. A corrected module built against
the exact candidate headers passed 100 RPCs. Fresh RX-timeout boundary refinement
is running; full board acceptance remains outstanding.
The initial October 2 simplified-DC trial exposed missing RX AXI write service.
The independent RX write sink at `a51139e` passed pipeline 515802 and two
cold-reset Enzian trials (200 correct RPCs); a matched trace confirms the formerly
stuck RX writeback now completes. See the [board evidence](docs/development/hardware-test-results.md#rx-writeback-fix--2026-10-02).
That historical image had WNS −0.005 ns. Current candidate 515944 has WNS
−0.089 ns and 474 failing setup endpoints; timing remains open. The
[layered verification report](docs/hardware/layered-verification.md) records
DC/THX endpoint checks and remaining race/refinement gaps.
DUT zuestoll14 and capture peer zuestoll12 are in the cooperative acceptance
campaign. Consult `out/board/cooperative-515944-refine/summary.json` and the
evidence ledger before access; do not assume a healthy service or reset a frozen
failure. Recheck reservations. Continue acceptance while retaining documented
hardware-service assumptions.

## Confirmed research and scheduling scope

The full paper builds an efficient low-latency RPC system on published 2F2F,
preserving OS flexibility and security. Demikernel is the primary baseline.
Planned applications: Dandelion, RPC server, userspace NFS, memcached, and
consensus such as etcd/Raft. Read [Dandelion](docs/research/dandelion.md) before
porting student runtime work from `../sven-dandelion`; its evaluation uses a
software mock. Tasks run to finish on isolated RPC worker cores, never shared
with ordinary tasks. Complete timeout/kill recovery remains open. The selected QP/DCU coloring is
implemented; board progress acceptance is still required.
Finished TCP/Protobuf student work lives in `../flavian-tcp` and
`../elena-protobuf`; merge soon to limit drift, below first-paper application
priority. Fully offloaded gRPC is a potential follow-on paper.

## Working rules

- Keep work organized in coherent, goal-focused commits. Simple single-commit
  changes may be committed directly on this worktree's `master`. Use separate
  branches for larger goals and independent experiments; when complete, merge
  into `master` and push to the private GitLab remote. Remove agent-created local
  and private-remote branches once merged and all CI jobs using their refs have finished; preserve unmerged experiments and
  branches belonging to others. Do not accumulate completed work as uncommitted changes or
  untracked source/docs in the master worktree; track intended project files with
  their goal and keep generated artifacts in ignored output directories. If work
  must remain unfinished, state its branch and remaining changes explicitly.

- Treat source as evidence of implementation, the workshop paper as design intent,
  and proposals as proposals. Do not claim full paper functionality is complete.
- ECI is the active prototype. PCIe has unfinished preemption; TCP/HTTP/gRPC files
  are placeholders in this checkout. Nested RPC hardware exists, but inspect
  software support before promising an application API.
- Keep NIC datapath IDs, Linux CPU IDs, process IDs and thread/QP identities
  distinct. Datapath 0 is bypass; generated ECI has four workers by default.
- Hardware descriptors, generated Mackerel/config headers, kernel mappings and
  runtime cache-line accesses must agree. Regenerate collateral after ABI changes;
  do not hand-edit generated output or use an unmatched bitstream/header pair.
- Prefer hardware byte-order conversion for numeric ECI fields. Keep scalar
  descriptor fields naturally aligned using padding; do not rely on generator
  support for unaligned access. Preserve wire-native packet bytes and Linux
  network-stack values where conversion would add software work. See the
  [ECI boundary contract](docs/software/nested-rpc.md#endianness-at-the-host-boundary).
- Read the ECI progress and routing notes before changing cache-line layouts,
  barriers, preemption ordering or ownership. Local address arithmetic alone does
  not establish coherence progress or isolation.
- After launching hardware CI, follow the [scheduled CI workflow](docs/development/ci.md#scheduled-hardware-build-follow-up): check tests/preparation and HW start after 30 minutes, then wait five hours before collecting results and testing on Enzian. Inspect failed gates; fix only trivial causes, otherwise report the diagnosis and stop. Keep pipeline refs until every job finishes.
- Use `nix develop` and source-defined Mill targets. Choose targeted validation
  from the [validation guide](docs/development/validation.md); for real hardware use
  the [automated test routine](docs/development/hardware-test.md). Vivado and
  deployment require platform prerequisites.
  State which checks actually ran. Historical logs are not current test results.
- If a hardware test fails in `probe_versions`, preserve the failure before
  resetting or restoring another image: freeze tracing, record VIO status, and
  dump trace DDR over the dedicated F_MAC1 CMAC with the matching trace map
  and bitstream provenance; follow the [capture procedure](docs/development/tracing.md#default-agent-capture-pipeline).
  If Ethernet is unavailable or a bounded dump fails, preserve the partial output
  and use JTAG before reset. Never reset merely to recover the capture link.
  Investigate the captured ECI traffic to pinpoint the failure; this recurring,
  hard-to-catch issue warrants capture without asking again. Keep tracing active
  through module load. If it was already stopped or the window was lost, record
  that limitation and reproduce with tracing armed before drawing conclusions.
- After releasing the BDK menu, require QLM8..13 CDR lock and the complete
  initialized CCPI/ECI lane list 0..23 before loading the driver. If either is
  missing or incomplete, preserve the boot log, perform a full verified cold
  reset, reprogram, and retry; do not treat Linux login alone as successful
  ECI bring-up. Use the boot helper's bounded retries and report exhaustion.
- For Enzian boot/test runs, the agent reads only compact stage/results output
  and `summary.json` by default. Do not open, tail, or search raw console, BMC,
  programmer, or CPU logs during normal progress or after success. Read raw
  logs only when a reported error needs diagnosis, and then only the relevant
  failed stage and a bounded excerpt. Let the expect-based flow validate boot
  markers and perform retries; retain raw logs on disk without loading them
  into the conversation. This keeps routine testing within a small token budget.
- Search first-party paths first (`hw`, `sw`, `docs`, `build.mill`, `flake.nix`,
  `vivado/eci/rtl`); inspect submodules when the task crosses those boundaries.
  Avoid broad recursive reads of `out`, `result`, trace captures and dependencies.
- Keep these short topic docs current when changing contracts, workflows or feature
  status. Preserve detailed existing design notes and link them rather than
  duplicating them. Ask the maintainer about unresolved intent when it affects work.
