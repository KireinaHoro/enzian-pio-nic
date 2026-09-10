# Real Enzian hardware test

Use a reserved machine, matching CI bitstream and software closure, and retain
job ID, commit, artifact checksums, console logs and client correctness CSV.
The [quickstart](https://gitlab.inf.ethz.ch/project-openenzian/documentation/userguide/-/jobs/artifacts/main/raw/enzian_quickstart.pdf?job=build)
describes power sequencing; the [cluster inventory](https://enzian.systems/generated/cluster-info.html)
maps machines to JTAG cables.

## Reservation and prerequisites

On `enzian-gateway`, run `emg list-machines`. If zuestoll14 is unreserved:

```sh
emg acquire zuestoll14 -n lauberhorn-linux-6.8 \
  -a 'isolcpus=nohz,domain,managed_irq,44-47 nohz_full=44-47 rcu_nocbs=44-47 irqaffinity=0-43 kthread_cpus=0-43 rcu_nocb_poll' \
  -g 2025-07-28
```

Use `console zuestoll14-console` and `console zuestoll14-bmc`; `console zuestoll14`
is ambiguous on this gateway. Detach with Ctrl-E, c, period. Do not take over
another attached console automatically.

Build `nix build .#deployFs -L` at the CI hardware revision. Verify CI success
and download `shell_lauberhorn-eci.bit` (and optionally `.ltx`); job artifacts
may exist for failed builds, so availability alone is insufficient. Keep the
hardware and software revision/configuration together; do not reuse an old
`/scratch/pengxu/deploy.img` without verifying its provenance.

CI revision `93f4c1d` predates the Kbuild release fix: its unmodified deployment
image builds successfully but embeds module vermagic `6.8.12`, incompatible with
the golden image's `6.8.0-64-generic`. Apply the `flake.nix` kernel-release fix
when preparing software for that hardware revision, and retain the patch with
the provenance. Do not force-load the mismatched module.

Pin the Git revision explicitly when the development worktree has newer commits
or pending edits; the revision is embedded in the generated hardware metadata.
For this older CI revision, review/export the packaging-only change and use the
[build helper](../../tools/enzian/build_deploy.py), which records the patch hash
and image checksum alongside the output link:

```sh
git diff 93f4c1da6d6dab2751ec26bc6985ebf275c0e6dd -- flake.nix > /tmp/kernel-release.patch
python3 tools/enzian/build_deploy.py 93f4c1da6d6dab2751ec26bc6985ebf275c0e6dd \
  --packaging-patch /tmp/kernel-release.patch --out-link /tmp/lauberhorn-deploy-ci-2811847
rsync -L --checksum /tmp/lauberhorn-deploy-ci-2811847 \
  enzian-gateway:/scratch/pengxu/deploy.img
```

The packaging patch must preserve hardware/ABI generation. The helper keeps CI
source and revision metadata while applying the explicitly recorded packaging
patch. For a later CI commit containing the fix, omit `--packaging-patch` to
build the unmodified commit normally.

The read-only [CI helper](../../tools/enzian/ci_artifacts.py) accepts an explicit
token file, queries only CI GET endpoints and refuses downloads until success:

```sh
python3 tools/enzian/ci_artifacts.py 2811847 --token-file ../.gitlab_token
# Wait up to three hours, then download only after success:
python3 tools/enzian/ci_artifacts.py 2811847 --token-file ../.gitlab_token \
  --wait-seconds 10800 --download /tmp/lauberhorn-artifacts-2811847
```

It records job metadata and SHA-256 checksums. The current GitLab project ID is
30605; the release configuration's historical 47960 does not identify this job.

## Programming

On 2026-09-08 the gateway's server on port 3121 was
`/opt/Xilinx/HWSRVR/2023.2/.../hw_server`. Matching Vivado is available on
`enzian-ba2` at `/opt/Xilinx/Vivado/2023.2/bin/vivado`. Recheck the server process
and installed tools before future runs. CI synthesis uses Vivado 2025.1.

Copy [program_fpga.tcl](../../vivado/eci/program_fpga.tcl) and the artifacts to
`enzian-ba2`. The Tcl script requires an exact cable ID and exactly one xcvu9p;
it fails if selection or programming fails. For zuestoll14:

```sh
/opt/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nojournal \
  -source program_fpga.tcl -tclargs \
  enzian-gateway.ethz.ch:3121 Digilent/210357B4B301A \
  shell_lauberhorn-eci.bit
```

Replace the bitstream argument with `--probe` to check connectivity and device
selection without programming. A powered-down FPGA may report no devices.

Before programming, halt Linux if running, then in `enzian-shell bringup` on
the BMC use `power_down()`, `common_power_up()`, `cpu_power_up()`. Watch the CPU
console concurrently: send `b` immediately at `Press 'B' for boot menu`, and
verify `Boot Options` followed by `Choice:`. Then `fpga_power_up()`, run the
programmer, and only after success send `n` on the CPU console.

[boot.py](../../tools/enzian/boot.py) automates this sequence with pexpect,
reservation verification, console transcripts and timeouts. It power-cycles
the machine: halt a running OS beforehand. Supply a programming command after
`--`, such as `ssh enzian-ba2` followed by the Vivado invocation above. Use a
new `--logs` directory each run. It leaves boot stopped on programming failure.
The script requires Python 3 with pexpect; the local workstation and
`enzian-ba2` have pexpect 4.8.0. To prepare while waiting for CI:

```sh
python3 tools/enzian/boot.py --logs /tmp/lauberhorn-hold-JOB --hold-only
```

If `print_voltage_all()` confirms all rails are off, use `--cold-start` to skip
`power_down()`: the latter fails when BMC sequencers are uninitialized, whereas
`common_power_up()` initializes them. After a successful hold, run with
`--resume-held`, a fresh log directory, and the actual programmer command after
`--`. This verifies the BDK menu before programming and sending `n`.
Cold-start, normal power-cycle/hold, failure handling and Linux continuation
have been exercised. Programming job 2811847 also succeeded; see the failed
positive-test result below.

## Linux and RPC validation

After Linux login, verify `uname -r`, `/proc/cmdline`, the isolated CPUs, and
`/scratch/pengxu` mount. Copy the built SquashFS to
`enzian-gateway:/scratch/pengxu/deploy.img` (the same shared path on the CPU).
The existing user deployment script mounts this image read-only at `/nix/store`;
first inspect any existing mount there and avoid hiding an active Nix store.
Follow the [module usage](../../sw/kmod/README.md)
for `insmod`, dmesg checks and the correct machine-specific MAC address.

The packaged application is `microbenchmarks add|mul [workers] [server_trace.csv]`.
The client in `sw/apps/microbenchmarks/client` checks arithmetic and request IDs
and exits nonzero on failure. Run from a host routed to the FPGA network, not
through a local loopback path; verify rpcbind service discovery and network
configuration before invoking `bench_client SERVER add 100 client.csv`.
A complete test requires successful module initialization, an operational
bypass interface, workers accepting RPCs and zero client correctness failures.
Boot or programming success alone is not an end-to-end RPC result.

The helpers compose independent operations:

| Helper | Responsibility |
| --- | --- |
| `boot.py` | Power cycle, catch BDK, invoke the programmer, resume Linux |
| `program_fpga.tcl` | Select the exact JTAG target and program or probe it |
| `run.py` | Run a command or local shell script as root over the CPU console; capture output and optionally assert ordered regexes |
| `cpu.sh mount IMAGE` | Mount a chosen SquashFS read-only at `/nix/store` |
| `cpu.sh load` | Check module vermagic against the running kernel and run `insmod` |
| `cpu.sh verify` | Compare the loaded hardware version with the closure's revision marker |
| `cpu.sh configure MAC CIDR` | Configure the bypass interface |
| `cpu.sh serve add/mul WORKERS NEW_LOG_DIR` | Start the packaged RPC server |
| `linux-smoke.sh IMAGE MAC CIDR NEW_LOG_DIR` | Compose the CPU steps for the positive test |

`boot.py` and `run.py` share reservation checks and console attachment code in
`console.py`. `run.py` accepts a local script via `--script`, followed by `--`
and script arguments, so `cpu.sh` need not be installed remotely:

```sh
python3 tools/enzian/run.py --logs /tmp/mount-run \
  --script tools/enzian/cpu.sh -- mount /scratch/pengxu/deploy.img
python3 tools/enzian/run.py --logs /tmp/load-run \
  --script tools/enzian/cpu.sh -- load
python3 tools/enzian/run.py --logs /tmp/verify-run \
  --script tools/enzian/cpu.sh -- verify
```

Use fresh log directories. The runner handles an existing shell or the documented
`enzian/enzian` console login; `ENZIAN_PASSWORD` overrides the password. Ordinary
commands must exit zero. Repeated `--expect` expressions instead require those
console messages in order, for tests where the command may oops or panic.
The runner does not reset the machine or infer which test to run.

To use `linux-smoke.sh` directly on the CPU, copy it and `cpu.sh` into the same
directory. It starts four add workers and leaves the server running for the
external client. CPU steps have no pinned image checksum, CI job or kernel
release; the module ABI check uses `uname -r`, and hardware compatibility uses
the mounted image's revision marker. Build/download hashes remain recorded
provenance, not deployment requirements. SquashFS detects read/decompression
errors, but its format does not provide a whole-image cryptographic integrity
check; see the [kernel format documentation](https://www.kernel.org/doc/html/latest/filesystems/squashfs.html).

## Current execution evidence (2026-09-08)

### Negative test without a bitstream

Run `boot.py --negative-no-bitstream --logs NEW_BOOT_LOG_DIR` to perform a full
power cycle, catch BDK, power the FPGA and continue boot without programming.
Mount the image using the reusable `cpu.sh mount` step above. Prepare console
logging with a normal command, then invoke the same load step used in positive
tests, supplying the expected fault messages:

```sh
python3 tools/enzian/run.py --logs /tmp/negative-prepare -- \
  bash -c 'sysctl -w kernel.panic=0; dmesg -n 8; sync'
python3 tools/enzian/run.py --logs /tmp/negative-load \
  --expect 'Internal error: synchronous external abort:' \
  --expect 'pc : probe_versions\+' \
  --script tools/enzian/cpu.sh -- load
```

The assertion intentionally avoids a compiled instruction offset. Inspect the
captured trace and the matching module's disassembly to establish the exact
read that failed. Reset after an oops before testing again.

The live test booted Linux `6.8.0-64-generic` successfully, then reported:

```text
Internal error: synchronous external abort: 0000000096000210 [#1] SMP
pc : probe_versions+0xa4/0x258 [lauberhorn]
```

Disassembly of the exact loaded module identifies offset `+0xa4` as
`ldr w21, [x1]`, the first static-shell version read at physical address
`0x97effffffff8` (`SHELL_REGS_BASE + 4 * SHELL_REGS_VERSION_ADDR`).
The trace includes `mod_init` and `Comm: insmod`. No static-shell version was
printed. This is the expected absent-register failure: the kernel emitted an
oops and killed `insmod` with SIGSEGV; no full kernel panic was observed, and the
shell remained available. Reset before further hardware testing.

Evidence is under `out/hardware-tests/2811847/negative-no-bitstream-boot/` and
`out/hardware-tests/2811847/negative-no-bitstream-console-2/console.log`.
The initial one-off negative-test script has been replaced by the shared runner
and CPU steps. A full repeat with the refactored helpers passed on 2026-09-08:
`boot.py --negative-no-bitstream` power-cycled the board and reached Linux;
`run.py --script cpu.sh -- mount ...` logged in and mounted the image;
`run.py --expect ... --script cpu.sh -- load` returned
`EXPECTED_OUTPUT_CONFIRMED`. The new trace again shows external abort
`0000000096000210`, `probe_versions+0xa4/0x258`, faulting instruction
`b9400035`, and `insmod` exiting 139. The shell survived the oops.
No helper changes were needed during this repeat. Logs are under
`out/hardware-tests/2811847/refactored-negative-{sync,boot,mount,prepare,load}/`.
ShellCheck, Python compilation and `git diff --check` also passed.

### Positive-test preparation

- Reservation confirmed: `zuestoll14`, owner `pengxu`.
- The initially powered-down machine exposed no JTAG devices. Scripted cold-start
  caught BDK and powered the FPGA; Vivado 2023.2 then reported `PROBE_SUCCESS`
  for `Digilent/210357B4B301A`, device `xcvu9p_0`.
- An intentionally failed programmer command exited nonzero without sending `n`.
- A subsequent normal `power_down()` → power-up cycle also caught BDK and
  powered the FPGA successfully, exercising both initial and repeated use.
- Job 2811847 succeeded at commit `93f4c1da6d6dab2751ec26bc6985ebf275c0e6dd`.
  The project-specific token authenticates successfully; no CI writes were made.
- Exact-commit `deployFs` initially built and transferred successfully, but offline
  inspection found the incompatible `6.8.12` vermagic. That original image has
  SHA-256 `602129ebc4fb527cd998b51fb1934bb162fc8146a2ce7519545b8609358fc6f4`
  and must not be loaded. The rebuild with the recorded kernel-release fix passed
  its vermagic check (`6.8.0-64-generic`). Corrected image SHA-256:
  `e97eac169a5af163ac9b44068f079c1030f46acd1881ea7222b78dd16f680266`.
- Existing RPC client compiled on the gateway using its system libraries (two
  rpcgen unused-variable warnings); hardware programming succeeded; RPC execution is blocked by module initialization.

### Programmed-board test (2026-09-08)

CI job 2811847 succeeded and its bitstream was programmed using the tracked Tcl
helper, Vivado 2023.2, and cable `Digilent/210357B4B301A`. `PROGRAM_SUCCESS` and
`LINUX_LOGIN_READY` were observed. The matching software closure mounted, but
`cpu.sh load` failed with exit 139: synchronous external abort `0000000096000210`
at `probe_versions+0xa4/0x258`, the same static-shell version read as the negative
test. No `lauberhorn0` or `/dev/lauberhorn` was created. A separate
`busybox devmem 0x97effffffff8 64` read also failed with SIGBUS (exit 135).

An immediate capture from
`i_eci_platform/i_eci_transport/gen_edge_ila.i_ila_eci_edge` showed both links
up and in `RUN` for all 1024 samples. This establishes link state at capture
time, not successful I/O transactions. The root cause of the register-access
failure remains unresolved; timing violations alone do not establish causation.
The CI trace reports unmet timing requirements. Its artifacts do not include
the generated timing summary report (a direct request returned HTTP 404).

The intended adder destination is `192.168.129.200` on `lauberhorn0`, with the
client on enzian-gateway routing via `cluster0.102`, source `192.168.191.254`.
No RPC was attempted because module initialization failed. Before configuring
the FPGA interface on a subsequent successful boot, resolve the overlapping
`192.168.128.0/18` route on CPU interface `cpu40g1` (`192.168.129.193/18`);
management and root storage use `cpu40g0` on the other subnet.

Logs: `out/hardware-tests/2811847/positive-{boot,mount,load,inspect,shell-read64}/`,
`ci-final.log`, and `eci-edge.csv`. Reset the CPU after this oops before retrying.

The existing Tcl helper also supports reusable immediate ILA capture without
reprogramming. Pass an exact ILA cell name and a fresh CSV path:

```sh
vivado -mode batch -nojournal -source program_fpga.tcl -tclargs \
  enzian-gateway.ethz.ch:3121 Digilent/210357B4B301A \
  --capture-ila shell_lauberhorn-eci.ltx \
  i_eci_platform/i_eci_transport/gen_edge_ila.i_ila_eci_edge eci-edge.csv
```

This capture operation was exercised with Vivado 2023.2 against the programmed
board. It uses the same exact target/device selection as programming.
