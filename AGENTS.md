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
- Simulation, trace analysis, validation selection: [validation](docs/development/validation.md).
- Research claims and evaluation: [paper goals](docs/research/paper-goals.md).
- External TCP/Protobuf projects and later gRPC scope: [protocol roadmap](docs/research/grpc-roadmap.md), [TCP](docs/research/tcp.md), [Protobuf](docs/research/protobuf.md).
- Pending features and unresolved intent: [roadmap](docs/research/roadmap.md).

## Confirmed research and scheduling scope

The full paper builds an efficient low-latency RPC system on published 2F2F,
preserving OS flexibility and security. Demikernel is the primary baseline.
Planned applications: Dandelion, RPC server, userspace NFS, memcached, and
consensus such as etcd/Raft. Read [Dandelion](docs/research/dandelion.md) before
porting student runtime work from `../sven-dandelion`; its evaluation uses a
software mock. Tasks run to finish on isolated RPC worker cores, never shared
with ordinary tasks. Timeout/kill recovery and QP/DCU coloring remain open.
Finished TCP/Protobuf student work lives in `../flavian-tcp` and
`../elena-protobuf`; merge soon to limit drift, below first-paper application
priority. Fully offloaded gRPC is a potential follow-on paper.

## Working rules

- Keep work organized in goal-focused branches with coherent commits. When a goal
  is complete, merge its branch into this worktree's `master` and push the branch
  and `master` to the private GitLab remote. Keep independent experiments on
  separate branches. Do not accumulate completed work as uncommitted changes or
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
- Read the ECI progress and routing notes before changing cache-line layouts,
  barriers, preemption ordering or ownership. Local address arithmetic alone does
  not establish coherence progress or isolation.
- Use `nix develop` and source-defined Mill targets. Choose targeted validation
  from the [validation guide](docs/development/validation.md); for real hardware use
  the [automated test routine](docs/development/hardware-test.md). Vivado and
  deployment require platform prerequisites.
  State which checks actually ran. Historical logs are not current test results.
- Search first-party paths first (`hw`, `sw`, `docs`, `build.mill`, `flake.nix`,
  `vivado/eci/rtl`); inspect submodules when the task crosses those boundaries.
  Avoid broad recursive reads of `out`, `result`, trace captures and dependencies.
- Keep these short topic docs current when changing contracts, workflows or feature
  status. Preserve detailed existing design notes and link them rather than
  duplicating them. Ask the maintainer about unresolved intent when it affects work.
