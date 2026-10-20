# DPDK client validation — 2026-10-04

The reusable generator and trace accounting audit merged into master at
`f7150422`; the feature branch was removed. Physical client validation passed on newly reserved
**zuestoll07**. No FPGA was programmed and no Lauberhorn driver was loaded on
this client. zuestoll12/14 were not used for the campaign.

**The R12→RA3 TX latency comparison remains outstanding.** The numbers below
validate the client, not Lauberhorn. See the
[evidence ledger](../../data/eci/board-evidence/dpdk-client-20261004.json),
[generator instructions](../../../microbenchmarks/client/README.md) and
[trace accounting contract](../research/latency-measurement.md).

## Physical client results

Client: Ubuntu `6.8.0-146-generic`, patched DPDK 23.11.4, ThunderX
`0002:01:00.2` / `cpu40g1`, 40 Gb/s, PA/no-IOMMU, Linux CPU 6. Management/root
storage stayed on `cpu40g0`. The isolated configuration uses NO_HZ_FULL and
moves ordinary scheduling/IRQs away from CPU 6; DPDK telemetry is disabled.

All numbers in this table are **raw**, with no clock-probe subtraction.
Loopback echoes a 64-byte UDP payload through the client's own NIC; it does
not traverse a Lauberhorn server or measure link/switch/transceiver costs.

| Configuration | Measured / warmup | Median E2E | P99 E2E | Maximum E2E | Median client software | P99 client software |
|---|---:|---:|---:|---:|---:|---:|
| Initial loopback | 10,000 / 100 | 2.140 µs | 4.840 µs | 36.850 µs | — | — |
| Isolated trial 1 | 100,000 / 1,000 | 2.080 µs | 2.430 µs | 7.790 µs | 0.570 µs | 0.580 µs |
| Isolated trial 2 | 100,000 / 1,000 | 2.080 µs | 2.420 µs | 7.780 µs | 0.570 µs | 0.580 µs |
| Isolated trial 3 | 100,000 / 1,000 | 2.140 µs | 2.440 µs | 7.940 µs | 0.560 µs | 0.580 µs |

Client software is a **per-request sum** of encode, PMD enqueue and validation,
not a sum of percentiles. The polling interval includes NIC latency and polling
observation delay. All attempts succeeded; warmup is explicitly marked and
excluded only from reported distributions. First/last-quarter corrected medians
are respectively 2.050/2.030, 2.070/2.020 and 2.090/2.090 µs. Residual tails remain;
do not replace the distribution by a constant or subtract this echo calibration
from a differently sized RPC workload.

A gateway UDP echo test also passed **10,100/10,100 exchanges** over the real
switch path. Its raw median was 35.580 µs, P99 63.231 µs; the gateway's software
response is included, so those values are functional evidence, not a fixed
network-cost calibration. Each test restored the data VF. A final short test
also checked exact IPv4/broadcast, MAC and MTU restoration.

Raw CSVs, summaries and the standalone `client-calibration.pdf`/`.png` are under
`out/benchmark-zs07/`; remote originals are under
`/scratch/pengxu/dpdk-benchmark/`. CSV hashes and compact distributions are
retained in the tracked ledger. Different sample counts and both CPU isolation
and telemetry changes distinguish the initial and final configurations; this
is not an isolated causal estimate of the effect of one kernel option.

## Bring-up fixes and validation

- The golden `6.8.0-64` client kernel returned EACCES/EIO in VFIO PCI device
  open/info. Updating only this client's kernel to `6.8.0-146` fixed access.
  Original and intermediate PXE entries are preserved under the scratch kernel
  directory. Both full verified cold boots completed.
- Stock ThunderX PMD TX reached the gateway, but RX counters remained zero.
  Linux PF `SET_XCAST` applies the CAM list and ignores its MAC argument.
  The PMD patch sends `ADD_MCAST` first and uses mailbox mode bits. Physical
  gateway echo and hardware loopback passed after this change.
- Legacy hugepage allocation avoids the physically contiguous DMA-ring allocation
  failure seen with the default dynamic allocator. VFIO mode and hugepage count
  are restored after each run; only the data VF is rebound.
- Native x86 and AArch64 Nix packages built with pinned DPDK 26.03. The final
  package expressions were built directly against pinned nixpkgs after full
  flake snapshotting exhausted local storage. An earlier unpatched native
  flake build passed. No hardware CI was launched.
- Native Enzian build and wire unit checks passed. Five actual DPDK pcap replay
  cases passed on both the patched Enzian and final x86 package: add, multiply,
  incorrect result, stale ID and timeout. Eight trace-accounting tests and five
  summary tests passed, including a complete overlapping-DMA trace fixture.

## Reservations and remaining campaign

Unused generated-volume reservations **zuestoll03 and zuestoll04 were released**.
03 had an attached console and 04's BMC console was unavailable; neither was
repurposed. 07 was retained through client validation, then released below.

An accidental unused reservation of **zuestoll10** selected the same named root
volume as 14. It was never booted or mounted by this campaign. Read-only backend
inspection confirmed both reservations reference the same target
`iqn.2022-05.systems.enzian.zuestoll:pengxu-lauberhorn-linux-6.8`.
Named LVs survive release, but release removes the shared export configuration
and attempts to delete that target without checking other reservations.
Automatic approval review initially blocked release while 14 had to remain
reserved. The maintainer subsequently explicitly authorized releasing **all**
zuestolls, including 12/14, superseding that preservation constraint.

After accepted graceful shutdown requests on 07/12/14 and their management
interfaces stopped responding, normal EMG release removed reservations for
**14, 10, 12 and 07**. A fresh listing confirmed **zero pengxu reservations**.
Generated root volumes for 07/12 were deleted by EMG; the named
`lauberhorn-linux-6.8` volume was retained. EMG removed its export configuration,
but `tgtadm` reported the target was still active and refused target deletion
on both releases. Reservation removal nevertheless completed. No named-volume
deletion or reacquisition was performed to force export cleanup. Raw benchmark
artifacts remain on shared scratch and in the local ignored output directory.

Future measurements require new client and DUT reservations; the generated
client root volume is no longer retained. Recreate it from the documented recipe.
Once available:

1. Acquire a **fresh generated root volume** and stage a complete matched image,
   bitstream, LTX and trace map; corrected 516112 is the documented current
   acceptance baseline. Use the hardware-test helper and preserve any failed
   probe trace before reset.
2. Start one isolated `microbenchmarks add` worker with a fresh server CSV
   (`LAUBERHORN_SERVER_TRACE_CSV`, or the application's third argument). Keep
   tracing active through module load, then capture a bounded loss-free workload
   with one outstanding request and all warmup rows retained.
3. Run the DPDK client on the newly reserved client with the DUT's exact IP/port/MAC. Freeze tracing,
   restore the client's kernel VF, and collect F_MAC1 trace DDR using the
   [CMAC-first procedure](tracing.md#default-agent-capture-pipeline). Preserve
   artifacts and topology with every run.
4. Require complete request correlation and nonnegative conserved partitions.
   Report per-request external residuals, raw/corrected E2E, client software,
   CPU TX-call time, FPGA commit→submit and commit→CMAC distributions.
5. Repeat with matched historical artifacts. The June 20 CSV-only baseline has
   corrected E2E P50 73.320 µs and CPU TX-call P50 2.580 µs, but no authenticated
   historical trace map has been found. It cannot by itself prove the TX change.
   Isolating R12→RA3 from all intervening changes needs a controlled image pair.
