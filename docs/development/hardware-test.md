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
(`ENZIAN_PASSWORD` overrides the console password); BMC root password `0penBmc`.
The BMC helper prompts if login is needed. CPU console is `zuestoll14-console`,
BMC console is `zuestoll14-bmc`; detach with Ctrl-E, c, period. Do not force
another console user off. See the [quickstart](https://gitlab.inf.ethz.ch/project-openenzian/documentation/userguide/-/jobs/artifacts/main/raw/enzian_quickstart.pdf?job=build)
and [machine/JTAG inventory](https://enzian.systems/generated/cluster-info.html).

## 2. Prepare matching artifacts once

Known working reference: job **2812450**, commit
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`. For another build, update the job,
revision and paths together. Do not poll CI unless asked to wait for it.
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
programmer argv `program`, post-verification shell commands `cpu`, and gateway
shell command `client`. The `cpu` function exposes all `cpu.sh` steps. `{run}`
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
stop the campaign as infrastructure failures; CPU failures skip RPC. Timeouts
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
DDR over JTAG before resetting; retain the matching map, VIO status, and image
provenance. Keep tracing active through module load. The shell-version read is
handled inside the static shell, so arm the static ECI edge ILA before the
attempt when possible; the application DDR trace alone may not show that read.

## Focused debugging

- [run.py](../../tools/enzian/run.py): run a command or local shell script over
  the CPU console; `--expect REGEX` asserts ordered output instead of exit zero.
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
