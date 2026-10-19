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
- Real Enzian hardware / adder E2E: use the [enzian-deploy skill](tools/enzian/skills/enzian-deploy/SKILL.md), then [hardware test quickstart](docs/development/hardware-test.md) → [example manifest](tools/enzian/adder.example.json) → `python3 tools/enzian/test.py CASES.json --logs NEW_DIR --repeats 2`. Check reservation and stage matching artifacts first; read `summary.json`, not full boot logs.
- Trace capture: use the [lauberhorn-trace skill](tools/enzian/skills/lauberhorn-trace/SKILL.md) and [canonical workflow](tools/enzian/trace/README.md). Manual and campaign capture share the same scripts; CMAC for full dumps, JTAG only for bounded integrity checks. Failed Ethernet capture stops without reset or full JTAG fallback.
- Simulation, trace analysis, validation selection: [validation](docs/development/validation.md).
- Research claims and evaluation: [paper goals](docs/research/paper-goals.md).
- Go runtime / HOST_WORK / future etcd: [design and dispatch matrix](docs/research/etcd.md); documentation only, no implementation authorized by this handoff.
- External TCP/Protobuf projects and later gRPC scope: [protocol roadmap](docs/research/grpc-roadmap.md), [TCP](docs/research/tcp.md), [Protobuf](docs/research/protobuf.md).
- Pending features and unresolved intent: [roadmap](docs/research/roadmap.md).

## Current handoff

October 13 Go/etcd direction is recorded in the [new design](docs/research/etcd.md).
HOST_WORK replaces the proposed name HOST_WAKE: it returns assigned RX waiters
to host-side work; allocation for unassigned domains is a separate requirement.
The current task ends with documentation and renaming, before implementation.
Future stages are Go adaptation, HOST_WORK with memcached hash-expansion
validation, Go native null-RPC including nested calls, then Go Raft and etcd.
Keep worker-core–endpoint affinity. Historical HOST_WAKE holds and evidence
below retain their dated scope; no runtime/RTL change or new board result is implied.

The active paper microbenchmarks use schema-defined echo RPCs at 8/128/1432
application bytes for both loaded and unloaded measurements. The canonical
contract and controller are in [benchmark campaigns](docs/development/benchmark-campaign.md)
and the [benchmark-campaign skill](tools/paper/skills/benchmark-campaign/SKILL.md).
Unloaded CDF: software probes compiled out, MAC tracing/PTP enabled, MAC-to-MAC.
Unloaded breakdown: software probes enabled, MAC tracing/PTP enabled, MAC-to-MAC.
Loaded: software probes compiled out, LH recording disabled with no trace readout,
DPDK PTP disabled, client RTT. Keep instrumentation and MAC collection distinct.
Use the paper's commented echo filenames for replacements; retain active PDFs
until validated replacements exist. Presentation changes remain uncommitted.
Recheck actual reservations and preserved hardware state before board access.

Timing optimization is paused at the maintainer's request. Platform master
adopts TX placement/refinement plus high-VC RX staging; selected CI revision
`1c72ea0` reached WNS -0.007 ns with 17 failing endpoints, not closure.
See the [closeout and remaining work](docs/hardware/physical-experiments.md#paused-after-consolidation--september-17).
DCS/low-VC/combined experiments remain unmerged. Toolkit commits stay on
separate feature branches/private refs pending colleague upstream approval;
never merge or push them to toolkit master. The existing ba2 local batch can
finish; no new timing work or CI polling until requested. Final master has no
new timing-closure result, and the static shell remains unchanged. The merged
CMAC trace path uses F_MAC1; revision `5cda901` passed 300 RPCs and three full
322 MB dumps, with WNS -0.062 ns (not closure). See the
[CMAC board evidence](docs/development/cmac-trace-diagnosis.md#f_mac1-board-validation-september-28).

The selected bounded Goal 2 models, 8 KiB physical-worker address layout and
cooperative DESCHED implementation are complete within their documented scope.
The original payload disclosure and inactive-domain faults have corrections;
516112 passed all CI jobs and two cold-boot 100-RPC trials. Never substitute
vulnerable 515944. The corrected 516232 module (`af0f64fe`) resolved its first
idle-activation failure and passed two fresh cold boots (200 measured + 32
warmup RPCs). The original failure capture remains preserved.

The October 5 follow-up verified GSYNC one-shot early NACK with 240 controlled
RPCs and one/two observed stalled peers. Buffered DPDK hot/cold cohorts passed
4,000 measured RPCs; kernel-off median RTT was 12.24 us hot / 43.36 us cold.
Hot CPU-work analysis and reproducible static counting are recorded. Read the
[consolidated status and open claims](docs/research/roadmap.md#current-status--october-8-consolidation)
for evidence links and remaining acceptance: four measured workers concurrently,
three stalled peers, adversarial isolation/recovery and full-system progress.
HOST_WAKE, service events/timers and complete kill/exit resource recovery remain
follow-on work. Timing optimization is paused; tested 516232 has WNS −0.102 ns
and 893 failing setup endpoints, not closure.

October 8 maintainer direction: merge the completed measurement/correction
branch and conclude the dedicated multi-worker campaign at its recorded finite
coverage. Further four-worker cases move into application validation; do not
claim those unrun cases passed. HOST_WAKE is on hold. Resume Dandelion using the
[active integration plan](docs/research/dandelion.md#active-integration--october-8).
The October 8 Dandelion campaign on reserved zuestoll14/zuestoll07 passed
atomic/DAG/concurrent-chain gates and 9,000 measured requests across allocation
and tracing controls. Depth-2 children overlap on CPUs 45–47 with their parent
on CPU 44 after bounded allocation warmup. See the
[waterfalls, provenance and limits](docs/research/dandelion-measurement.md).
That campaign left the tracing-off Dandelion control with FPGA tracing frozen
after success; both instrumented measurement windows are preserved. The October 9
[memcached follow-up](docs/research/memcached.md#first-native-board-attempt-after-merge)
subsequently preserved that buffer and deployed matched pipeline 517951 artifacts.
The October 10 pipeline 518689 follow-up passed two matched RPC trials (200 calls).
Native memcached then passed two five-instance/one-worker cohorts sharing four
physical workers, with trace-confirmed ordinary yields and process switching;
see the [finite switching evidence](docs/research/memcached.md#five-instances-on-four-workers-october-10).
A subsequent five-instance/four-workers-each synthetic campaign passed 61,440
requests across single/pair/all-tenant bursts and tracing controls. Its complete
batched trace confirms every tenant reached four assigned workers and 2,477
process switches; see the [burst and latency evidence](docs/research/memcached.md#four-workers-per-tenant-and-variable-bursts-october-10).
The bounded memcached campaign is closed; joined per-request client/application/
hardware tracing and nullrpc-style latency breakdowns remain follow-on work.
See the [closeout](docs/research/memcached.md#campaign-closeout-and-per-request-tracing-follow-up).
The latest successful cohort remains alive with tracing frozen. Earlier failures
are preserved. Full pool-size/performance measurements and HOST_WAKE remain open;
518689 is not timing closed (WNS -0.950 ns).
Recheck reservations and actual state before
access; do not treat a dated note as a live reservation or reset an unpreserved
failure. The [GSYNC record](docs/hardware/gsync-worker-reproduction.md) and
[latency record](docs/research/latency-measurement.md#corrected-module-516232-comparison--october-5)
distinguish the historical failure from completed follow-up tests.

October 10 null-RPC inline-TX correction on unchanged 518689 hardware passed
4,000 measured hot/cold RPCs plus 32 warmups, with one control forward/ack and
no overflow invalidation per measured TX. Kernel-off hot RTT is 12.760 µs and
MAC latency 4.165 µs, still above the old 3.700 µs MAC baseline. A conserved
FPGA-clock comparison localizes about 63 ns mean growth outside host turnaround
and 402 ns in host/link turnaround; see the [measurement and hardware comparison](docs/research/latency-measurement.md#inline-tx-and-pre-doorbell-probe-measurement--october-10).
The DUT runs that RPC cohort with tracing frozen; all four windows are preserved.
Recheck reservation and live state before reuse.

October 11 pipeline 518836 passed all gates and two matched RPC cold boots.
The first joined memcached RTT campaign passed 1,536 traced requests plus an
identical tracing-off control (and an earlier 1,536-request traced cohort).
Software medians are about 12 µs RX-to-response preparation and 2.7 µs TX
preparation. The Python generator dominates the observed client RTT. Hardware
intervals are qualified: the matched recorder timestamps after an input FIFO,
so queue residence distorts burst timings without sample loss. The correction is in pipeline 518922 (`cc19c428`);
two matched cold-boot RPC trials passed (200 calls), with zero recorded trace
loss markers. Corrected memcached measurements remain pending before precise
burst breakdown claims; system-event CDC timestamp uncertainty also remains.
See [evidence and timestamp limitation](docs/research/memcached.md#first-joined-rtt-breakdown-and-recorder-limitation--october-11).
The five-instance extension stopped after 2,688 requests on client ENOSPC;
its frozen trace and software records are preserved, not a passing campaign.
Recheck live state/reservations; use RAM-backed peer output for the next run.
The subsequent [vanilla Linux comparison](docs/research/memcached.md#vanilla-linux-mac-comparison--october-11)
passed 384 traced and 384 control requests over the FPGA bypass/netdev. Sustained
GET MAC p50 is 74.65 µs versus native 18.97 µs, with the same legacy timestamp
caveat; this is not a CPU-NIC baseline. Its trace is now frozen and preserved,
and the temporary upstream process was stopped. The subsequent 518922 acceptance
preserved that trace and cold-reset the DUT, ending the native instances. The DUT
now runs the matched RPC service with tracing frozen and its full capture saved.
See the [recorder board acceptance](docs/research/memcached.md#corrected-recorder-preflight-and-board-acceptance--october-11).

## Confirmed research and scheduling scope

The full paper builds an efficient low-latency RPC system on published 2F2F,
preserving OS flexibility and security. Caladan is the primary baseline, selected
as a closer match for runtime-managed core allocation and networking. Follow the
[minimal Enzian port and calibration plan](docs/research/caladan-enzian.md); the
port and its measurements are pending.
Planned applications: Dandelion, RPC server, userspace NFS, memcached, and
consensus such as etcd/Raft. Read [Dandelion](docs/research/dandelion.md) before
porting student runtime work from `../sven-dandelion`; its evaluation uses a
software mock. Tasks run to finish on isolated RPC worker cores, never shared
with ordinary tasks. Complete timeout/kill recovery remains open. The selected QP/DCU coloring is
implemented; board progress acceptance is still required.
Finished TCP/Protobuf student work lives in `../flavian-tcp` and
`../elena-protobuf`; merge soon to limit drift, below first-paper application
priority. Fully offloaded gRPC is a potential follow-on paper.

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
  the [automated test routine](docs/development/hardware-test.md). Vivado and
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
