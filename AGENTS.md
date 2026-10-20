# Lauberhorn agent entry point

This repository builds an OS-integrated smart NIC on Enzian. Start with
[the system map](docs/architecture/overview.md), then read only the topic needed
for your task. Check relevant source before relying on implementation details.

## Skills and documentation conventions

Use these tracked skills for their respective workflows:

- [documentation-discipline](../../skills/documentation-discipline/SKILL.md): inspect and update affected documentation when a task changes documented behavior, interfaces, workflows, or design; small fixes preserving the documented contract do not require review. README and `docs/` serve human readers; `agents/` holds agent work context and progress.
- [paper-chart](../../skills/paper-chart/SKILL.md): reproducible publication figures; apply the [local chart contract](docs/development/paper-charts.md).
- [benchmark-campaign](tools/paper/skills/benchmark-campaign/SKILL.md): RPC measurement campaigns.
- [enzian-deploy](tools/enzian/skills/enzian-deploy/SKILL.md): Enzian deployment and acceptance.
- [lauberhorn-trace](tools/enzian/skills/lauberhorn-trace/SKILL.md): FPGA trace preservation and capture.
- [tofino-capture](../tofino-hw-timestamping/skills/tofino-capture/SKILL.md): Tofino MAC timestamp capture.

Shared skills are tracked in `~/work-local/skills`; Tofino capture is tracked in
`../tofino-hw-timestamping`. Installed entries under `~/.codex/skills/` link to
these canonical sources. Edit the tracked source rather than keeping local copies.

## Task routing

- Routed timing / STA / pipelining / floorplanning: [physical implementation and evaluation](docs/hardware/physical-implementation.md), [paused continuation](agents/physical-implementation.md); run `tools/physical/checkpoint.tcl` on enzian-ba2, read the `tools/physical/summarize.py` digest first.
- FPGA datapaths, ECI, buffers: [architecture](docs/hardware/architecture.md), [status](docs/hardware/implementation-status.md).
- Linux scheduling/preemption: [scheduler](docs/software/scheduler.md).
- C runtime and applications: [runtime](docs/software/runtime.md), [programming model](docs/software/programming-model.md).
- Nix/Mill/Vivado/software builds: [workflow](docs/development/workflow.md), [Nix-to-Vivado CI handoff](docs/development/ci.md).
- Real Enzian hardware / adder E2E: use the [enzian-deploy skill](tools/enzian/skills/enzian-deploy/SKILL.md), then [hardware test quickstart](docs/development/validation.md#board-validation) → [example manifest](tools/enzian/adder.example.json) → `python3 tools/enzian/test.py CASES.json --logs NEW_DIR --repeats 2`. Check reservation and stage matching artifacts first; read `summary.json`, not full boot logs.
- Trace capture: use the [lauberhorn-trace skill](tools/enzian/skills/lauberhorn-trace/SKILL.md) and [canonical workflow](tools/enzian/trace/README.md). Manual and campaign capture share the same scripts; CMAC for full dumps, JTAG only for bounded integrity checks. Failed Ethernet capture stops without reset or full JTAG fallback.
- Formal verification (TLA+, model checking and RTL verification), assumptions, bounds and results: [formal documentation](docs/formal/README.md). Model source, configurations and machine-readable evidence stay under `model/`.
- Simulation, trace analysis, validation selection: [validation](docs/development/validation.md).
- Research claims and evaluation: [paper goals](docs/research/paper-goals.md).
- Go runtime / HOST_WORK / future etcd: [design and dispatch matrix](docs/proposals/go-etcd.md); documentation only, no implementation authorized by this handoff.
- External TCP/Protobuf projects and later gRPC scope: [protocol roadmap](docs/proposals/grpc.md), [TCP](docs/research/tcp.md), [Protobuf](docs/research/protobuf.md).
- Pending features and unresolved intent: [roadmap](docs/research/roadmap.md).

## Agent context

Read the [goal index](agents/README.md) before resuming work. It records
constraints, evidence, pending checks and dated hardware observations. Recheck
reservations and live state before board access; preserve failures before reset.
Timing optimization remains paused; toolkit changes must not enter toolkit
master without colleague upstream approval.

## Confirmed research and scheduling scope

The [full-paper goal](docs/research/paper-goals.md) is efficient low-latency RPC
on published 2F2F with OS flexibility and security. [Caladan](docs/research/caladan-enzian.md)
is the primary baseline. Active application goals include RPC, userspace NFS,
memcached and consensus such as etcd/Raft. Tasks run to finish on isolated RPC
worker cores, never shared with ordinary tasks; complete timeout/kill recovery
is an open design problem.

[Dandelion](docs/applications/dandelion.md) is bracketed for the paper because
process-sandbox overhead dominates; its student evaluation used a software mock. External TCP/Protobuf integration
from `../flavian-tcp` and `../elena-protobuf` is below first-paper application
priority; fully offloaded gRPC is a potential follow-on paper. See the
[protocol roadmap](docs/proposals/grpc.md) and
[runtime/application continuation](agents/runtime-applications.md).

## Working rules


- Be disciplined about what enters the repository. `tools/` is for maintained,
  reusable tooling with a clear owner and supported workflow, not a catch-all.
  Keep one-off probes, experiments, temporary build recipes, debug scripts and
  raw diagnostic output in ignored `out/` or temporary directories; do not commit
  them. Promote a tool only when it has an ongoing purpose and fits the shared
  package/build/deployment path. Retire superseded tools, scripts, outputs and
  obsolete narrative from the current tree instead of retaining parallel historical
  implementations or appending an experiment diary. Git history preserves earlier
  work; recover it there when needed. Keep current documentation concise and
  explicit about what is implemented, deployed and validated. This does not remove
  the requirement to track data and reproduction scripts actually used by active
  paper charts, or maintained regression tests for supported functionality.

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
- Provision Enzian DUTs and traffic-generator/capture peers exclusively through
  `emg`, including boot-image/kernel selection, kernel arguments and root volumes.
  Never edit PXE/GRUB configuration or iSCSI/TGT exports directly, or manage their
  backing volumes by hand. Use the repository harness for power sequencing,
  FPGA programming, boot validation and tests.
- Use `nix develop` and source-defined Mill targets. Choose targeted validation
  from the [validation guide](docs/development/validation.md); for real hardware use
  the [automated test routine](docs/development/validation.md#board-validation). Vivado and
  deployment require platform prerequisites.
  State which checks actually ran. Historical logs are not current test results.
- If a hardware test fails in `probe_versions`, preserve the failure before
  resetting or restoring another image: freeze tracing, record VIO status, and
  dump trace DDR over the dedicated F_MAC1 CMAC with the matching trace map
  and bitstream provenance; follow the [capture procedure](docs/development/tracing.md#default-agent-capture-pipeline).
  If Ethernet is unavailable or a bounded dump fails, preserve the partial output
  and stop without a full JTAG fallback or reset. Never reset merely to recover the capture link.
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
