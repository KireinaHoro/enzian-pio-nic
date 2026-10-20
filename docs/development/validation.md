# Validation

Use this guide to test a Nix-built application image on an Enzian board, select
local checks, and choose an automation flow. Build images, boot bundles and
matching FPGA inputs with the [development workflow](workflow.md); application
package recipes and local overrides are documented there. Run host commands
from the repository root in the Nix development environment.

## Board validation

A successful build supplies an artifact, not board acceptance. A passing RPC
test requires externally routed correct replies through `lauberhorn0`, with
matching bitstream, generated ABI, runtime, kernel module and client protocol.
Record the image manifest and hashes alongside the board result. For captured
evidence, use the [tracing reference](tracing.md) to interpret source IDs, loss
markers and clock boundaries; successful transport alone does not validate a
workload measurement.

| Flow | Use it for | Entry point and result |
| --- | --- | --- |
| Automated board acceptance | Repeated reset → program → boot → mount/load/verify → application/client cases | [test.py orchestration](#automated-board-orchestration); compact stage output and `summary.json` |
| Interactive image testing | Inspect or run a packaged application on a prepared board using the same target environment | [lh-test and manual equivalents](#interactive-image-testing); manifest, application/client output |
| Focused boot/console diagnosis | Boot gating, programming handoff or bounded commands for a reported failure | [boot.py, run.py and cpu.sh](#focused-debugging); stage summaries and failed-stage evidence |
| Benchmark acquisition | Coordinated DUT/generator setup, workload sweeps, capture, resume and result validation | [benchmark campaigns](benchmark-campaign.md); campaign receipts and summaries, with the board prerequisites here |
| Trace preservation | Preserve a failed or completed measurement before board reuse | [capture workflow](../../tools/enzian/trace/README.md); matched capture/map and preservation result |
| Local regression | Software, RTL and bounded model checks before deployment | [local checks and simulation](#local-checks-and-simulation) |

For a Nix-built image: select matching artifacts, reserve/provision the board and
peer through EMG, then use either the automated case manifest or interactive
commands below. Both paths require the same boot gates and failure preservation.
Dandelion and other application images use this same environment; select their
packaged fixture and workload from the application's documentation rather than
assuming an image build exercises it. See [Dandelion](../../sw/apps/dandelion/README.md)
and its [evaluation scope](../research/dandelion.md).

## Check access and reserve

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

## Prepare matching artifacts

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

## Automated board orchestration

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

## Interactive image testing

Build the application image using the [development workflow](workflow.md).
After the reservation, artifact and boot checks above, use the same target
commands as the automated harness. Stage the selected Nix image:

```sh
rsync -L result enzian-gateway:/scratch/USER/deploy-UNIQUE.img
# On the CPU, as root; refuses to replace an existing mount:
bash cpu.sh mount /scratch/USER/deploy-UNIQUE.img
export LH=/nix/store/lauberhorn
export PATH="$LH/bin:$LH/sbin:$PATH"
```

Run `cpu.sh` from a staged copy of `tools/enzian/cpu.sh` on the CPU, or use
`run.py --script` from the host. The automated harness stages it itself.
`lauberhorn` links to a hashed environment in the mounted closure. Applications
never install into that stable location themselves.

| Operation | Helper | Manual equivalent |
| --- | --- | --- |
| Inspect | `lh-test info --json` | `jq . "$LH/manifest.json"` |
| Load | `lh-test load` | Check `modinfo -F vermagic "$LH/modules/lauberhorn.ko"` against `uname -r`, ensure module is absent, then `insmod "$LH/modules/lauberhorn.ko"` |
| Configure | `lh-test configure MAC CIDR` | `ip link set lauberhorn0 address MAC`; `ip link set lauberhorn0 mtu 1500`; `ip addr add CIDR dev lauberhorn0`; `ip link set lauberhorn0 up` (helper retries link-up three times) |
| Run | `lh-test run microbenchmarks -- add 4 /scratch/pengxu/run/server.csv` | `"$LH/apps/microbenchmarks/bin/microbenchmarks" add 4 /scratch/pengxu/run/server.csv` |
| Select executable | `lh-test run NAME --executable PROGRAM -- ARGS` | `"$LH/apps/NAME/bin/PROGRAM" ARGS` |

Create a fresh writable result directory before launching. For example, create
`/scratch/pengxu/run` before using the illustrative paths in the table. Start Ubuntu's
`rpcbind` service with `systemctl start rpcbind`; inspect with `rpcinfo -p localhost`.
The tools environment includes rpcinfo, IP tools, kmod, mount tools, Bash, jq and
coreutils. Ubuntu owns system services. No helper unloads modules, kills workers,
or replaces a mount. `run` preserves argument boundaries and the program's exit
status. `load` validates kernel vermagic; it does not prove FPGA ABI compatibility.
Check hardware identity with `cpu.sh verify`, then require an externally routed
correctness client. Use `LH_DIRECT=1 cpu serve ...` for the manual application path
in the same test harness. Collect server/client logs from the writable directory
with host-side rsync, along with image hashes and the manifest.


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

For a CPU-only traffic-generator peer, `boot.py --cpu-only` reuses the same
verified cold power sequence and BDK handling, skips FPGA programming, and
requires Linux login. This is not FPGA/ECI acceptance. Provision its kernel and
isolation arguments through EMG first. For the 32-core echo generator, use
CPUs 16–47 with housekeeping on 0–15; the shared DPDK runner additionally verifies
and temporarily corrects device IRQ, RPS and unbound-workqueue placement.

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

## DPDK baseline readiness

Build the [ConnectX-5 image](workflow.md#dpdk-microbenchmark-baseline-images)
and follow the board reservation/provisioning checks above. The
[DPDK baseline design](../research/dpdk-baseline.md) defines the workload and
scalar-PMD restrictions. This CPU-NIC path does not require a Lauberhorn FPGA
image or module; use CPU-only boot for a fresh baseline host.

Use the [deployment skill](../../tools/enzian/skills/enzian-deploy/SKILL.md),
`tools/enzian/run.py`, and the hash-verified private image launcher in
`tools/enzian/image.py`. Stage the image and helper through the existing transfer
service; the host interpreter only verifies and enters the image.

Inside the image, run `tools/paper/campaign/mlx5_readiness.py` with an unused
server IP, the peer's actual source IP and both explicit MAC addresses. This
maintained helper uses the canonical runner for every role and produces a fresh
output directory. It exercises 8/128/1432-byte echo, instrumented and
uninstrumented single-outstanding calls, and short aggregate loaded traffic with
one/four responders. The local NICVF generator occupies CPUs 16–19 and mlx5
responders CPUs 44–47. Shared hugepages are reserved before either role starts
and restored after both finish. This is functional readiness, not a throughput
or saturation result. Full campaigns require a separate generator machine.

Archive the helper, command manifest, image hash, per-role outputs and
restoration summaries. Import unloaded observations through
`campaign.import_unloaded.dpdk`; absent Tofino data produces `wire_ns: null`,
`wire_available: false`, `paper_inputs: false`. Import loaded observations through
the ordinary aggregate validator; validate actual worker activity and full
payload replies. Paper CDF/breakdown validation rejects missing wire durations.
Full collection uses the [campaign wire-acceptance gate](benchmark-campaign.md#canonical-measurement-contract).
Caladan directpath has its [own acceptance plan](../research/caladan-enzian.md).

The helper CLI, run inside the staged image, is:

```sh
python3 tools/paper/campaign/mlx5_readiness.py --output NEW_READINESS_RUN \
  --server-ip UNUSED_SERVER_IP --client-ip ACTUAL_CLIENT_IP \
  --server-mac ACTUAL_MLX5_MAC --client-mac ACTUAL_NICVF_MAC
```

The current helper fixes the server interface to `enP5p144s0np0` and local peer
to `cpu40g1`; verify those names and their connectivity before running it.

## Runtime PMU profiling

The runtime image includes `lh-pmu-probe`. Read the
[PMU boundaries and instruction-counter blocker](../research/hot-path-performance.md#pmu-measurement-boundaries)
before interpreting counts. Independent instruction validation failed on the
October 5 setup; do not publish instruction counts or IPC from those cohorts.

After checking reservations and staging the cross-built, matched deployment:

1. Enable `/proc/sys/kernel/perf_user_access` as root for the experiment. Record
   its previous value. Do not disable access while a profiling worker is still
   executing counter reads; restore it after that process has stopped or rebooted.
2. Run `taskset -c 0 lh-pmu-probe` before launching workers. It checks a known
   instruction loop, 100,000 adjacent snapshot pairs, and small/large memory
   footprints, including comparison against kernel perf `read()` counts.
   Exit 2 means instruction validation failed while the cache-response check
   passed; exit 1 means another validation failed. This is not an RPC failure.
3. For a buffered traced service, set `LAUBERHORN_PMU=1` and the existing
   `LAUBERHORN_SERVER_TRACE_CSV`/application CSV path. Initialization happens
   before worker registration. Unavailable or unscheduled counters fail closed.
4. `LAUBERHORN_PMU_SKIP_ROWS=N` skips snapshots for the first N completed rows
   of each thread. `LAUBERHORN_PMU_WHOLE_ROWS=M` then takes only RX-entry,
   RX-return and TX-return snapshots for M rows; subsequent rows have detailed
   snapshots. Counts are per thread, include warmups, and do not count unsuccessful
   RX polls. Set both to zero for detailed profiling throughout.
5. Stop traffic, then export once with SIGUSR2. Never dump while measuring.
   Retain CSV, status, calibration, client timings and software/FPGA provenance.

`tools/enzian/pmu_breakdown.py DIRECTORY` validates three consecutive cohorts
(`off.csv`, `coarse.csv`, `detailed.csv`, plus `server/server.csv` and its status).
Defaults are 1,000 measured requests and 16 warmups per cohort. It checks IDs,
CPU/worker identity, success, snapshot masks and additive conservation. Negative
counts are errors. Only after independent instruction validation fails may
`--exclude-instructions` withhold that entire event while retaining every request
for cycles/cache statistics. It never clamps negatives or silently drops samples.

## Local checks and simulation

Select the smallest relevant check for the changed component. These checks
complement board acceptance; they do not establish physical ECI behavior.

### Select the smallest relevant check

```sh
# All untagged/fast Lauberhorn tests (broader than CI).
nix develop -c mill gen.test -l org.scalatest.tags.Slow

# ECI package portion of the fast CI check, excluding Slow.
nix develop -c mill gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci

# One suite / one test.
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim -- -t rt-timestamped

# Full suite, including Slow; intentionally broader and potentially expensive.
nix develop -c mill gen.test

# Dependency regression when touching/updating blocks.
nix develop -c mill 'blocks[2.13.12].test'
```

The `fast-tests-eci` CI check also runs `RecoveryStreamBufferSim`,
`RecoveryInFlightSim`, `ExecutionWatchdogSim` and `RpcReplyContextsSim` in the
`lauberhorn` package. See the [check definitions](../../nix/checks/default.nix)
for the complete selection.

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

This loops until failure and exits nonzero, retaining the failing workspace.
Set `REPEAT_TEST_LIMIT=100` for a bounded campaign. Compiled Verilator models
are cached outside Mill's disposable test sandbox in
`out/repeat-test-verilator-cache` (`LAUBERHORN_VERILATOR_CACHE` overrides it);
each repetition still starts a fresh test JVM. Mill module-level test
parallelism being disabled does not serialize `ParallelTestExecution` suites.
Simulations can overlap. Start with one reproducer, then verify
that selecting sibling tests does not alter its stimulus.

To request eight ScalaTest suite runners:

```sh
nix develop -c mill gen.test -- -P8
```

Mill module-level test parallelism remains disabled to avoid repeated Verilator
model builds. Read compressed simulation transcripts with `less`, `vim` or
`zcat`; use the workspace path printed by the suite.

### AXI decode-error regression

When changing AXI crossbar decode or completion accounting, run:

```sh
nix build .#checks.x86_64-linux.axi-decerr
```

The [read/write regression](../../deps/blocks/deps/verilog-axi/tb/axi_crossbar/test_decerr.sv)
completes an unmapped transaction through the internal DECERR responder, then
requires a mapped transaction to progress. Internal error responses must not
release a transaction count that address admission never acquired. This is a
crossbar simulation check; it does not validate kernel worker parking or establish
what response occurred in a historical board capture.

## Evidence to retain from a failure

- Simulation config uses Verilator, FST waves, and a `verilator-cache` path; emitted paths depend on the Mill test working directory. Use the printed workspace path instead of assuming a fixed root-level `simWorkspace`.
- The shared `DutSimFunSuite` writes `sim_transcript.log.gz` and prints a reproducer including `-DsetupSeed=... -DsimSeed=... -DprintSimLog=true`. Preserve `simulation-seeds.txt`, `simulation-runtime.txt`, `simulation-inputs.sha256`, test name, revision/submodule revisions, Nix lock and generated config. Follow the [seed and stimulus contract](#simulation-reproducibility) when replaying.
- `tools/ci/nix-build.sh` follows the failed directory reported by Nix rather than assuming `/tmp/nix-build-*`. This retains evidence with both old and `/nix/var/nix/builds/nix-*` layouts.
- ECI tests using `testWithDB` emit `lauberhorn_trace.pcapng` from a `finally` block. This supports causal event inspection even on failure; load the repository Lua dissector as described in the [trace tools](../../data/eci/sys_trace/README.md).
- PCAP replay tests load `data/eci/iladata/rx-lockup{,-2}.pcap` and use libpcap through pcap4j. Nix configures `LD_LIBRARY_PATH`; missing native libpcap can fail before an RTL assertion.
- For simulator changes in dependencies, inspect/update the submodule rather than copying its implementation into the platform. Blocks regression gates every pipeline; see the [CI checks](ci.md#pipeline-and-handoff).

## Coverage limits that affect claims

- `rx-tx-interleaved` exercises independent RX/TX control colors in the regular suite. RX/TX size scans include `Slow` tests; check the selected test tags when reporting coverage.
- `PacketAllocSim` explicitly leaves multi-configuration and block-until-free checks incomplete.
- The Scala host model approximates software IRQ/scheduling behavior. Passing RTL simulation does not validate the actual Linux module, aarch64 atomics, or real ECI cache behavior.
- `model/legacy/tla/lauberhorn.tla` and Toolbox models describe isolation/preemption; the checked-in small model config names type, cacheline-exclusion, preemption and isolation invariants plus temporal properties. Generated model outputs are historical artifacts, not proof that current RTL/software satisfies the model. The separate steady-state model now has a Nix check; it does not validate this older scheduling model. Reconcile source and model snapshots before using results in a paper.
- Existing `data/` captures and plots are debugging/measurement evidence with their own provenance. They are not a current benchmark result merely because they are checked in.

After a behavioral change, run the relevant suite and elaborate the affected hardware; require real target validation for scheduler/ECI deployment claims. For docs-only changes, link/path and command-definition checks suffice.

Evidence: [test build graph](../../build.mill), [simulation config](../../hw/src/lauberhorn/Config.scala), [shared fixture](../../deps/blocks/tester/src/jsteward/blocks/DutSimFunSuite.scala), [ECI fixture](../../hw/test/src/lauberhorn/host/eci/NicSim.scala), [trace fixture](../../hw/test/src/lauberhorn/sim/DbFactory.scala), [RPC tests](../../hw/test/src/lauberhorn/host/eci/OncRpcSim.scala), [model](../../model/legacy/tla/lauberhorn.tla), [small model config](../../model/config/legacy/lauberhorn_model_isolation/MC.cfg).

For routed checkpoint analysis and source-level timing closure, use the
[physical implementation guide](../hardware/physical-implementation.md).

## Simulation reproducibility

The shared fixture records seed format 2 in `simulation-seeds.txt` and passes
`simSeed` directly to Spinal. Precedence is explicit `-DsimSeed`, then
`SPINAL_SIM_SEED`, then a deterministic derivation from the setup seed and test
name. Preserve both seeds and the seed-format version. Format 1 used a different
contract and requires its original fixture; its numbers are not sequence-compatible
with format 2. The native backend normalizes seed zero to one.

Keep stimulus on the per-simulation `simRandom` stream or an explicitly seeded
local stream. Fixture configuration and simulation mutexes must belong to the
test invocation/model instance. ScalaTest `ParallelTestExecution` can overlap
simulations even when Mill's module-level parallelism is disabled. Replaying
alone and among sibling tests should not change stimulus.

A seed is only part of the reproducer: retain source/submodule revisions, Nix
lock, generated RTL/input hashes and runtime/toolchain metadata. Hash iteration
and library implementation details are not cross-version contracts. Audit
randomized branches when enabling additional simulation helpers; the ECI
fixture's isolation does not imply library-wide determinism. In particular,
Spinal's separate `AxiMemorySim` and Wishbone helpers have global-RNG paths
outside this fixture's seeded stimulus contract.

Wait for the full host-model operation before reusing its QP/cache line or
ending a test. Network packet completion alone does not mean `txSendSingle`
has finished its control reads and voluntary reload checks. Similarly, setup
that depends on reset completion must wait for reset-qualified sampling cycles,
not merely count clock edges while reset is asserted. Preserve concurrent RX/TX
and eviction coverage rather than masking a test-ownership error by disabling
stimulus or weakening assertions.

### Settled progress checks

See the [verification contract and reproducible commands](../hardware/steady-state-verification.md).
`OncRpcSim` tests `rx-independent-progress-{1,2,3}-stalls` exercise finite
independent-worker and simultaneous bypass RX/TX progress while peers stall.
They do not prove infinite-stall liveness or establish silicon timeout bounds.
Python capacity tests and the independent TLC runner live under `tools/progress/`.

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

These historical models cover handoff, pending wake ownership and interrupt
ordering, including scenarios outside the current implementation. They do not
test the kernel scheduler or instantiate DCS RTL. The report defines the
optional stalled-third-worker bound. For the implemented IRQ-free protocol, use
the [cooperative scheduling checks](#cooperative-scheduling-and-slice-routing).

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
[directory-pressure report](../hardware/lauberhorn-rom.md#directory-pressure-and-the-nonlocking-contract) for scope and the
distinction between a passing diagnostic and a reproduced design progress failure.

The `dcs-pressure` target also runs the reset-to-full auto-lock sequence through
actual TSU/tag RAM and its reset wrapper, using a test XPM RAM primitive model.
`directory-summary.json` and `directory-events.log` record naturally reached
full-set absent-LCI stalls, successful hit invalidation, and UL-enabled recovery.
These checks establish directory-component reachability, not the complete
Lauberhorn/THX execution. These auto-lock checks retain their historical protocol scope. The selected
Lauberhorn ROM has separate checks described below; passing the old-controller
diagnostic does not validate that selected protocol.

### Generated Lauberhorn DCS ROM

See [the selected ROM contract and reproducible checks](../hardware/lauberhorn-rom.md).
`dcs-rom-thx` checks extracted-THX compatibility; `dcs-lauberhorn` runs actual DCU
full-set/LCI tests using `dcsRom`. Both gate the Vivado input bundle. They do not
replace whole-slice routing tests or hardware validation.

## TLA+ source and module checks

Start with [formal verification topics](../formal/README.md), the
[model source index](../../model/README.md) and
[module review](../hardware/model-review.md). The active model is LCI-only;
source, configuration, address data and historical evidence are separate.
`nix build .#checks.x86_64-linux.tla-modules` checks the inventory/import boundaries
and SANY semantics. `scheduling-transport` checks focused transport/handoff cases;
`steady-progress`, `scheduling-races` and `scheduling-kill-exit` retain their
separate scopes. The historical wake/close composition targets below include
behavior outside the implemented cooperative protocol; their bounded results
do not establish full-system progress.

The [AXI/coherence response suite](../formal/axi-response-contract.md) runs with
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

The RX writeback regression connects the generated RX AXI router to the DCU
and `axi_wr_cl` using the selected ROM. Run from the
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

The regression requires the production RX sink to accept both write beats,
return B and release WDDA, LCIA and the next RX control read. Controls check a
no-data HAKD and an independent test sink which deliberately withholds B: the
latter must block completion until B is released. The test uses synthetic
returned data, manual initial DC read completion, test XPM RAM and direct
LCIA-to-`invDone` wiring. It excludes the full slice data buffer/crossbar, THX
and higher-level RX protocol. The [return-half design note](../hardware/rx-writeback.md)
describes the current payload replay shadow and proposed response-channel uses.

The independent-channel test also runs in the fast ECI CI suite:

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.RxWritebackSim
```

It checks W-before-AW, delayed W, queued IDs, zero/partial strobes, stable B under
backpressure and quiescence. See the [writeback and permission-change contract](../hardware/eci-implementation.md#rx-writeback-and-permission-changes).

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
write completion and backpressured LCIA. It also withholds three worker read
responses plus bypass RX while an independent worker and bypass TX complete
three rounds, then returns the held responses in a different order and checks
final writeback/LCIA accounting. The XPM RAM and passive trace outputs
are test models; the payload datapath and ThunderX are not instantiated. The
runner removes one unmatched `%d` from an upstream unsupported-size diagnostic
in a temporary compile copy; it does not alter protocol logic or the dependency.
Source hashes and that normalization are recorded in `slice-summary.json`.

To generate and cross-build all seven supported worker counts from a committed
checkout (the outputs are interface-check artifacts, not deployment images):

```sh
nix develop -c mill gen.test.runMain lauberhorn.host.eci.GenWorkerConfigurations "$PWD/out/worker-configs"
nix build -L --impure --file nix/hardware/worker-configurations.nix \
  --argstr revision "$(git rev-parse HEAD)" --arg generatedRoot "$PWD/out/worker-configs" \
  kmod1 runtime1 kmod2 runtime2 kmod3 runtime3 kmod4 runtime4 \
  kmod5 runtime5 kmod6 runtime6 kmod7 runtime7 --no-link --json
```

The helper resets the descriptor-emission guard between elaborations because
the production CLI normally elaborates only once per JVM. Do not hand-edit
headers to make a configuration compile.

### Payload isolation across cooperative ownership changes

Run the focused router and full-engine checks when changing payload isolation
or cooperative ownership:

```sh
nix develop -c mill gen.test.testOnly \
  lauberhorn.host.eci.TxPayloadIsolationSim \
  lauberhorn.host.eci.RxPayloadIsolationSim \
  lauberhorn.host.eci.CooperativeYieldSim
```

The direct TX fixture poisons backing storage and checks scrub completion under
B backpressure, partial strobes, response stability, repeated clearing and the
last mapped overflow line against an adjacent-allocation sentinel. The RX
fixture poisons padding, then checks 65-byte and one-byte packets in a 128-byte
allocation, including reads beyond the packet. The full-engine test checks
A→B→A reuse, every TX alias, RX tails and DMA transmission of unwritten successor
bytes through the neighbor-miss path. The coherent host model alone does not
exercise partial AXI write strobes; keep the direct fixture too.

These tests belong to the normal fast ECI suite. See the [isolation correction
and board-acceptance boundary](../hardware/payload-isolation-blocker.md#source-correction--october-3-follow-up).

## Hot echo RPC generator and campaign

See the [campaign contract and current acceptance status](benchmark-campaign.md).
The native traffic-generator package checks schema/raw-wire compatibility,
zero-length bodies, MTU boundaries and absolute schedule/deadline rules.
`open_loop_replay_test.py` exercises complete loss and an induced generator
stall through the real DPDK pcap PMD. Campaign fixtures cover rate-prefix
selection, overload extensions, sustained fallback, resume integrity and
notification retries. These local checks do not establish hardware acceptance.
