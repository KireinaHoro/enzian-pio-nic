# Test on real Enzian hardware

Run from the repository root. Use [test.py](../../tools/enzian/test.py) for the
shared [interactive commands](interactive-testing.md) and the whole reset → program → Linux → module → gateway RPC test. **A passing test
requires correct RPC replies through `lauberhorn0`, not just successful boot or
`insmod`.** Keep bitstream, software closure and client protocol matched.

## 1. Check access and reserve

Use `emg` exclusively for Enzian provisioning, including reservations, boot-image
and kernel selection, kernel arguments, and root-volume setup. Never edit PXE/GRUB
configuration or iSCSI/TGT exports directly, or manage their backing volumes by
hand. This applies to both DUTs and traffic-generator/capture peers. If the needed
operation is unclear, inspect the supported `emg` workflow rather than bypassing
it. Continue to use the repository harness for BMC power sequencing, FPGA
programming, boot validation and tests.

Requires local Python with `pexpect`, Nix, and SSH access to `enzian-gateway` and
`enzian-ba2`. Check `ssh enzian-gateway 'emg list-machines'` every session; never
assume the previous reservation persists. For an unreserved zuestoll14, build and rsync `bootFiles` using the
[boot bundle workflow](workflow.md#thunderx-cpu-nic-timestamps), then run on
the gateway:

```sh
emg acquire zuestoll14 -n lauberhorn-linux-6.8 \
  -a 'isolcpus=nohz,domain,managed_irq,44-47 nohz_full=44-47 rcu_nocbs=44-47 irqaffinity=0-43 kthread_cpus=0-43 rcu_nocb_poll copymods=force' \
  -k "$USER/lauberhorn-6.8.0-146-ptp/vmlinuz" \
  -i "$USER/lauberhorn-6.8.0-146-ptp/initrd.img" \
  -g 2025-07-28
```

Named volumes persist after release, but their iSCSI export is shared by
**user + volume name**, not by reservation. Do not reuse an active machine's
named root volume for another reservation. The current gateway backend removes
the common TGT configuration and attempts target deletion on release, without
checking for another reservation using it. Distinct PXE initiator names do not
create distinct targets. Prefer generated volumes for fresh clients; an accidental
shared reservation needs admin cleanup that preserves the active export.

Match the kernel release in the selected boot bundle and deployment manifest.
The documented boot bundle uses `6.8.0-146-generic`; older artifacts can require
`6.8.0-64-generic`. Never combine an old module with a different boot kernel.
CPU credentials `enzian/enzian`
(`ENZIAN_PASSWORD` overrides the console password); BMC root password `0penBmc` (`ENZIAN_BMC_PASSWORD` supplies it noninteractively).
The BMC helper prompts if login is needed and that environment variable is absent.
Supply it for unattended boot: the remote login prompt expires after 60 seconds. CPU console is `zuestoll14-console`,
BMC console is `zuestoll14-bmc`; detach with Ctrl-E, c, period. Do not force
another console user off. See the [quickstart](https://gitlab.inf.ethz.ch/project-openenzian/documentation/userguide/-/jobs/artifacts/main/raw/enzian_quickstart.pdf?job=build)
and [machine/JTAG inventory](https://enzian.systems/generated/cluster-info.html).

## BMC recovery and powered-off starts

The discoverable [enzian-deploy skill](../../tools/enzian/skills/enzian-deploy/SKILL.md)
is the agent workflow entry point. In `enzian-shell bringup`, inspect
`print_voltage_all()` before choosing the power sequence. An uninitialized-device
error suggesting `init_driver()` can mean CPU rails are off: do not call that
function as a repair. Use `common_power_up()` followed by `cpu_power_up()` through
the boot helper, which catches BDK before FPGA programming. Missing voltage
readings are not numeric proof of off rails; the helper verifies actual core
voltages after restoring common monitor power.

For this powered-off starting state, pass `--cold-start-first` to `test.py`.
It forwards `--cold-start` only to the first boot: skip the redundant
`power_down()`, restore common power, require CPU/FPGA core rails below 0.2 V,
then continue normal bring-up. Subsequent cases and bounded ECI retries use a full
cold reset. Never use this option to bypass a failed rail check.

A D-Bus `NoReply` timeout is different from a normal API/state error. Preserve
the failing log, reboot the DUT BMC, verify its return, inspect voltages, then retry
the appropriate sequence. If a timeout recurs after one reboot and bounded
retry, stop and report evidence. Do not steal an occupied console or replace
EMG provisioning with manual export/boot configuration changes.

## 2. Prepare matching artifacts once

Select a successful hardware job and its matching software preparation output
from the same clean revision. Follow the
[CI artifact contract](ci.md#pipeline-and-handoff) and the
[scheduled hardware follow-up](ci.md#scheduled-hardware-build-follow-up) when a
new build is authorized. Record the full revision, job IDs, bitstream/LTX/trace
map hashes, deployment-image manifest and client protocol together. Verify the
module's kernel release against the provisioned boot bundle; never force-load
an incompatible module. Hardware and functional acceptance do not imply timing
closure.

The project token at `../.gitlab_token` is for read-only CI queries and artifact
downloads; keep it local. For an already completed job:

```sh
python3 tools/enzian/ci_artifacts.py HARDWARE_JOB_ID --token-file ../.gitlab_token \
  --download /tmp/hw-UNIQUE_RUN
```

Fetch the corresponding deployment image from the preparation job using the
[artifact paths](ci.md#pipeline-and-handoff). For a local build, use the
[hardware build workflow](hardware-build.md) and matching software image from
the same clean revision. Stage programmer inputs on the Docker/Vivado host and
the deployment image on CPU-visible shared storage; record their exact paths
in the case manifest. `/scratch/USER` is shared between gateway and CPU.

Build the matching client through the maintained Nix package/image workflow;
see the [traffic generator](benchmark-campaign.md#dpdk-traffic-generator).
Historical packaging overlays require their original checkout, runner and ABI
comparison. A patch-builder allowlist is not evidence of ABI compatibility.
The old 2812450 walkthrough is retained in Git at
`a7970b8b:docs/development/hardware-test.md`; it is not the current acceptance
baseline.

Recheck tool versions: the tested gateway hardware server is 2023.2 on port
3121, matching `/opt/Xilinx/Vivado/2023.2/bin/vivado` on ba2. For offline 2025.1
checkpoint analysis, ba2 has `/opt/Xilinx/2025.1/Vivado/bin/vivado`.

## 3. Run and read the summary

Copy [adder.example.json](../../tools/enzian/adder.example.json) to your case
file. This is a historical 2812450 template: replace its revision, image,
programmer/artifact paths and client command with the matched selection above.
Replace `UNIQUE_RUN` with a fresh experiment label (also used for remote logs). Inspect current CPU routes before using its `cpu40g1 down`
command: this removes the overlapping FPGA-subnet route; management/root storage
must remain on `cpu40g0`. The example is specific to zuestoll14: JTAG
`Digilent/210357B4B301A`, MAC `0c:53:31:03:01:c8`, IP `192.168.129.200/18`.

```sh
cp tools/enzian/adder.example.json /tmp/cases.json
# Edit the unique case name and review the machine-specific settings.
python3 tools/enzian/test.py /tmp/cases.json --logs /tmp/test-UNIQUE_RUN --repeats 2
cat /tmp/test-UNIQUE_RUN/summary.json
```

`cpu load` disables Linux RT bandwidth throttling by setting
`kernel.sched_rt_runtime_us=-1` and checking readback before loading the module.
This prevents the default 950 ms-per-second budget from pausing the SCHED_FIFO
workers for roughly 50 ms. The setting applies to the DUT for the current boot;
it is reapplied by the routine after every cold reset and recorded in
`summary.json` as `rt_bandwidth`. It does not change the matched deployment image
or ordinary scheduler/worker synchronization locks.

The manifest is a list of cases: `name`, full `revision`, CPU-visible `image`,
programmer argv `program`, post-verification shell commands `cpu`, and
shell command `client` (gateway by default). Set `client_machine` (for example `zuestoll12`) to
execute that command as root through the reserved peer CPU console instead of
on the gateway. Peer logs are retained in `client-cpu/console.log`. The `cpu` function exposes all `cpu.sh` steps. `{run}`
expands to round/case name in CPU/client commands. Cases alternate each round;
the gateway client must exit nonzero on incorrect replies. The example checks
100 add calls per trial, including arithmetic and echoed request IDs.

Launch once, wait for compact stage/results output, then read `summary.json`.
For standalone `boot.py`, read its compact output and `--logs/summary.json`.
**Agents must not read raw logs unless a reported error needs diagnosis.**
Do not open, tail, or search console, BMC, programmer, or CPU transcripts to
monitor normal progress, recheck boot markers, or confirm a successful run.
The expect-based flow validates QLM/ECI initialization and handles retries;
use its structured results. A recovered retry needs no raw-log inspection
unless its underlying error is being investigated. For diagnosis, start with
the summary's error and read only a bounded excerpt of the relevant failed
stage, expanding only if needed. Automated parsing and retaining logs on disk
continue normally; avoid loading routine transcripts into the conversation
to save tokens. Do not issue one tool call per setup step.
Full logs, manifest and executed CPU script
are retained; client CSVs are at the manifest's shared paths. Reset failures
stop the campaign as infrastructure failures. CPU or client failures also stop
the entire campaign before another reset, preserving hardware state for capture. Timeouts
interrupt/reap local child processes. The final successful server stays running.

Each normal boot shuts down CPU/FPGA rails and main PSU, waits five seconds,
restores common power to access monitors, and requires CPU/FPGA core readings
≤0.2 V **before** enabling either core. It then catches BDK automatically,
powers/programs the FPGA, and releases boot only after programming succeeds.
The BMC remains powered. Use `boot.py --cold-start --hold-only` only for recovery
from a confirmed already-off board; normal comparison trials require full resets.

After releasing BDK, `boot.py` requires CDR lock on QLM8..13 and the complete
`N0.CCPI Lanes([] is good):[0]...[23]` report before accepting Linux boot.
Missing or incomplete initialization triggers another full verified cold reset
and reprogramming, up to ten attempts (`--boot-attempts` overrides the limit).
The first attempt keeps the usual log paths; retries use `retry-02/`, etc., and
`boot/summary.json` records each outcome. Exhaustion is an infrastructure
failure; do not load the driver anyway. The explicit `--negative-no-bitstream`
test bypasses this gate because absent ECI initialization is intentional.

If `probe_versions` still fails after validated bring-up, freeze and dump trace
DDR using the [CMAC-first capture procedure](tracing.md#default-agent-capture-pipeline)
before resetting. If Ethernet capture fails, retain partial output and stop
without reset or a full JTAG fallback. Retain partial captures
and the matching map, VIO status, and image
provenance. Keep tracing active through module load. The shell-version read is
handled inside the static shell, so arm the static ECI edge ILA before the
attempt when possible; the application DDR trace alone may not show that read.

## Focused debugging

- [run.py](../../tools/enzian/run.py): run a command or local shell script over
  the CPU console; `--expect REGEX` asserts ordered output instead of exit zero.
  Script transfer uses short base64 lines and waits for the shell's continuation
  prompt after each line; fixed pacing alone lost input during kernel logging.
- [cpu.sh](../../tools/enzian/cpu.sh): independent `mount IMAGE`, `load`, `verify`,
  `configure MAC CIDR`, `serve add/mul WORKERS NEW_LOG_DIR` steps.
- [boot.py](../../tools/enzian/boot.py): `--hold-only`, `--resume-held` with a
  programmer command, or `--negative-no-bitstream` for a deliberate absent-HW test.
- [program_fpga.tcl](../../vivado/eci/program_fpga.tcl): exact-target programming,
  `--probe`, or `--capture-ila LTX EXACT_CELL OUTPUT.csv` instead of the bitstream.

For a negative test, boot with `--negative-no-bitstream`, mount using `cpu.sh`,
then run `cpu.sh load` through `run.py` with expectations
`'Internal error: synchronous external abort:'` and `'pc : probe_versions\+'`.
The observed failure was an oops at shell version register `0x97effffffff8`, not
a full kernel panic. Preserve the failure before any reset. ECI links being up does not establish
working register accesses or RPCs. See [recorded results](../../agents/hardware-state.md#early-boot-and-artifact-evidence)
for known failures and repeatability limits; historical results are not a new test.

## Use a second Enzian as the traffic generator

Acquire an available machine with `emg acquire MACHINE`; `-g 2025-07-28`
selects the newer golden image if needed. Do not copy the DUT's isolated-core
kernel arguments to the client machine. Verify its reservation, boot Linux,
and check `ip route get DUT_FPGA_IP`: `cpu40g1` is in the FPGA NIC VLAN.
Use `cpu40g1` for traffic/capture on the client and `lauberhorn0` on the DUT;
keep management/root storage on `cpu40g0`.

Build `.#trafficGeneratorImage` on the build host and deploy that runtime-only
image to the client. Cross-build all client binaries and tools; do not ship
compilers, build on Enzian, or rely on Ubuntu userspace packages for measurement.
Keep host kernel/modules provisioned through `emg` and record their versions.

For the reusable polling client and VF restoration runner, see the
[DPDK generator](../../../microbenchmarks/client/README.md) and
[measurement contract](../research/latency-measurement.md). The [benchmark guide](benchmark-campaign.md#dpdk-traffic-generator) explains
the generator, kernel/PMD requirements and calibration limits. Its pcap replay
checks are functional tests; require physical packet validation before collecting
network latency data.

The controlled [worker-issued GSYNC reproduction](../hardware/gsync-worker-reproduction.md)
uses a separate instrumented RPC probe to test whether worker-local permission
changes wait behind peer RX holds. Its socket client measures reproduction
outcomes; it is not a replacement for the DPDK latency benchmark.

The client does not need a programmed FPGA or the Lauberhorn kernel module.
`boot.py --machine MACHINE --negative-no-bitstream` can boot it without
programming. This mode's log label describes the absent FPGA image; no negative
module-load test is implied. For an uninitialized-device error during power
sequencing, follow [BMC recovery](#bmc-recovery-and-powered-off-starts); inspect
voltages and use the helper's powered-off sequence rather than calling
`init_driver()` as a repair. Do not treat `is_up` in the
reservation listing as proof of Linux readiness.

For a cross-built AArch64 benchmark client, use `trafficGeneratorImage`.
Keep every test's client machine, software and capture paths in its manifest.
Never count peer boot/setup failures as runtime test failures.

## Historical compatibility

Current application tests use maintained application images and the
[Dandelion workflow](../research/dandelion.md). Retired student packaging and
B4 recovery runners require their original checkout and ABI; they must not be
combined with cooperative DESCHED images. Historical provenance is in the
[imported-runtime](../../agents/runtime-applications.md#historical-imported-runtime-probes)
and [B4 recovery](../../agents/runtime-applications.md#historical-b4-parking-and-recovery)
records. Current unsupported exit/fatal faults fence the domain; complete
resource recovery is a separate contract in the
[scheduling interface](../software/scheduling-control-interface.md).

## Serial command integrity

The console runner paces input in small UART bursts, acknowledges shell input
lines and verifies the complete decoded script with SHA-256 before execution.
Its interactive-shell tests cover long payloads, literal shell characters, exit
status, noninteractive stdin and corrupted-payload rejection. Interrupted input
framing cancels only the unexecuted compound command; a running board command is
not automatically interrupted or retried. Preserve the failed console log and
check whether a requested setting was actually programmed before resuming.

## ECI receive-stall calibration

Use [RX stall-timeout calibration](../hardware/eci-implementation.md#calibrating-the-rx-stall-timeout-before-nack)
to compare the NIC's NACK timer with observed CPU/ECI timeout behavior. It covers
parameter readback, TAD/kernel checks, contention, trace analysis and the limits
of finite measurements. Use the normal preservation and boot gates above.

For a CPU-only traffic-generator peer, `boot.py --cpu-only` reuses the same
verified cold power sequence and BDK handling, skips FPGA programming, and
requires Linux login. This is not FPGA/ECI acceptance. Provision its kernel and
isolation arguments through EMG first. For the 32-core echo generator, use
CPUs 16–47 with housekeeping on 0–15; the shared DPDK runner additionally verifies
and temporarily corrects device IRQ, RPS and unbound-workqueue placement.
