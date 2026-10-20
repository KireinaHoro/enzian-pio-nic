# Hardware observations and preservation

These are dated observations migrated from the entry-point handoff, not live
reservations or permission to reset. Recheck reservations, processes, tracing
and saved provenance before board access. Never reset an unpreserved failure;
follow [AGENTS.md](../AGENTS.md) and the [capture procedure](../docs/development/tracing.md#default-agent-capture-pipeline).
Do not substitute vulnerable pipeline 515944 for corrected artifacts. Match
bitstream, trace map, headers and deployment image.

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
[Recorder acceptance and evidence](../docs/research/memcached.md#corrected-recorder-preflight-and-board-acceptance--october-11).
This supersedes earlier claims that Dandelion, memcached or the 518689 null-RPC
cohort was still running; it does not establish today's DUT state.

## Preservation references

- Corrected 516232 module `af0f64fe` passed two fresh cold boots after an
  idle-activation failure. Keep the original failure capture; the [GSYNC record](../docs/hardware/gsync-worker-reproduction.md)
  distinguishes it from successful follow-up.
- October 8 Dandelion left its tracing-off control frozen, with two instrumented
  windows preserved. The October 9 [memcached follow-up](../docs/research/memcached.md#first-native-board-attempt-after-merge)
  preserved that buffer before deploying matched 517951 artifacts.
- October 10 matched 518689 RPC trials passed 200 calls before subsequent
  application campaigns. Earlier memcached failures and successful burst
  captures are linked from the [switching evidence](../docs/research/memcached.md#five-instances-on-four-workers-october-10)
  and [burst evidence](../docs/research/memcached.md#four-workers-per-tenant-and-variable-bursts-october-10).
- All four October 10 null-RPC windows were preserved; see the [inline-TX record](../docs/research/latency-measurement.md#inline-tx-and-pre-doorbell-probe-measurement--october-10).
- The October 11 five-instance extension stopped after 2,688 requests on peer
  ENOSPC. Its frozen trace and software records were preserved; use RAM-backed
  peer output next time. [Failure and timestamp record](../docs/research/memcached.md#first-joined-rtt-breakdown-and-recorder-limitation--october-11).
- The [vanilla Linux comparison](../docs/research/memcached.md#vanilla-linux-mac-comparison--october-11)
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
