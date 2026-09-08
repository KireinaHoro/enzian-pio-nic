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
Cold-start, normal power-cycle/hold and failure handling have been exercised; programming and Linux
continuation are pending CI artifacts.

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

[linux-smoke.sh](../../tools/enzian/linux-smoke.sh) runs on the CPU as root and
accepts image path, SHA-256, full revision, MAC, IP/prefix and a fresh log
directory. It checks the kernel ABI and isolated CPUs, mounts the image,
loads the module, compares the reported hardware version, configures the NIC
and starts the add server. It deliberately reports only `SERVER_STARTED`;
the separate client must still establish end-to-end correctness. Inspect the
CPU's current routes before using the existing user-script addressing
`0c:53:31:03:01:c8`, `192.168.129.200/18`; do not copy another machine's address.
This Linux script has passed syntax checking but awaits execution on the CPU.

## Current execution evidence (2026-09-08)

- Reservation confirmed: `zuestoll14`, owner `pengxu`.
- The initially powered-down machine exposed no JTAG devices. Scripted cold-start
  caught BDK and powered the FPGA; Vivado 2023.2 then reported `PROBE_SUCCESS`
  for `Digilent/210357B4B301A`, device `xcvu9p_0`.
- An intentionally failed programmer command exited nonzero without sending `n`.
- A subsequent normal `power_down()` → power-up cycle also caught BDK and
  powered the FPGA successfully, exercising both initial and repeated use.
- Job 2811847 is running at commit `93f4c1da6d6dab2751ec26bc6985ebf275c0e6dd`.
  The project-specific token authenticates successfully; no CI writes were made.
- Exact-commit `deployFs` initially built and transferred successfully, but offline
  inspection found the incompatible `6.8.12` vermagic. That original image has
  SHA-256 `602129ebc4fb527cd998b51fb1934bb162fc8146a2ce7519545b8609358fc6f4`
  and must not be loaded. The rebuild with the recorded kernel-release fix passed
  its vermagic check (`6.8.0-64-generic`). Corrected image SHA-256:
  `e97eac169a5af163ac9b44068f079c1030f46acd1881ea7222b78dd16f680266`.
- Existing RPC client compiled on the gateway using its system libraries (two
  rpcgen unused-variable warnings); hardware programming and RPC execution pending.
