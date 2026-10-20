# Measurement continuation

## Echo contract and artifact constraints

Active paper microbenchmarks use schema-defined echo RPCs at 8/128/1432
application bytes, loaded and unloaded. Follow the [canonical contract](../docs/development/benchmark-campaign.md)
and [campaign skill](../tools/paper/skills/benchmark-campaign/SKILL.md).

| Measurement | Software probes | Collection | Reported interval |
| --- | --- | --- | --- |
| Unloaded CDF | Compiled out | Tofino server-facing MAC pairs; FPGA recording frozen, NIC PTP off | Wire-to-wire |
| Unloaded breakdown | Enabled | Separate Tofino cohort; FPGA recording frozen, NIC PTP off | Wire-to-wire plus CPU durations and combined residual |
| Loaded | Compiled out | No switch capture, FPGA readout or NIC PTP | Client RTT |

Follow the [wire methodology](../docs/research/hot-measurement.md). The older
FPGA-MAC/PTP acquisition contract applies only to its recorded campaigns below.
Full collection requires hash-bound real-workload wire acceptance; inspect that
receipt before launch rather than treating a dated blocker as live status.

Use the paper's commented echo filenames for replacements and retain active PDFs until validated
replacements exist. Leave presentation changes uncommitted as instructed;
check their actual worktree before editing. Recheck reservations and
[hardware state](hardware-state.md) before board access.

## Echo validation evidence

The campaign guide at `a19331dc:docs/development/benchmark-campaign.md` records
the following compact-observation validation (no new acquisition was run during
this documentation migration):

Targeted board validation passed nine 1-second-warmup/1-second-measured cohorts
(seven DPDK, two LH), with no generator misses, admission failures, malformed
replies or health/restoration failures. At 6,568,491 RPC/s, the fresh detailed
control took 358 seconds including import and retained 411 MB; two aggregate
trials took about 85 seconds and retained 20 KB each. All requests were on time.
Detailed median/P99 were 72.58/122.55 microseconds; aggregate medians were
73.25–73.55 and P99s 123.55–125.25 microseconds. These short comparisons establish
similar behavior and export savings, not statistical equivalence or sustained
peak capacity. The LH overload check accounted for all 256,292 transmissions as
176,487 successes plus 79,805 explicit rejections, with a 78.55 microsecond P99,
matching the earlier detailed P99. Original raw evidence and artifact identities
remain in `out/echo-redesign/histogram-resolution/`; `comparison-summary.json`
records the diagnostic scope and the final maximum-XID stride correction.
Historical detailed rehearsal cohorts remain eligible for explicit reviewed
reuse; full paper acquisition will use fresh aggregate observations.

The same guide records local controller and rendering fixtures:

The local service-crash fixture exercised an actual SIGKILL and ExecStopPost
notification delivery to a file-backed sink. All five plots also passed portable
synthetic-input rendering, exact PDF dimensions and embedded-font checks. These
fixtures are excluded from paper inputs and are not board acceptance.

On resumption, inspect the preserved validation artifacts and current frozen
configuration before selecting reuse or fresh acquisition. These fixtures do
not substitute for real-workload switch acceptance. Dated boot placement is in
[hardware state](hardware-state.md#october-18-echo-topology-observation).

## Recorded latency evidence

- October 5 buffered DPDK hot/cold cohorts: 4,000 measured RPCs; kernel-off RTT
  medians 12.24 µs hot / 43.36 µs cold. Hot CPU-work analysis and reproducible
  static counting are recorded in the [consolidated evidence](../docs/research/roadmap.md#current-status--october-8-consolidation)
  and [516232 comparison](../docs/research/latency-measurement.md#corrected-module-516232-comparison--october-5).
- October 10 inline-TX correction on unchanged 518689 hardware: 4,000 measured
  hot/cold RPCs plus 32 warmups, one control forward/ack and no overflow
  invalidation per measured TX. Kernel-off hot RTT 12.760 µs; MAC latency
  4.165 µs versus old 3.700 µs baseline. Conserved FPGA-clock comparison
  attributes about 63 ns mean growth outside host turnaround and 402 ns to
  host/link turnaround. [Measurement and comparison](../docs/research/latency-measurement.md#inline-tx-and-pre-doorbell-probe-measurement--october-10).
- October 11 pipeline 518836 passed all gates and two matched RPC cold boots.
  Joined memcached RTT tests passed 1,536 traced requests and an identical
  tracing-off control, following an earlier 1,536-request traced cohort.
  Software medians were about 12 µs RX-to-response preparation and 2.7 µs TX
  preparation. Python generation dominated client RTT. Recorder timestamps
  after an input FIFO distort burst intervals without sample loss.
  [Evidence and limitation](../docs/research/memcached.md#first-joined-rtt-breakdown-and-recorder-limitation--october-11).
- The vanilla Linux FPGA-bypass/netdev comparison passed 384 traced and 384
  control requests. Sustained GET MAC p50 was 74.65 µs versus native 18.97 µs,
  subject to the same legacy timestamp limitation. This is not a CPU-NIC
  baseline. [Comparison](../docs/research/memcached.md#vanilla-linux-mac-comparison--october-11).

## Remaining checks

Pipeline 518922 (`cc19c428`) corrects the recorder and passed two matched
cold-boot RPC trials (200 calls), with zero recorded trace loss markers.
[Acceptance](../docs/research/memcached.md#corrected-recorder-preflight-and-board-acceptance--october-11)
does not supply corrected memcached burst measurements.

On resumption, collect matched memcached measurements with the corrected
recorder before precise burst-breakdown claims, and establish the system-event
CDC timestamp uncertainty bound. Use RAM-backed peer output: the five-instance
extension stopped at 2,688 requests on client ENOSPC and is a failed campaign,
with frozen trace and software records preserved. Full pool-size/performance
measurements require separate acceptance; the finite switching results are in
[runtime/application context](runtime-applications.md).
