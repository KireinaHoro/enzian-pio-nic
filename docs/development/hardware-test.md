# Test on real Enzian hardware

Run from the repository root. Use [test.py](../../tools/enzian/test.py) for the
shared [interactive commands](interactive-testing.md) and the whole reset → program → Linux → module → gateway RPC test. **A passing test
requires correct RPC replies through `lauberhorn0`, not just successful boot or
`insmod`.** Keep bitstream, software closure and client protocol matched.

## 1. Check access and reserve

Requires local Python with `pexpect`, Nix, and SSH access to `enzian-gateway` and
`enzian-ba2`. Check `ssh enzian-gateway 'emg list-machines'` every session; never
assume the previous reservation persists. For an unreserved zuestoll14, run on
the gateway:

```sh
emg acquire zuestoll14 -n lauberhorn-linux-6.8 \
  -a 'isolcpus=nohz,domain,managed_irq,44-47 nohz_full=44-47 rcu_nocbs=44-47 irqaffinity=0-43 kthread_cpus=0-43 rcu_nocb_poll' \
  -g 2025-07-28
```

Tested setup: kernel `6.8.0-64-generic`; CPU credentials `enzian/enzian`
(`ENZIAN_PASSWORD` overrides the console password); BMC root password `0penBmc` (`ENZIAN_BMC_PASSWORD` supplies it noninteractively).
The BMC helper prompts if login is needed and that environment variable is absent.
Supply it for unattended boot: the remote login prompt expires after 60 seconds. CPU console is `zuestoll14-console`,
BMC console is `zuestoll14-bmc`; detach with Ctrl-E, c, period. Do not force
another console user off. See the [quickstart](https://gitlab.inf.ethz.ch/project-openenzian/documentation/userguide/-/jobs/artifacts/main/raw/enzian_quickstart.pdf?job=build)
and [machine/JTAG inventory](https://enzian.systems/generated/cluster-info.html).

## 2. Prepare matching artifacts once

Known working reference: job **2812450**, commit
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`. For another build, update the job,
revision and paths together. When launching an authorized hardware pipeline, use
the [30-minute gate check followed by five-hour collection/test timer](ci.md#scheduled-hardware-build-follow-up).
Do not start additional CI polling outside that workflow unless requested.
The project token at `../.gitlab_token` is for **read-only CI queries and HW
artifact downloads only**; do not copy it to remote hosts.

```sh
python3 tools/enzian/ci_artifacts.py 2812450 --token-file ../.gitlab_token \
  --download /tmp/hw-2812450
# This older commit needs only the already-tested kernel-release packaging fix.
git show 7c9fb46 --format= -- flake.nix > /tmp/kernel-release.patch
python3 tools/enzian/build_deploy.py aa32b1af04f2fcdd9191c94784eba5bbad1ea25f \
  --packaging-patch /tmp/kernel-release.patch --out-link /tmp/deploy-2812450
rsync -rlt /tmp/hw-2812450/ vivado/eci/program_fpga.tcl \
  enzian-ba2:/tmp/lauberhorn-2812450/
rsync -L /tmp/deploy-2812450 enzian-gateway:/scratch/pengxu/deploy-2812450.img
```

For commits already containing the kernel-release fix, omit `--packaging-patch`.
The historical example above describes the old image layout: use the runner from
that revision for an unchanged historical image. Current runners require the
shared `lauberhorn` environment and JSON manifest. The patch builder now accepts
reviewed `nix/*.nix` definitions (including subdirectories), the interactive helper
and listed C Makefiles as well as `flake.nix`; it copies composition inputs too.
Its file allowlist is not proof of ABI preservation. Compare generated RTL and
ABI collateral before programming, and record baseline-specific packaging tests
separately from final-master evidence.
Review any patch: it must preserve HW/ABI generation. Never force-load a module
with incorrect vermagic. Image hashes are recorded provenance, not pinned test
requirements. `/scratch/pengxu` is shared between gateway and CPU.

Build the matching client on the gateway (requires GCC, rpcgen and libtirpc):

```sh
mkdir -p /tmp/client-2812450
for file in Makefile bench_client.c; do
  git show aa32b1af:sw/apps/microbenchmarks/client/$file > /tmp/client-2812450/$file
done
git show aa32b1af:sw/apps/microbenchmarks/bench.x > /tmp/client-2812450/bench.x
rsync -rlt /tmp/client-2812450/ enzian-gateway:/scratch/pengxu/lauberhorn-client-2812450/
ssh enzian-gateway 'cd /scratch/pengxu/lauberhorn-client-2812450 && make'
```

Recheck tool versions: the tested gateway hardware server is 2023.2 on port
3121, matching `/opt/Xilinx/Vivado/2023.2/bin/vivado` on ba2. For offline 2025.1
checkpoint analysis, ba2 has `/opt/Xilinx/2025.1/Vivado/bin/vivado`.

## 3. Run and read the summary

Copy [adder.example.json](../../tools/enzian/adder.example.json) to your case
file. Replace `UNIQUE_RUN` in its name with a fresh experiment label (also used
for remote logs). Inspect current CPU routes before using its `cpu40g1 down`
command: this removes the overlapping FPGA-subnet route; management/root storage
must remain on `cpu40g0`. The example is specific to zuestoll14: JTAG
`Digilent/210357B4B301A`, MAC `0c:53:31:03:01:c8`, IP `192.168.129.200/18`.

```sh
cp tools/enzian/adder.example.json /tmp/cases.json
# Edit the unique case name and review the machine-specific settings.
python3 tools/enzian/test.py /tmp/cases.json --logs /tmp/test-UNIQUE_RUN --repeats 2
cat /tmp/test-UNIQUE_RUN/summary.json
```

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
and reprogramming, up to three attempts (`--boot-attempts` overrides the limit).
The first attempt keeps the usual log paths; retries use `retry-02/`, etc., and
`boot/summary.json` records each outcome. Exhaustion is an infrastructure
failure; do not load the driver anyway. The explicit `--negative-no-bitstream`
test bypasses this gate because absent ECI initialization is intentional.

If `probe_versions` still fails after validated bring-up, freeze and dump trace
DDR using the [CMAC-first capture procedure](tracing.md#default-agent-capture-pipeline)
before resetting; fall back to JTAG if Ethernet fails. Retain partial captures
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
a full kernel panic. Reset after an oops. ECI links being up does not establish
working register accesses or RPCs. See [recorded results](hardware-test-results.md)
for known failures and repeatability limits; historical results are not a new test.

## Use a second Enzian as the traffic generator

Acquire an available machine with `emg acquire MACHINE`; `-g 2025-07-28`
selects the newer golden image if needed. Do not copy the DUT's isolated-core
kernel arguments to the client machine. Verify its reservation, boot Linux,
and check `ip route get DUT_FPGA_IP`: `cpu40g1` is in the FPGA NIC VLAN.
Use `cpu40g1` for traffic/capture on the client and `lauberhorn0` on the DUT;
keep management/root storage on `cpu40g0`.

The client does not need a programmed FPGA or the Lauberhorn kernel module.
`boot.py --machine MACHINE --negative-no-bitstream` can boot it without
programming. This mode's log label describes the absent FPGA image; no negative
module-load test is implied. If a newly acquired BMC reports an uninitialized
`pac_cpu` driver on `power_down()`, inspect its state and initialize sequencer
drivers before retrying the normal verified reset. Do not treat `is_up` in the
reservation listing as proof of Linux readiness.

For a matched AArch64 client independent of the golden image's development
tools, `student_build.nix` also exposes `peerImage`. Mount that closure on the
peer without loading its kernel module, then run its `*-lauberhorn-peer-client`
binary. Keep every test's client machine, software and capture paths in the
manifest. Never count peer boot/setup failures as runtime test failures.

## Student runtime and Dandelion application-only probes

`runtime-peer.example.json` records the five-case setup (upstream add/mul,
Sven's standalone/nested adder, Dandelion matmul). Edit machine addresses,
programmer paths, image paths and unique case names before running it. Stage
the client scripts in its shared directory and prepare the peer first.

`student_build.nix` builds Sven's adder demos, the AArch64 baseline client,
and Dandelion against a pinned runtime revision. It uses that same revision for
RTL/header generation and the NIC revision marker, while importing this tree's
kernel-release packaging fix. Before using it, verify `sw/{rt,include,core,kmod}`,
`hw`, `build.mill`, `project-lock.nix` and hardware dependencies match the pinned
revision. It is not a mechanism for testing changed hardware under an old marker.
Record the student source revisions, endpoint patch, and output image hashes.

Prepare archived source directories from `../sven-dandelion/platform` and
`../sven-dandelion/dandelion`. For the platform snapshot archive only
`sw/apps/adder-demo` and `sw/apps/nested_adder-demo`; the recipe supplies the
matched upstream includes and common Makefile. Archive the complete Dandelion
tree. The recipe applies `student-nested-remote.patch` itself to initialize one
shared endpoint before workers, preserve request IDs and configure the nested
destination through `NESTED_REMOTE_IP` (default: local NIC `192.168.129.129`).
Use unpatched snapshots. The main flake now also exposes pinned `dandelion` and
`dandelionDeployFs` outputs. Pass the archived snapshots'
absolute paths as Nix path arguments:

```sh
nix build --impure --file tools/enzian/student_build.nix \
  --argstr runtimeRevision "$(git rev-parse HEAD)" \
  --arg studentSource /ABS/PREPARED-STUDENT-PLATFORM \
  --arg dandelionSource /ABS/DANDELION-SNAPSHOT \
  studentImage --out-link /tmp/student-image
# Other outputs: peerImage, dandelionImage, apps, dandelion, dandelionTests.
```

`student_adder_client.py --bind PEER_DATA_IP` checks 100 calls to Sven's
standalone adder, including its echoed 64-bit timestamp and arithmetic result.
That schema differs from both the upstream benchmark and nested demo.

`nested_adder_client.py --bind PEER_DATA_IP` runs the nested-demo correctness
client and an ordinary UDP ONC-RPC add responder on that peer. Start the DUT's
nested demo with `NESTED_REMOTE_IP=PEER_DATA_IP`. This deliberately tests remote
nested calls, not unsupported switch reflection or local loopback. It checks
sum=10 and receipt of two future calls plus one callback call; socket observations
are not a substitute for a packet capture. It reports but does not validate the
nested response's request ID, which Sven's handler leaves uninitialized.
`capture-client.sh` can wrap the
client, preserving `peer.pcap`, capture diagnostics and the client result.
Stage `peer_capture.py` beside that wrapper. Its raw socket records Ethernet
frames to/from the DUT MAC without putting the peer interface into promiscuous
mode. PCAP timestamps are userspace receive times, not latency measurements;
the recorder does not measure capture drops. This avoids the tested image's
tcpdump failures (NFS ownership change failure and a later SIGSEGV).

`dandelion_matmul_client.py` mirrors the student's single-function `test_matmul`
fixture: register the AArch64 process-isolated matmul binary, invoke a 1x1 matrix
through FPGA UDP RPC, then validate the returned matrix. It needs pymongo's
`bson` module and a DUT-visible fixture path; specify separate `--management`,
`--nic`, `--bind` and `--binary` arguments. Set `PROCESS_WORKER_PATH` to the
packaged `mmu_worker` and `DANDELION_LAUBERHORN_CONFIG` to the DUT configuration.
Composition tests remain separate because local nested transport has not landed.

## Historical B4 recovery evidence

The September B4 campaign used the superseded control-line and recovery ABI.
Its runner is removed; it must not be used with cooperative PREEMPT images.
See the [recorded results](hardware-test-results.md#b4-kernel-parking-fix-on-512199--2026-09-18)
for the tested revisions. Current unsupported exit and fatal faults kill and
fence the affected domain, retaining resources until a full coherence reset.
Complete recovery and parked-domain deadline activation remain deferred.

## Cooperative RX hold campaign

The matched module exposes root-only parameters:

- `/sys/module/lauberhorn/parameters/rx_hold_cycles`: write the NIC hold interval
  in application-clock cycles; read returns the programmed hardware value.
  The implemented timer is 64 bits with one-cycle resolution. Convert using the
  matched bitstream's actual clock, and verify durations in traces.
- `/sys/module/lauberhorn/parameters/l2c_tad_snapshot`: read all eight mapped
  units' BAR, INT, STAT and TIMEOUT values. Reads do not acknowledge interrupts
  or modify timeout protection. A clean snapshot alone is insufficient: the
  normal EDAC handler can clear INT, so retain kernel exception logs too.

First establish a passing setting on the new image. Increase hold duration to
an exception or the register limit, then refine at timer resolution. Preserve
traces and fault state before every failing-trial reset. Repeat the highest
passing candidate under contention across two cold boots; record exposure,
nearest failing bound, actual hold durations and peer completions. Include
one/multiple stalled peers and all-but-one stalls, active-peer handoffs and
simultaneous bypass RX/TX. Report the highest **tested** exception-free setting;
do not substitute a historical threshold or weaken architectural protection.

Check TAD state and kernel logs again **after** the follow-up RPC check. A clean
short idle probe can fault later at the same setting: the October 3 campaign
observed this at 27,204,589 cycles. A timed-out client is not a passing probe;
preserve its trace and subsequent fault state before continuing or resetting.
Bound the client process and its cleanup as well as the RPC deadline. Confirm
that the next value was actually programmed before attributing a fault to it:
a later 27,193,602-cycle trial faulted before the following write. Its preserved
trace still showed only the preceding value. Withdraw such a short pass and
re-establish the lower bound with a longer exposure; do not keep narrowing
around a lower bound that has already failed.

Use the matched generated trace map to quantify intervals and overlapping peer
transport events:

```sh
python3 tools/enzian/analyze_rx_holds.py RUN/trace-dram.bin \
  --map RUN/lauberhorn_trace_dma_map.json --clock-hz ACTUAL_APP_CLOCK_HZ \
  --minimum-hold-cycles PROGRAMMED_HOLD \
  --output RUN/rx-holds.json
```

The report includes capture/map hashes, loss and incomplete-pair counts. Its
request-to-NACK intervals include protocol/response overhead; pending handoffs
are marked. Peer RX/TX, CMAC and activation events are supporting transport
evidence. Confirm application completions independently using client results.

For finite concurrent peer load, `tools/enzian/cooperative_client.py` supports
`add`, `mul`, the packaged `nested-validation` fixture's `echo`, and kernel UDP
`bypass` streams. It checks every response and source, records individual send/
receive timestamps in `completions.csv`, and fails without retry on loss or
corruption. Start its `--bypass-server` mode on a non-worker DUT CPU, binding
only the DUT NIC address; this is an ordinary kernel UDP echo service on 32345.
Create fresh output directories for each run. For example, on the reserved peer:

```sh
python3 cooperative_client.py --bind 192.168.129.129 --nic 192.168.129.200 \
  --streams add,echo,bypass --seconds 60 --window 1 --output NEW_PEER_RUN
```

Activate services in a recorded order and verify actual worker placement from
traces. Additional workers still require the existing queue watermark; declaring
three threads or sending three sequential calls does not allocate three workers.
To exercise all-but-one stalls, first place an active service on worker 0, then
use a bounded burst to allocate the three-thread stalled service on the other
workers. Alternate the two one-thread active services to require cooperative
handoffs while bypass traffic continues. This is a campaign procedure, not a
claim that the placement or progress has already been observed.

Save plain `l2c_tad_snapshot` output and complete kernel logs before and after
each trial. `tools/enzian/assess_tad.py --before TAD_BEFORE --after TAD_AFTER
--kernel-before DMESG_BEFORE --kernel-after DMESG_AFTER --output NEW_REPORT.json`
requires exactly eight distinct units, checks their interrupt/timeout state,
and rejects kernel fault diagnostics or missing log continuity. It only reads
saved files. EDAC may clear a hardware interrupt before a snapshot, so neither
register snapshots nor logs alone suffice. Preserve the board state on failure.

The hold analyzer additionally groups independent transport events by the number
of **simultaneously** stalled workers whose completed intervals meet
`--minimum-hold-cycles`. Bypass is excluded from the stalled-worker count;
ordinary `YieldAccepted` events are distinguished from initial activation.
The report also separates idle/ordinary activation counts by physical worker
and PA domain, and request dispatch counts by Linux PID and physical worker.
Trace `CoreID` is the NIC datapath ID: zero is bypass, and application worker
index is `CoreID - 1`; it is not a Linux CPU ID. Keep these identity spaces
distinct. Preserve a full capture, then analyze a
recorded byte range for each measured trial so earlier phases cannot inflate
its counts.
Use these overlap counts together with client completions and TAD assessment.
They do not prove progress outside the recorded window.

For the active-peer handoff phase, `cooperative_client.py --streams add,echo,bypass
--alternate-rpc --rpc-gap-us 2000` keeps one application RPC in flight and rotates
its destination after each validated reply. Bypass retains an independent window.
The completion CSV records the actual request spacing. This workload encourages
cooperative handoffs; verify physical-worker placement and simultaneous holds in
the trace before counting it as an all-but-one-stalled-worker result. Pool sizes
alone do not establish that placement. The pinned loopback test verifies RPC
alternation, the minimum gap and bypass completions between RPCs.
