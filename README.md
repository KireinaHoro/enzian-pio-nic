# Lauberhorn

Lauberhorn is a smart NIC designed to integrate tightly with the OS scheduler.
The Enzian FPGA prototype combines coherent RPC delivery over ECI with hardware
request scheduling, Linux worker management, and a userspace RPC runtime. The
[HotOS 2025 workshop paper, *The NIC should be part of the OS.*](https://arxiv.org/pdf/2501.10138)
explains the research direction. The [published coherent datapath (2F2F)](https://arxiv.org/html/2409.08141v3)
is the foundation; the full-system goal is low-latency, efficient RPC without
sacrificing OS flexibility and security.

The active implementation uses SpinalHDL, mixed-language ECI/Vivado integration,
and an AArch64 Linux software stack. Ethernet/IPv4/UDP/ONC-RPC server paths and
nested call/reply hardware exist; the C runtime currently exposes synchronous
server handlers with software XDR. PCIe is a legacy, incomplete path, and
TCP/HTTP/gRPC are placeholders in this checkout. See the status docs before
assuming that a paper proposal or design note is implemented.

RPC workers use isolated cores and run tasks to finish; timeout/recovery policy
and queue-pair/DCU coloring remain open. The planned evaluation uses Demikernel
as its primary baseline, with Dandelion, an RPC server, userspace NFS, memcached,
and a consensus service such as etcd/Raft. The [Dandelion student project](docs/research/dandelion.md)
provides a mock-based port and nested-call runtime reference.

## Documentation

Humans: start with the [system map](docs/architecture/overview.md), then choose:

- [FPGA architecture](docs/hardware/architecture.md) and [implementation status](docs/hardware/implementation-status.md).
- [Scheduler integration](docs/software/scheduler.md), [runtime](docs/software/runtime.md), and [application programming model](docs/software/programming-model.md).
- [Build/deployment workflow](docs/development/workflow.md) and [simulation/validation guide](docs/development/validation.md).
- [Full-paper goals and evidence plan](docs/research/paper-goals.md) and [pending features/design questions](docs/research/roadmap.md).

Agents: [AGENTS.md](AGENTS.md) is the repository entry point, with task-specific
reading routes and implementation constraints. The concise docs were surveyed
at `b86c68c` on 2026-09-08; they link to source and older detailed design notes.

## Setup

Use the Nix flake for development.  It provides Mill, JDK, Verilator, GHDL,
formal tools, Mackerel, the aarch64 cross compiler, and the helper scripts used
by this repository.

```console
$ git clone --recursive <lauberhorn-platform-url>
$ cd platform
$ nix develop
```

One-shot commands can be run without entering an interactive shell:

```console
$ nix develop -c mill gen.test -- -l org.scalatest.tags.Slow
```

The old Docker image is no longer the preferred local environment.  Use it only
when reproducing old CI runs or debugging container-specific behavior.

## Hardware Tests

Run the Scala/Verilator simulation suites through the Nix shell.

```console
# Fast/default test pass: exclude ScalaTest's Slow tag.
$ nix develop -c mill gen.test -- -l org.scalatest.tags.Slow

# Full simulation pass, including slow integration tests.
$ nix develop -c mill gen.test

# Ask ScalaTest to run suites in parallel.  Mill keeps module-level test
# parallelism disabled to avoid repeatedly rebuilding Verilator models.
$ nix develop -c mill gen.test -- -P8
```

Run one suite:

```console
$ nix develop -c mill gen.test.testOnly lauberhorn.PacketAllocSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcNestedSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.RxBypassSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.TxBypassSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.RxReplayPcapSim
$ nix develop -c mill gen.test.testOnly lauberhorn.host.pcie.NicSim
```

Run the `deps/blocks` regression suite used by Lauberhorn.  Quote the Mill
target so the shell does not treat the cross-version brackets as a glob.

```console
$ nix develop -c mill 'blocks[2.13.12].test'
```

CI runs this blocks suite only when the `deps/blocks` submodule is bumped.

Run one named test inside a suite:

```console
$ nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim -- -t rx-tx-interleaved
```

Repeat a flaky test until it fails.  `repeat-test` is provided by the dev shell
but its default names the shared `NicSim` trait, not a runnable suite. Always
supply a concrete suite:

```console
$ nix develop -c repeat-test rx-tx-interleaved lauberhorn.host.eci.OncRpcSim
```

Simulation transcripts are written under `out/` and `simWorkspace/`, depending
on the suite and generated Verilator workspace.  Compressed transcripts can be
viewed directly with `less`, `vim`, or `zcat`.

## CI

GitLab CI currently runs these jobs:

- `fast-tests-eci`: fast ECI Scala/Verilator tests on every pipeline, using
  `mill gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci`.
- `blocks-tests`: the `deps/blocks` regression suite, using
  `mill 'blocks[2.13.12].test'`; this job is only triggered when the
  `deps/blocks` submodule path changes.
- `build-hw-eci`: the ECI Vivado bitstream build, using
  `mill --no-server eci.generateBitstream`; this runs after `fast-tests-eci`
  and declares bitstream, probes, routed checkpoint, header, and device-description
  artifacts. Some header/device artifact paths are stale; see the workflow guide.
- `publish`: tag-only release job; its artifact list still includes obsolete
  software and disabled PCIe outputs. Review paths before relying on a release.

CI does not currently run software builds, the full slow suite, or TLA+ checks.
Some registered simulation tests have empty TODO bodies; see the
[coverage limits](docs/development/validation.md).

## Build Products

Generate ECI RTL and generated configuration headers:

```console
$ nix develop -c mill eci.generateVerilog
```

Build the same RTL/configuration output as a Nix package:

```console
$ nix build .#genVerilog -L
```

Generate the ECI Vivado project or bitstream from inside the dev shell:

```console
$ nix develop -c mill --no-daemon eci.vivadoProject
$ nix develop -c mill --no-daemon eci.generateBitstream
```

The ECI bitstream flow downloads the configured static shell checkpoint during
the Mill task.  Use `--no-daemon` for Vivado-related tasks so a stale Mill
server does not keep an old environment.

PCIe generation tasks still exist for the legacy path, but contain unfinished
preemption support and are not a supported equivalent of the ECI build:

```console
$ nix develop -c mill --no-daemon pcie.generateVerilog
$ nix develop -c mill --no-daemon pcie.generateBitstream
```

## Software Builds

Build software artifacts with Nix:

```console
# Kernel module for Enzian/aarch64.
$ nix build .#kmod -L
$ file result/lauberhorn.ko

# Userspace runtime library.
$ nix build .#runtime -L
$ file result/liblauberhorn.so

# SquashFS deployment image containing the kernel module and demo apps.
$ nix build .#deployFs -L
```

The Nix builds generate RTL-derived headers and Mackerel headers automatically.
For manual development, RTL generation alone is insufficient: Makefiles require
`HW_CFG_HDRS` and `MACKEREL_DEV_HDRS` pointing to generated C headers, plus target
libtirpc or kernel build dependencies. Prefer the Nix packages; see the
[workflow guide](docs/development/workflow.md) for artifact contracts and known
Vivado output-path discrepancies.

## Trace Tools

The system trace exporter and Wireshark Lua dissector live in
`data/eci/sys_trace`.  See [data/eci/sys_trace/README.md](data/eci/sys_trace/README.md)
for converting trace buffers or legacy Vivado ILA CSV captures to pcapng.

## Enzian Use

Build the bitstream, kernel module, runtime, and deployment image as above,
then boot an Enzian with the matching FPGA image.  The kernel module exposes
`/dev/lauberhorn` and the bypass network interface `lauberhorn0`; see
[sw/kmod/README.md](sw/kmod/README.md) for module loading and interface setup.

## Development Notes

Create an IntelliJ IDEA project:

```console
$ nix develop -c mill mill.idea.GenIdea/idea
```

Update the Mill dependency lock after changing Mill dependencies:

```console
$ nix develop -c update-mill-lock
```

If Mill keeps using stale environment variables, stop the daemon or run the
command with `--no-daemon`.
