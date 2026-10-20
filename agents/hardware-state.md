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
