# Lauberhorn

Lauberhorn is an RPC-accelerating NIC for Enzian.  The hardware is written in
SpinalHDL, simulation is driven through Mill/ScalaTest/Verilator, and the
software stack contains the Enzian kernel module, userspace runtime, and demo
applications.

See [HW-ARCH.md](docs/HW-ARCH.md) for the hardware source structure, `NicEngine`
plugin layout, and elaboration flow.

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
and defaults to `lauberhorn.host.eci.NicSim` if no suite is supplied.

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
  and publishes the bitstream, probes, routed checkpoint, generated headers,
  and generated device description artifacts.
- `publish`: tag-only release job that uploads the `build-hw-eci` artifacts.

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

PCIe generation tasks still exist for the legacy path:

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
For manual development inside `nix develop`, run `mill eci.generateVerilog`
first so generated headers exist before invoking lower-level `make` targets.

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
