# Hardware observations and preservation

These are dated observations migrated from the entry-point handoff, not live
reservations or permission to reset. Recheck reservations, processes, tracing
and saved provenance before board access. Never reset an unpreserved failure;
follow [AGENTS.md](../AGENTS.md) and the [capture procedure](../docs/development/tracing.md#default-agent-capture-pipeline).
Do not substitute vulnerable pipeline 515944 for corrected artifacts. Match
bitstream, trace map, headers and deployment image.

## October 27 TCP acceptance and frozen trace failure

zuestoll07 is held at BDK after three independently power-cycled attempts.
Each attempt verified CPU/FPGA rails off (VDD_CORE 0/0.00390625 V,
VCCINT_FPGA 0 V), programmed exact `99fb5fed`, reached startup HIGH and failed
DQS-gate calibration at rank 0, nibble 17 (preamble missing, all samples one).
The revised harness `81639e1a` cold-cycles before every LOW/CAL FAIL retry,
with at most three programming failures; eleven boot tests passed. The live run
exhausted that limit. No memcached test or trace capture ran, and no further
reset or programming followed. The physical failure persists across cold starts.
Evidence: main `out/tcp-fmac3-cold-retry-20261027/` and ba2
`/tmp/tcp-fmac3-cold-retry-20261027/`, with verified hashes. The earlier in-place
programming retry remains under `out/tcp-fmac3-retry-20261027/`.
Read-only exact `99fb5fed` DCP analysis (`out/mig-checkpoint-99fb5fed-20261027/`)
found DDR/UI WNS +0.003 ns, WHS +0.010 ns, no setup/hold/pulse failures;
visible MIG incoming/outgoing worst setup +0.008 ns and hold +0.010 ns.
Fresh default DRC has no errors/critical warnings. Relevant warnings are
BIIVRC-1 (bank70 nominal 0.600 V versus POD12_DCI 0.840 V) and methodology
LUTAR-1 (PHY PLL reset logic). AMD PG150 documents the nominal Vref/initial
POD reference distinction; the warning alone does not establish a voltage bug.
All 45,013 MIG primitives with an SLR assignment are in SLR2 (1,441 have none).
These results do not establish external DQS signal integrity or calibration.
Direct comparison with hot-breakdown pipeline 519555 (`0e58f058`) is preserved in
`out/mig-compare-519555-99fb-20261027/`: MIG parameters/custom DIMM identical;
all 147 F_D4 port records (pins/electrical properties) identical; I/O, BITSLICE,
PLL/MMCM and Vref primitive sites unchanged. 44,773 shared fabric-cell LOCs
changed, still within SLR2. Bank70 Vref warning occurs in both checkpoints.
Crucially, the October 21 zuestoll07 failure capture with 519555 already logged
Calibration Failed and writeSlot=19: `out/hot-full-20261021/stages/
a7f44c1dc8aae63fe0fc/attempt-1/echo-58e3c9b3-4b49cc684019-9010e3992b25b811-failure-dump/`.
The calibration fault therefore predates the recent trace move. Do not equate
successful CPU/Tofino hot breakdowns with verified FPGA DDR capture; an earlier
successful same-board FPGA capture has not yet been located in inspected records.



The preceding exact `99fb5fed` memcached TCP test passed with peer zuestoll14,
including 16 clients, binary/text commands, pipelining, backpressure and reset
recovery. Its F_MAC3 export received metadata and ARP but stalled on the first
DMA read. That frozen live state was superseded by the explicitly authorized
retry; all original evidence remains in `out/tcp-fmac3-acceptance-20261027/`
and ba2 `/tmp/tcp-fmac3-acceptance-20261027/`. No full trace was captured.
The MIG remains in its original SLR2 region and on F_D4. The relocation did not
change its region or DDR configuration. Trace MAC learning on leaf0 Ethernet108
and bidirectional packets contradict SONiC's down/no-transceiver report; do not
infer a missing cable. Recheck reservations before board access.

## October 22 generator reservation recovery

After an accidental release, EMG reacquired zuestoll14 on the existing
`echo-generator32-z14-20261021` volume with the same kernel/initrd and CPU
isolation arguments. No reboot/reset occurred. An attempted `emg acquire
zuestoll14 --help` unexpectedly acquired a generated volume; this was corrected
through EMG release/reacquire before board access. EMG confirmed the named old
volume already existed and skipped creation. Inventory/acquisition logs and live
restoration checks are in `out/zuestoll14-reacquire-20261022/`.

Campaign r4 stopped at 833 accepted acquisitions because its final client-file
cleanup required a reservation. Both roles had passed/restored and exported
intact evidence. After both hosts' live process/NIC/resource restoration checks,
the maintained cleanup completed and the same campaign/service resumed, retaining
all accepted data and retrying CS-A64 1432 B / two workers / 55 kRPC/s. The failed
attempt remains preserved; no acquisition/configuration or binary change occurred.
Restoration review and submission receipt are `restoration-review.json` and
`resume.json` in that recovery directory. Do not poll after launch.

## October 21 generator reacquisition

At the maintainer's request, EMG reserved zuestoll14 again under `pengxu` as
the load generator. The explicit fresh root is `echo-generator32-z14-20261021`,
cloned from golden `2025-07-28`; boot selection is
`pengxu/lauberhorn-6.8.0-146-ptp/{vmlinuz,initrd.img}`, with CPUs 16–47 isolated
and CPUs 0–15 handling ordinary tasks/IRQs. The former generator volume remains
preserved. Acquisition and both post-acquisition inventory checks are in
`out/zuestoll14-generator-reservation-20261021/`.

The later full-campaign authorization booted zuestoll14 through the shared
CPU-only harness; first attempt passed. Preparation evidence is
`out/hot-full-20261021-preparation/generator-boot/summary.json`. Live preflight
verified kernel 6.8.0-146, isolated/nohz CPUs 16–47, management routing and RAM
budgets; both zuestoll07 and zuestoll14 remained reserved to pengxu. The
first campaign `out/hot-full-20261021` failed at generator image launch because
`/nix/store` was absent; DUT boot/ECI/module gates passed. Exceptional trace
receiver launch failed for the same reason. Old evidence and unsafe state remain
preserved. The maintainer then explicitly authorized starting from scratch,
including cold reset without recovering that frozen 1,216-byte trace.

The corrected image launcher passed a live no-traffic bootstrap on zuestoll14,
and fresh preflight passed before submission of `out/hot-full-20261021-r2`.
Authorization and receipts: `out/hot-full-20261021-r2-preparation/`.
See [measurement continuation](measurements.md#active-full-hot-campaign--october-21).
Recheck live state before further manual board access.

## October 18 echo topology observation

The October 18 role-swap record reports: both named roots were released and reacquired
exclusively through EMG, without cloning or deleting them. zuestoll07 booted
`lauberhorn-linux-6.8` with CPUs 44–47 isolated; zuestoll14 booted
`echo-generator32-20261015` with CPUs 16–47 isolated. Both booted kernel
6.8.0-146 through the shared harness. The initial 07 boot was CPU-only for CX-5;
Lauberhorn requires matched FPGA programming and normal ECI acceptance.
The swap and live command-line evidence are in `out/tofino-unloaded-20261018/`.
No switch capture is implied by this boot verification.

These observations supersede the October 11 topology as a deployment reference,
not as proof of current reservations or running services. Recheck both machines
before access; the role-swap record does not establish a later FPGA deployment.

## October 11 recorder acceptance observation

After October 11 pipeline 518922 acceptance, the DUT ran the matched RPC
service with tracing frozen and the full capture saved. The cold reset ended
the native memcached instances. The preceding vanilla Linux capture was
preserved, and its temporary upstream process had been stopped.
[Recorder acceptance and evidence](../docs/applications/memcached.md#joined-rtt-measurements-and-recorder-limitation).
This supersedes earlier claims that Dandelion, memcached or the 518689 null-RPC
cohort was still running; it does not establish today's DUT state.

## Preservation references

- Corrected 516232 module `af0f64fe` passed two fresh cold boots after an
  idle-activation failure. Keep the original failure capture; the [GSYNC record](../docs/research/eci-progress.md#controlled-gsync-results)
  distinguishes it from successful follow-up.
- October 8 Dandelion left its tracing-off control frozen, with two instrumented
  windows preserved. The October 9 [memcached follow-up](../docs/applications/memcached.md#integration-and-ownership)
  preserved that buffer before deploying matched 517951 artifacts.
- October 10 matched 518689 RPC trials passed 200 calls before subsequent
  application campaigns. Earlier memcached failures and successful burst
  captures are linked from the [switching evidence](../docs/applications/memcached.md#five-instances-on-four-workers)
  and [burst evidence](../docs/applications/memcached.md#four-workers-per-tenant-and-variable-bursts).
- All four October 10 null-RPC windows were preserved; see the [inline-TX record](../docs/research/hot-path-performance.md#contiguous-layout-regression-analysis--october-10).
- The October 11 five-instance extension stopped after 2,688 requests on peer
  ENOSPC. Its frozen trace and software records were preserved; use RAM-backed
  peer output next time. [Failure and timestamp record](../docs/applications/memcached.md#joined-rtt-measurements-and-recorder-limitation).
- The [vanilla Linux comparison](../docs/applications/memcached.md#linux-bypass-control)
  capture was preserved before 518922 cold-reset acceptance.

These pointers preserve recovery context; no captures or raw evidence were
removed by the documentation migration.

## CMAC initialization evidence reference

The October 9 pipeline 518139 (`79d5758f86a9b891234519b46e49226e01cfbb3d`)
passed two cold boots and 200 checked RPCs with register-controlled F_MAC0
startup. A BMC power-read failure and a rejected CCPI boot are separate failures.
This tested startup at module load, before the later software UP/DOWN lifecycle
restoration; it does not establish physical failed-UP/retry recovery. WNS was
−0.881 ns, not timing closure. Subsequent deployments supersede its live-DUT note.

Artifacts and manifests: `/tmp/lauberhorn-cmac-axil/out/cmac-ci-518139/`;
results: `board-rpc/summary.json`, `board-rpc-retry/summary.json`,
`trial-01-results/` and `trial-02-results/` beneath that root. Deployment SHA256:
`c489cbe0b3f2fad1aba9313963d82638872e42c8da4e83e6df7d74f73c09ea76`.
The prior 517951 failure was preserved at
`out/native-udp/memcached-recovery-trace/trace-dram.bin` (682,201,600 bytes;
SHA256 `76450049ee7cbe01f0a0f8880d37afd980a53ed77aab6262f53831f2277748ba`).
Its sticky sample-loss limitation remains; only a hash-matched remote duplicate
was removed for quota recovery. Full historical provenance is in Git at
`418f8bcc:docs/development/cmac-axil-init.md`.

## CMAC trace readout evidence

The September 28 F_MAC1 trial used revision
`5cda9017fdae1177c002ad0ba51ec3176019c917`, pipeline 514561, hardware job
2852730 and software job 2852729 with matched LTX/map. One cold boot passed
300 add RPCs without retries. Three reads of one frozen buffer each returned
322,322,624 bytes with zero retransmissions/timeouts and identical SHA256
`b7532515fae2e5d4b500c302eb55d4bd75f2a80312130d9e18dc7532cb951c68`.
Independent first/final 4 KiB JTAG samples matched. The buffer contained zero
loss markers; sticky sampleLost asserted only after freeze. Two auxiliary
PCAPs dropped packets locally; the final RAM-backed, 200-us-paced PCAP had
complete coverage and no payload mismatches or socket drops. F_MAC1 switch
FEC/lane-error counters remained zero. This is one boot and one buffer, not
long-duration reliability or proof of F_MAC3's physical root cause. WNS was
−0.062 ns with 184 failing setup endpoints, not timing closure.

Before that deployment, the intermittent F_MAC3 link recovered and the full
211,086,144-byte frozen capture was preserved with no retransmissions or loss
markers; head/tail matched earlier JTAG samples. SHA256:
`671ee92e64b5d03fabca4e97fc5efbf6ddb5fa0df1eae4af23a408a488cede5e`.
This resolves the earlier incomplete-dump preservation hold. The old full-JTAG
attempt was stopped; it is not the supported recovery procedure. Later board
observations supersede the September running-service and tracing-state notes.

The experiment worktree's complete ignored output was copied and checksum-compared
to `out/cmac-trace-archive/`. Resolve historical `out/...` references beneath
that root; retained manifests contain old absolute paths and must not be run
blindly. Principal evidence:

- `hardware-tests/cmac-fmac1-20260928/`: `summary.json`, board logs, three
  received captures, JTAG samples, switch snapshots and `restore.log`;
  `recover-old/received/` holds the recovered F_MAC3 buffer.
- `ci/cmac-5cda901-hw-5h/`: matching CI artifacts and hashes.
- `hardware-tests/cmac-fixed-514053/`: earlier one-MiB F_MAC3 UDP capture,
  PCAP, bounded JTAG comparison and PHY snapshots; link remained intermittent.
- `hardware-tests/cmac-full-20260928/`: partial-read failure and head/tail
  evidence before recovery. `hardware-tests/cmac-switch-20260925/` records
  the short-ARP and internal-TKEEP-gap diagnosis and framing regressions.
- `physical/cmac-placement-514053/summary.json`: supported F_MAC3 placement
  and pad mapping; no root cause established by the offline placement audit.

Full investigation history, including earlier diagnostic pipelines and probes,
is in Git at `f3e1ddaa:docs/development/cmac-trace-diagnosis.md`. No outstanding
CMAC experiment or CI launch is implied by these historical records.

## October 4 shared-root release observation

The traffic-generator campaign accidentally reserved zuestoll10 with the same
named root as zuestoll14; 10 was never booted or mounted. EMG release removed
shared export configuration and attempted target deletion without checking the
other reservation. Check root/export sharing before releasing a reservation;
use EMG exclusively rather than editing exports or backing volumes manually.
The maintainer subsequently authorized release of all boards. Reservations
14/10/12/07 were removed; generated roots for 07/12 were deleted and named
`lauberhorn-linux-6.8` retained. The active target refused deletion; no manual
cleanup was forced. This is a dated closeout, not current reservation state.
Evidence: `out/benchmark-zs07/release-all-final.txt` and the
[ledger closeout](../data/eci/board-evidence/dpdk-client-20261004.json).

## Early boot and artifact evidence

September trials on zuestoll14 used the following matched historical builds:

| Build | Functional observation | Final setup timing |
| --- | --- | --- |
| 2812450 / `aa32b1af` | Initial 101 add calls; two verified-reset repeats of 100 passed | WNS −1.051 ns |
| 2811847 / `93f4c1da` | Static-shell read oops despite programming and ECI RUN | WNS −1.813 ns |
| 2383312 / `4fa10def` | Initial shell-read oops; two resets passed module checks but failed RPC | WNS −0.164 ns, TNS −165.535 ns, WHS +0.004 ns |

Reset dwell and run-to-run variation were confounded. These small samples do
not establish reliability or explain failures by WNS. The negative test twice
aborted in `probe_versions` at shell-version address `0x97effffffff8`; an
application trace alone does not capture this static-shell access. Matching
software included a kernel-release packaging correction, never a force-loaded
6.8.12 module. March's legacy `add.x` lacks request IDs; use its matching client.

Evidence under `out/hardware-tests/`: `reproducibility/verified-trials/summary.json`,
`reproducibility/repro-{01,02}-{2812450,2383312}/`, `2812450/`, `2811847/`,
`2383312/`, and `build-selection/`. Excluded monitor/harness failures remain in
`reproducibility/trials/` and `monitor-recovery/`. Older ba2 archive
`Downloads/artifacts(21).zip` was associated with 2383312 by generation timestamps
and later NIC revision checks; `(22)` matched job 2645398 / `76c1ae40`.
June jobs 2623585/2622870 returned artifact 404s. Legacy client copies are in
`out/hardware-tests/2383312/client/` and
`/scratch/pengxu/lauberhorn-e2e-2383312-client/`.

## Early CMAC and boot-gate evidence

The September polarity ECOs were not accepted deployment artifacts. One changed
an ECI clock route and failed version reads; a route-locked retry passed version
checks but later panicked. Original hardware subsequently passed 100 RPCs.
Evidence: `out/hardware-tests/cmac-trace-20260917/summary.json`. The ARP-body
parser regression passed after correction; later framing fixes and F_MAC1
acceptance supersede these early capture failures.

Pipeline 512110 (`4c898645`, jobs 2834758/2834757) failed shell access on a boot
without the CCPI lane report, then passed 100 RPCs after a cold boot with all
lanes. Its first DDR snapshot was taken with tracing already stopped and does
not capture the abort. Partial ILA/DDR windows in
`out/hardware-tests/cmac-trace-512110/` are not a full credit reconstruction.
The boot gate now requires QLM8–13 lock and all 24 lanes; use the current
[harness procedure](../docs/development/validation.md#board-validation) for retry limits.

The September 23 matched 512381 trial (`5adb1e7`, jobs 2837292/2837291) passed
100 RPCs after one automatic boot retry, but trace data/status requests failed.
Its metadata alone did not establish a working readout path. Summary:
`out/hardware-tests/cmac-20260923/summary.json`. The initially volatile peer
capture was subsequently preserved under
`out/cmac-trace-archive/hardware-tests/cmac-diagnosis-20260924/prior-capture/`;
the later [CMAC evidence](#cmac-trace-readout-evidence) supersedes its old
copy-blocked and unresolved-readout notes.

## RX writeback regression evidence

October 2 pipeline 515621 / hardware 2861585 (`2699f580`) failed before any
application RPC completed. Boot retry passed the lane gate and module checks;
client creation then failed and console responsiveness was lost. Its preserved
139,951,936-byte F_MAC1 capture was unwrapped, repeat-hash-matched and free of
decoded loss/DCS errors. The selected exclusive RX read at `0x100` received
PEMD, then FLDX_EH returned HAKD dmask 15. DCS entered WDD without WDDA/LCIA;
the next `0x80` read remained blocked. AXI handshakes were not traced on board.
The directed actual-DCU/router diagnostic reproduced the absent write service;
withholding B retained the wait, releasing B completed WDDA/LCIA and the read.
Four expected diagnostic outcomes and six selected-ROM checks passed, not a
production acceptance campaign.

The September 28 `5cda901` comparison used PSHA/RA2 and SINV_H with HAKD dmask 0.
TX already used data-bearing FLDX_EH/HAKD. No CPU store is required to explain
the exclusive RX response. Evidence and excerpts:
[lci-only trial](../data/eci/board-evidence/lci-only-20261002.json),
[permission comparison](../data/eci/board-evidence/rx-ra3-comparison-20261002.json),
[RTL diagnostic](../data/eci/board-evidence/rx-writeback-rtl-20261002.json).
Raw failure artifacts remain in `out/board/20261002-lci/` and its recorded
shared/ba2 locations; later deployments supersede the frozen-live-DUT note.

Pipeline 515802 (`a51139ef8816ecc91366043016867caccf7b0e0c`, jobs
2863306/2863307/2863308) added independent RX write completion. Two cold boots
passed 100 adder RPCs each with no reported kernel faults. A prior missing-pexpect
invocation failed before reset and is not a hardware trial. The first capture
was preserved before the second reset: 429,277,056 bytes, SHA256
`d3b147b45535c95b992c80a9f5db68f921b132aeb5d4980f6d91719a4ba620c3`.
It has no decoded loss or asserted DC errors; 144 code-6 events are the trace-only
ERR_REQ_STALLED diagnostic. The old blocked sequence now completes WDD→WDDA/LCIA
and the next read. Aggregate equal WDD/WDDA counts are not independent transaction
proof. WNS −0.005 ns with one failing endpoint is not timing closure.
[Fix ledger](../data/eci/board-evidence/rx-writeback-fix-20261002.json);
`out/board/rx-fix-515802/` and `/scratch/pengxu/rx-fix-515802/` retain raw evidence.
This predates the scheduling/address pivot and current RX replay shadow.

## Isolation and contention evidence for 516112

Pipeline 516112 (`b2117e9625cca7ce256b44db34a5f2a46a952622`, jobs
2865917/2865918/2865919) passed all 13 gates and two first-attempt cold boots,
100 correct one-worker add RPCs each. Tracing and static ILA were armed through
module load; checks found no TAD/kernel fault. This finite baseline does not
establish adversarial isolation or full progress. WNS −0.396 ns, TNS −917.034 ns,
7,227 failing setup endpoints. [Matched ledger](../data/eci/board-evidence/isolation-516112-20261004.json);
`out/board/isolation-516112/` and `out/ci-followup/516112/` retain artifacts.

The follow-up passed finite three-peer contention across two cold boots at
26,656,301 cycles (133.281505 ms), with 180-second TAD/kernel/control checks.
RT throttling was disabled on the second run; recurring 50 ms delays disappeared,
but two approximately 124 ms echo outliers remained between promptly answered
TX acquisition and commit. The analyzer's recorder-bubble bug was corrected
and old ranges reprocessed. A serial setup failure was captured separately.
[Interpretation](../docs/research/eci-progress.md) and
[ledger](../data/eci/board-evidence/worker-hol-516112-20261004.json) retain limits.
Do not restart the closed dedicated campaign from these dated observations.

These summaries derive from `8f05507a:docs/development/hardware-test-results.md`.
Git retains intermediate trials and complete historical hashes; raw captures
were not removed. None of these service/reservation observations is live state.
