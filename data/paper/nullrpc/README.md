# Null-RPC paper measurements

## Active figures

The [inventory](../../../tools/paper/charts.json) contains three figures, all
currently included in `evaluation.tex`: the hot/cold MAC CDF, hot MAC
throughput–latency, and hot worker scaling. Cold throughput–latency is retired.
The CDF and scaling retain pipeline 518922 measurements; the hot throughput
curve now uses the October 14 paced sweep on pipeline 519555. These are separate
hardware revisions, not a single-revision campaign. Chart iterations remain
uncommitted until the user requests a commit/push.

## October 13 CDF/scaling and retained acquisition: pipeline 518922

The October 13 acquisition used matched hardware/runtime
`cc19c4285148db65c5b5517f6a02abd04b7f9142` (pipeline 518922). This was the latest
successful hardware build at acquisition; the read-only CI query is retained.
The client source is `4e1372c0`; its deployed SHA-256 is
`9da72e4c465e992326898e7339d3da306eb7a80992796acdf560efc83daf98fa`.
No FPGA, kernel or server runtime implementation was changed for these runs.
Timing closure is not implied.

Across four matched cold boots, the campaign passed **185,000 measured requests,
2,304 warmups and 400 setup calls**, plus 160,000 validated eviction-preparation
RPCs. Every requested worker served measured requests in every scaling trial.
All four complete captures have matching request/reply counts and zero stored
loss markers (347,704 RPCs including preparation). The final runtime has four
resident workers on CPUs 44–47; traces are frozen and the one-shot runtime export
is consumed. Recheck reservations before reuse.

The one-worker campaign passed **95,000 measured requests + 1,152 warmups**,
following a 100-RPC setup check. The runtime exported all **96,252** rows with
zero drops. Hardware health checks found no TAD interrupt/timeout or new kernel
fault. The complete frozen trace contains exactly 256,252 admitted RPCs and
normal replies (including 160,000 eviction-preparation RPCs), with zero stored
loss markers. Kernel instrumentation was off; runtime buffering and FPGA tracing were
on. The DUT used Linux 6.8.0-64-generic and the DPDK peer 6.8.0-146-generic.
The static peer neighbor is required because the DPDK client does not answer ARP.

- Hot CDF: all 10,000 measured requests with one outstanding request. MAC-to-MAC
  p50/p99 is **4.145/4.190 µs**; the maximum **11.225 µs** remains included.
- Cold CDF: 1,000 measured requests with eviction preparation before each RPC.
  MAC-to-MAC p50/p99 is **35.085/35.76505 µs**, maximum **98.495 µs**.
- Retired window-only hot throughput sweep: six closed-loop windows, 1/2/4/8/12/16 outstanding
  requests, each with 128 warmups and 10,000 measured replies. Achieved throughput
  rises from **75.765** to approximately **156.136 kRPC/s**. These inputs remain
  evidence, but `paper_throughput: false` excludes them from the active plot.
- The cold throughput–latency figure is retired. Its five cold load cohorts and
  eviction-preparation records remain acquisition evidence; only the cold CDF is
  an active latency figure.
- Worker scaling: three 10,000-request cohorts per pool size at window 16, with
  128 warmups per cohort. All four pool sizes passed. Median trial throughputs are
  **156.135, 311.762, 466.850, and 624.687 kRPC/s** for 1–4 workers.
  The plot requires every requested worker to serve measured requests, each in
  one resident activation on a distinct CPU. Its center is the median trial
  throughput and error bars show the full observed trial range, not confidence
  intervals.

These are finite, single-client closed-loop measurements. They do not establish
open-loop queueing/SLO behavior, avoid coordinated omission, or independently
establish that a throughput plateau is server-limited. Each worker-pool size
uses one cold boot; repeated cohorts on that boot are not independent boots.
CDFs and load points share the latest hardware. Current charts use MAC-to-MAC
latency, `TxCmacExit - RxCmacEntry`, in the FPGA's 5 ns clock domain with matched
source pipeline compensation. The corrected 518922 recorder timestamps before
its batch FIFO. The raw selected MAC/protocol samples, map, derived per-request
CSV and `mac-provenance.json` allow regeneration without board access. All 95,000
measured one-worker requests have paired intervals. One setup request and one
excluded warmup lack normal-path TX packet markers; no measured samples are
omitted. The whole-capture join accounts for all 256,252 RPCs, including eviction
preparation. MAC/Ethernet streams are paired in order with equal counts, followed
by live RPC/packet identifier matching. A single affine clock fit places every
matched interval within its client call bounds; that fit does not alter latency.

Client RX-observation RTT remains available in the original CSVs as
`client_rx_burst_ns - client_call_enter_ns`. It is retained as acquisition evidence
and a causal bound, not used as MAC-to-MAC latency. Throughput retains its original
client elapsed-window definition, including cold preparation and pacing.

`manifest.json` pins input hashes and observation windows. Each `.csv.gz` stores
verbatim client or runtime rows with deterministic gzip metadata. Per-run
`acquisition/` records retain runner status/restoration, preparation state,
observation-window accounting, acquisition plans and hardware/client provenance.
Import uses all successful measured rows, rejects failed runs/duplicate IDs and
joins each drained cohort to its runtime requests; no percentile reconstruction
or tail clipping is used. The hardware capture stays in ignored output storage,
with its hash/status tracked; the compact MAC event subset is retained with the
paper data so current charts regenerate without the full capture.

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf
nix develop -c python3 tools/paper/plot_nullrpc.py --charts throughput
nix develop -c python3 tools/paper/plot_nullrpc.py --charts all
```

PDFs go to `../paper/plots/`. Chart iterations remain uncommitted until explicitly
requested; the [active inventory](../../../tools/paper/charts.json) records use. Acquisition/build/import
instructions are in [the plotting workflow](../../../tools/paper/README.md).
The exact hardware setup and ordered load plans are under `acquisition/`.
Recheck reservations, use new output labels and preserve existing traces before
replaying any plan. The original missing-neighbor pilot and a 64-outstanding
capacity-overload warmup remain documented in `acquisition/failure-provenance.json`;
neither contributes a plotted sample. The latter full frozen capture is preserved
with checksum, and its bounded burst has 64 RX, 22 normal RPC admissions/replies,
and 64 TX events, consistent with context-capacity rejection.

## Preserved October 10 latency inputs

`hot-latency.csv` and `cold-latency.csv` are verbatim client files from
`out/nullrpc-inline/hot-off2.csv` and `out/nullrpc-inline/cold-off.csv`.
Both contain 1,000 successful measured requests; hot additionally contains 16
marked warmups, while cold has none.
The manifest pins their SHA-256 hashes. No raw samples were reconstructed from
percentiles. These historical figures use the original full-call endpoint, so
their numbers are not a like-for-like improvement comparison with the current
RX-observation CDFs. RTT is `client_call_exit_ns - client_call_enter_ns`, converted from
nanoseconds to microseconds; no clock-overhead subtraction is applied.

The acquisition ran on October 10, 2026, using pipeline **518689**, hardware
`45cbfe52`, software `8a1521ee`, one measured add worker, and a separate four-worker
mul domain for cold preparation. Kernel tracing was off; buffered runtime and
FPGA tracing remained on. The workload uses one outstanding request. These are
historical latency measurements, not measurements on the newest hardware and
not evidence of saturation throughput. The cold preparation completed two
16-request mul bursts before each measured add request, outside its timed RTT.
The existing analysis established one activation per cold request.

The [full report](../../eci/board-evidence/dpdk-inline-hot-cold-518689-20261010.json)
and [exact acquisition/build inputs](../../eci/board-evidence/dpdk-inline-hot-cold-518689-20261010-inputs/README.md)
retain deployment provenance, cold validation, failure recovery, instrumentation
and timing limitations. The pinned build recipe is
`tools/enzian/nullrpc_inline_build.nix`. Do not replay historical acquisition paths
on live services; use fresh labels and the reservation/preservation procedure in
`docs/development/hardware-test.md`.

The historical RTT-only CDF outputs and reproduction mode are retired; their
raw inputs and manifest remain measurement evidence. Current plotting requires
MAC evidence and does not relabel these historical RTTs.

## October 14 paced hot grid: pipeline 519555

The replacement hot throughput–latency chart uses matched hardware/runtime
`0e58f0582b6f0039ceeff2c4714ceb4451cca490` (pipeline **519555**), the newest
successful hardware build at the acquisition query. The validated client binary
and its source revision/hash are unchanged from the October 13 campaign.
`acquisition/grid519555-load-plan.json` contains 17 minimum request-start gap
settings plus an unpaced endpoint, all with a 16-request in-flight limit.
Each cohort has 10,000 measured requests and 128 excluded warmups.

All **180,000 measured requests** passed, with one observed worker in one resident
activation per cohort. Achieved rates cover **19.913–156.260 kRPC/s**, including
88.038/97.434/107.191/116.605/125.059/133.852/138.478/143.460/146.613/147.846/
148.609/151.881 kRPC/s. Requested pacing rates are not substituted for achieved
rates. Median MAC latency stays around 4.14 µs through 151.881 kRPC/s (P99
10.375 µs at that point), then reaches about 93.4 µs for the two saturated
window-16 cohorts. This is a bounded paced workload on one boot; it is not an
open-loop SLO experiment or an independently proven server-only limit.

The runtime snapshot contains **182,404 rows** (180,000 measured + 2,304 warmups
+ 100 direct-DPDK setup requests), zero drops. Every measured request has a MAC
interval; all intervals pass a single affine client-clock containment check.
The fit validates joins and is not subtracted from MAC latency. The complete
2,272,273,472-byte frozen capture has exactly 182,404 RPC admissions and replies,
zero stored loss markers, and SHA-256
`2e368c94399761284eb355fe24ae9fe56ce8f9fe632fa8cdbd46b4191bbc59ee`.
Independent JTAG reads of the first/final 4 KiB match. TAD/kernel health passes.
The compact verbatim event subset, matched trace map, raw client/runtime CSVs,
per-request MAC CSV and hashes are included alongside this document for Git.

The first new-hardware cold boot passed 100 gateway RPCs. A second boot required
a full-cold retry after missing CCPI initialization, then its gateway client
failed ARP resolution before any RPC admission. Its full frozen capture and
clean TAD/kernel health are preserved under `out/paper-hot-grid/`, with compact
provenance in `acquisition/grid519555-preflight-failures.json`. The measurement
boot then passed all ECI gates and 100 direct-DPDK RPCs, using the same explicit
MAC path as the sweep. No failed-attempt samples enter the chart. Timing closure
is not implied by this finite board result.

Regenerate from the archived compact data without hardware access:

```sh
nix develop -c python3 tools/paper/import_nullrpc_mac.py --campaign grid519555
nix develop -c python3 tools/paper/plot_nullrpc.py --charts throughput
python3 tools/paper/check_charts.py
```

The full acquisition/reproduction plan is `grid519555-provenance.json`; the
matched boot manifest, bitstream/map hashes, source pacing plan, acceptance
results, capture and health checks are under `acquisition/grid519555-*`.
Re-extraction from the preserved full capture uses:

```sh
nix develop -c python3 tools/paper/import_nullrpc_mac.py --campaign grid519555 \
  --trace out/paper-hot-grid/grid519555-dump/trace/trace-dram.bin \
  --map out/paper-hot-grid/hw/lauberhorn_trace_dma_map.json
```

The DUT remains on the one-worker 519555 service with tracing frozen and the
one-shot runtime export consumed. Temporary capture overrides are restored;
the peer's interface is restored. Recheck reservations and live state before
reuse. No commit or push was made during chart iteration.

## CPU-NIC baseline preflight — October 14

The reserved pair remains zuestoll14 (DUT) and zuestoll07 (generator).
The repository DPDK program is a client, not a CPU-NIC RPC responder.
Read-only DUT inspection found `cpu40g1`, PCI `0002:01:00.2`, running `nicvf`
on kernel `6.8.0-64-generic`; `ethtool -T cpu40g1` reports software timestamps
only, no PTP hardware clock, and no hardware transmit/receive modes.
Pinned DPDK 26.03 `drivers/net/thunderx/nicvf_ethdev.c` likewise has no
`timesync_*` callbacks. These findings establish a missing measurement path,
not that the silicon cannot support timestamping with additional driver work.
Do not substitute client RTT or software burst timestamps for MAC latency.
The compact capability report is `acquisition/cpu-nic-timestamp-preflight.txt`.
Reproduce the read-only inspection with `uname -r`, `ethtool -i cpu40g1`,
`ethtool -T cpu40g1`, and `readlink -f /sys/class/net/cpu40g1/device` through
`tools/enzian/run.py` after verifying the reservations. No NIC rebind, service
restart, FPGA reprogramming, or new baseline experiment occurred in this preflight.

### PTP implementation follow-up

Read-only inspection on zuestoll14 confirmed `CONFIG_CAVIUM_PTP=m`,
`CONFIG_PTP_1588_CLOCK=y`, and `cavium_ptp` loaded as a dependency of `nicvf`.
`lspci -nn -d 177d:a00c` and `ls /sys/class/ptp` both returned no entries.
Thus the immediate missing clock is not explained by an absent module or
kernel configuration option. Firmware/device enumeration is a hypothesis,
not yet a proven cause. No register writes or rebinds were performed.
Full inspection output is retained in ignored `out/paper-thx-ptp-inspect/`.

The existing Linux implementation is reusable design/source evidence:
[2018 timestamping patch](https://lists.openwall.net/netdev/2018/01/15/123),
[PTP clock driver](https://codebrowser.dev/linux/linux/drivers/net/ethernet/cavium/common/cavium_ptp.c.html).
It configures RX through `NIC_MBOX_MSG_PTP_CFG` (0x19), handles the eight-byte
big-endian MAC timestamp prefix, enables queue-set TX timestamping, sets the
send descriptor timestamp bit, and consumes `CQE_TYPE_SEND_PTP` completions.
Only one timestamped TX packet may be outstanding; timeout/conflict completions
must invalidate the sample. This can fit the single-outstanding latency
experiment, but does not establish full per-packet loaded-throughput coverage.

Pinned DPDK 26.03 includes the descriptor fields but not the timestamp API,
mailbox configuration, or completion handling. Its current receive loop assumes
RX completions, so merely setting the TX timestamp bit is insufficient.
No ready-made ThunderX PMD implementation was located in the upstream source
or searched DPDK mailing-list records. This is a bounded port of known Linux
logic, not a claim that it is a configuration-only change. Ordinary UDP can
use the hardware mechanism; the Linux RX filter supports all packets.

Next validate PTP device exposure and the clock with the existing Linux path,
then port the required PMD pieces and check paired UDP RX/TX timestamps before
collecting paper data. The Enzian BDK Git repository could not be inspected:
the established SSH configuration returned `Internal API unreachable`.
No boot firmware changes were made. Actual timestamp placement, units, and
packet correlation still require validation on this board.

### Normal PCI enumeration check

On October 14, all four live ECAM controllers (`0x848000000000` through
`0x84b000000000`) were present, each with buses 0..31 and a 32 MiB
configuration-space aperture. Their compatible is `pci-host-ecam-generic`.
The first host's memory window `[0x802000000000, 0x808000000000)` already
contains the supplied PTP register address `0x807000000f00`.
No enumerated PCI resource covers that register and no `177d:a00c` function
exists. A standard `echo 1 > /sys/bus/pci/rescan` completed with the same 63
PCI functions before/after and no PTP clock. This rules out a missing containing
host memory window; it does not prove whether firmware hides the function or
whether direct non-secure MMIO would succeed. No raw MMIO or firmware changes
were attempted. Decoded DT and rescan evidence is
`acquisition/ptp-enumeration.json`; full normal enumeration output is retained
in ignored `out/paper-ptp-enumeration/` and `out/paper-ptp-pci-rescan/`.

### Direct read-only PTP probes

`tools/enzian/probe_thx_ptp.py` maps the documented BAR0 page through
`/dev/mem` with read-only permissions and performs individual 64-bit reads
in bounded child processes, stopping on failure. On zuestoll14, October 14,
CFG (`0x807000000f00`), CLOCK_LO (`...f08`), CLOCK_HI (`...f10`), and COMP
(`...f18`) all read zero. Repeated CFG and CLOCK_HI reads remained zero.
All six accesses completed; no clock progress was observed. Zero values are
consistent with an uninitialized/disabled block, but do not establish register
identity or distinguish that from read-as-zero access. No clock configuration,
compensation, PCI, or firmware writes were made during these probes.
Raw values and health snapshots are in `acquisition/ptp-direct-probe.json`.
The harness preserved full before/after kernel logs and TAD snapshots in
`/dev/shm/lauberhorn-ptp-direct-20261014/results` on the DUT. Reproduce only on
a verified reservation with a new output directory, using the console harness
and Python script; the supplied address is specific to this CN88xx node.

### Controlled clock-enable test

The authorized October 14 write test demonstrated that the PTP counter is
accessible and functional at the fixed BAR address despite absent PCI
enumeration. `tools/enzian/test_thx_ptp_clock.py --enable-test --output NEW_DIR`
reads the enumerated reset-controller BAR's `RST_BOOT` as Linux does, deriving
800 MHz. It saves the PTP settings, writes CFG bit 0 and COMP
`(1000000000 << 32) / 800000000 = 0x140000000`, and samples CLOCK_HI.
Both writes read back exactly. CLOCK_HI advanced 102,968,453 ns over about
102,968,931 ns of host monotonic time; this is a coarse progress/rate check,
not a calibrated accuracy bound. The test restored CFG and COMP to their
original zero values and verified readback. Counter registers were never
written; elapsed counter state is not restored. No NIC timestamping was
enabled and no RX/TX timestamps have yet been acquired.
See `acquisition/ptp-enable-test.json` for values, restoration and health.
This resolves the earlier read-as-zero ambiguity for these registers. It does
not resolve PCI discovery or establish MAC timestamp placement/correlation.

### Linux CPU-NIC packet timestamp validation and watchdog follow-up

The subsequent fixed-address platform-device test exposed a Linux PHC and
reused the existing nicvf timestamp path on the data VF only. Correcting BGX1
RX_ID_MAP from packet-kind 0 to 4 resolved the mismatch with Linux's timestamp
header-skip configuration. Two RX-only cohorts passed 100/100 requests each,
with nonzero increasing hardware timestamps and independent client replies.
These are functional tests, not null-RPC paper measurements.

In the earlier incorrect-selector trials, TX requests reached hardware, but
SEND_PTP completions returned status 0x86 and
raw timestamp zero. Selecting TX timestamp interface 1 did not fix this.
The watchdog follow-up verified SEND_CFG 0x10009 and all eight SQ interface
selectors at 1. Increasing only the data watchdog to periods 8 and 15 still
produced timestamp timeouts despite delivered replies. Request-to-CQE log
delays grew to 1.082 ms and 134.249 ms, confirming the watchdog change takes
effect and suggesting a ~1 GHz watchdog clock (distinct from the 800 MHz PTP
reference). The original watchdog and packet-kind settings were restored;
kernel/TAD health passed. This rules out simply needing a longer watchdog in
these tested cases, but does not establish why timestamp return fails.

See `acquisition/ptp-packet-validation.json`,
`acquisition/ptp-watchdog-validation.json`, and the reproducible build/probe
instructions in `tools/enzian/thx-ptp/README.md`. No CPU-NIC DPDK null-RPC samples are available for the charts yet. The test driver remains loaded after
the watchdog follow-up to support further investigation; timestamp mode is off.

The descriptor follow-up (`acquisition/ptp-descriptor-validation.json`) confirms
live QS1 SEND_TSTMP_ENA=1 and a pre-doorbell header with TSTMP=1, DS=0, TSO=0,
PNC=1. PNC was already set by the Linux driver. A single-request trial after a
fresh test-driver load produced exactly one timestamp request and the same
0x86/zero-timestamp completion, despite client receipt. There is no overlap
between this probe's timestamp operations. Kernel/TAD health passed.

### Correct TX port selector and paired hardware timestamps

The CN88xx SQ TSTMP_BGX_INTF encoding is `(bgx << 2) | lmac`.
The DUT data NIC is BGX1/LMAC0, so the correct selector is **4**, not 1.
The corrected probe passed 10/10 paired RX/TX hardware timestamps and a further
100/100 with diagnostic logging disabled. SEND_PTP status is zero; the original
watchdog period 1 suffices. Evidence: `acquisition/ptp-port4-validation.json`
and `acquisition/ptp-port4-control-validation.json`. This resolves the earlier
TX timeout; no unsupported-hardware conclusion follows from those failures.
The Python responder is functional validation, not a DPDK latency baseline.

Reusable patches/package definitions now live in `../thunderx-dpdk-ptp`.
The platform exposes `thunderxPtpModules`, `thunderxDpdk`, and the
`checks.x86_64-linux.thunderx-ptp` check; deploy images carry the modules and
patched DPDK runtime. Kernel packet tests remain distinct from the packaged
DPDK implementation's build and synthetic completion-ring tests.
