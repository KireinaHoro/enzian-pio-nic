# Paper charts

`paper_chart.py` is the shared plotting library. `figure()` creates one axes at
one ACM sigconf column width; `save()` writes a vector PDF with a fixed canvas.
Geometry is obtained from the installed `acmart` class after `\maketitle` (before
that point acmart still reports a full-width column). Typography is Libertine
with New TX math. Axis text is 9 pt, ticks and legends 8 pt. The paper class is
`\documentclass[sigconf,screen,10pt,dvipsnames]{acmart}`. No captions, titles,
footnotes or annotations are emitted unless requested. The default aspect ratio is 0.68.

The reusable skill is [paper-chart](skills/paper-chart/SKILL.md). Its installed
copy lives at `~/.codex/skills/paper-chart/SKILL.md`; keep it in sync with this
tracked source when updating the skill.

From the platform root:

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf
nix develop -c python3 tools/paper/plot_nullrpc.py --charts all
```

Default output is `../paper/plots/`, independent of working directory. Use
`--output out/paper-preview` for temporary renders. The Nix development shell
supplies Python, NumPy and Matplotlib; the paper's TeX Live installation must
provide `pdflatex`, `acmart`, `libertine`, and `newtxmath` on PATH. Missing fonts
or class files fail rather than silently falling back. The paper repository
tracks emitted PDFs; platform tracks source data, provenance and scripts.

Check PDFs with `pdfinfo` and `pdffonts`; preview using `pdftoppm`. A single-column
chart with the current TeX Live 2025 class is 240.247 PDF points wide (3.337 in).
PDF points are 1/72 inch; the class reports TeX points at 1/72.27 inch. Do not crop
the PDF, because that would change its intended physical size.

The null-RPC script validates input hashes, success markers, request identity,
warmup and observation windows before computing distributions or throughput.
The separate hot and cold CDFs use the default 1.2 pt lines and
annotate P50, P99 and P100 MAC-to-MAC latency beside their markers. It prints the CDF sample counts and percentiles. All measured latency samples
are included, including tails; the manifest selects the exact recorded latency
endpoint. Lauberhorn latency uses paired hardware `RxCmacEntry` and
`TxCmacExit` events; the hot DPDK baseline uses paired ThunderX RX/TX MAC
timestamps. The plotter validates the baseline against its client IDs, success
markers, warmups and elapsed-time bounds. Throughput still uses the client observation window.
Client RTT is retained as acquisition evidence, not plotted as MAC latency.
Missing load measurements raise an error rather than producing invented points.
See [measurement provenance](../../data/paper/nullrpc/README.md).

## New load acquisition

1. Check live reservations and preserve existing traces with the hardware-test
   workflow. Confirm pipeline 518922 is still the newest available hardware;
   generated manifests pin that matched image and must be updated together if
   a later image is selected. Keep the raw hardware/software manifests and hashes.
2. Build `.#trafficGeneratorNative` and `.#trafficGeneratorDeployFs`; run both
   packet-replay scripts in the DPDK README. Stage the cross-built client closure
   on the reserved peer. A binary-only update is valid only when every linked
   runtime dependency is already present in the matched mounted client image.
3. Use `make_nullrpc_cases.py --workers N --label UNIQUE --output CASES.json`,
   inspect machine paths, and run `tools/enzian/test.py` with that manifest and
   a fresh log directory. This performs the required cold reset/ECI bring-up.
   A separate four-thread mul flush service is started only for the one-worker
   case. Kernel tracing is disabled; runtime row buffering stays enabled.
4. Stage `acquire_nullrpc_peer.sh` and a JSON plan at the gateway transfer URL.
   Each plan row names a fresh `label`, `count`, `warmup`, `window`, `gap_ns`, and
   `mode` (`hot` or `flush:1`). `run_nullrpc_plan.py --plan-url URL --acquire-url
   URL --transfer-url URL --logs NEW_DIR` executes the sequence through the
   repository console harness and stops at the first failure. The acquisition
   script uses fresh peer RAM directories and exports each result as a tarball;
   it never retries a failed data point. These scripts currently contain the
   recorded zuestoll14/07 addresses and the campaign RAM base, so inspect/update
   those together for another reservation or campaign.
5. Export runtime rows **once**, after all traffic on that boot is drained, with
   `export_nullrpc_dut.sh LABEL PID SERVER_CSV TRANSFER_URL` through the DUT
   console harness. The exporter rejects an existing CSV/status file. Preserve
   hardware trace and health evidence before the next reset. Full raw logs stay
   in ignored output directories and are read only for failed-stage diagnosis.
6. Create an import plan documenting `label`, `hardware_revision`, `pipeline`,
   `server_csv`, `server_status`, `setup_count`, and ordered `runs`. Each run has
   `label`, `directory`, `condition`, `workers`, and `scaling`. Run
   `nix develop -c python3 tools/paper/import_nullrpc_load.py PLAN.json`.
   The importer joins each drained cohort to runtime requests, verifies success
   and distinct cold activations, and archives original CSVs as deterministic
   gzip files for Git. Keep its source plan and manifests with the data;
   commit only when explicitly requested.
7. Render all three PDFs with `plot_nullrpc.py --charts all`, check the numeric
   summaries and visuals, and leave the PDFs in the paper worktree for review.
   Commit and push only when the user explicitly requests it.

The hardware has 16 reply contexts. A 64-outstanding exploratory burst exceeded
that capacity and is preserved as a failed warmup, not a throughput measurement.
The intended successful sweep is bounded to at most 16 outstanding requests.
Client-side throughput limits and finite run duration must remain explicit in
provenance; a plateau alone does not establish server saturation.

For the recorded 518922 server environment, fetch hardware job **2886156** with
`tools/enzian/ci_artifacts.py --token-file ../.gitlab_token --download NEW_HW_DIR
2886156`, and rebuild the matched deployment using
`tools/enzian/build_deploy.py cc19c4285148db65c5b5517f6a02abd04b7f9142
--out-link NEW_IMAGE`. The accepted image SHA-256 is
`9140a9caf6448938247fbc74758fd31cf809b373e303d2f7d53788a726cd52f4`.
Use the source revision and matched collateral rather than assuming a rebuilt
image must reproduce that historical filesystem hash. Client source `4e1372c0`
contains the bounded load generator and failure-only diagnostic used here.
Keep the generated trace map and its hash with every raw capture.

`check_nullrpc_capture.py TRACE --map MAP --expected-rpcs COUNT --output JSON`
checks complete unwrapped captures for stored loss markers and exact admitted/
normal-reply counts. Count setup, warmup and flush traffic as well as measured
requests. This is acquisition validation, not cross-clock latency analysis.

For a fresh peer image, create the campaign RAM base
`/dev/shm/paper-nullrpc-20261013` and copy its matched
`/nix/store/lauberhorn-client/bin/bench_dpdk` there. The recorded acquisition
scripts use that base; keep it or change the base consistently. Fresh run labels
prevent overwriting prior results. To serve only staged scripts/plans and receive
small result archives on the gateway, use:

```sh
python3 transfer_results.py --root /tmp/NEW_CAMPAIGN \
  --bind 192.168.255.254 --port 18843
```

Stage the tool and intended files in that dedicated directory first. Uploads are
bounded and refuse overwrites; stop the temporary server when finished. Download
named result archives with `fetch_nullrpc_runs.py PLAN --destination NEW_LOCAL`.
Keep raw captures and failures in ignored storage, and import only validated
cohorts into paper data.

## Iteration and active chart inventory

Do not commit or push chart tweaks without an explicit request. The authoritative
[chart inventory](charts.json) records every active PDF, its script, data and
regeneration command, and whether it is actually included in the paper. Keep it
in sync with TeX inclusions; pending figures are not described as already used.
Delete superseded PDFs and scripts, including stale previews; retain shared
scripts used by other active figures and original measurement evidence.

```sh
python3 tools/paper/check_charts.py
```

MAC evidence is reproducible from the compact, verbatim trace samples and matched
map prepared for Git alongside the per-request latency CSV:

```sh
nix develop -c python3 tools/paper/import_nullrpc_mac.py
nix develop -c python3 tools/paper/plot_nullrpc.py --charts all
```

To re-extract those samples from the original preserved capture:

```sh
nix develop -c python3 tools/paper/import_nullrpc_mac.py \
  --trace out/paper-microbenchmarks/w1-dump/trace/trace-dram.bin \
  --map out/trace-recorder-518922/hw/lauberhorn_trace_dma_map.json
```

The importer validates the capture/map hashes and loss markers, pairs the full
MAC/Ethernet streams in packet order, follows live RPC and packet IDs, accounts
for setup/warmup/eviction traffic, and checks all MAC intervals against client
call bounds under a single affine clock mapping. The affine fit validates the
join only; MAC latency is the direct same-clock interval at 5 ns per tick.

## Hot throughput grid

Throughput–latency is a hot-only chart. Varying only the outstanding-request
window jumps from approximately one-client-RTT throughput to saturation; use
request-start pacing with enough in-flight capacity to sample the intervening
rates. `make_hot_load_plan.py` emits 18 bounded cohorts (17 target rates plus an
unpaced endpoint), 10,000 measured requests and 128 warmups each, window 16.
The grid targets 20–140 kRPC/s, then 145/150/152/154/156/160/180 kRPC/s.
The configured gap is a minimum interval, not a guaranteed offered rate; plot
successful completions divided by the recorded elapsed window. This remains a
bounded paced workload, not an open-loop SLO experiment.

```sh
python3 tools/paper/make_hot_load_plan.py --prefix CAMPAIGN --output PLAN.json
```

For a fresh peer RAM directory, pass `--peer-base /dev/shm/CAMPAIGN` to
`run_nullrpc_plan.py`. After draining/exporting the runtime, preserve the entire buffer through the
[canonical trace workflow](../enzian/trace/README.md):
`python3 tools/enzian/trace_capture.py capture --config TRACE.json --output NEW_DIR`. The transfer endpoint and reserved peer must
remain available until the capture has been validated. Import the validated
client/runtime plan, retain the complete capture check under
`data/paper/nullrpc/acquisition/CAMPAIGN-trace-check.json`, then run
`import_nullrpc_mac.py --campaign CAMPAIGN --trace RAW --map MATCHED_MAP`.
Subsequent MAC regeneration needs only `--campaign CAMPAIGN`; per-cohort MAC
provenance allows different campaign revisions without relabeling old CDF data.

The latency CDF command now emits separate hot and cold PDFs. The hot chart
compares Lauberhorn with the CPU-NIC DPDK MAC timestamp samples in
`data/paper/nullrpc/dpdk-hot/` (see the data README). The combined CDF is
retired. Run `python3 tools/paper/check_charts.py` after regeneration.

## Median MAC breakdown

```sh
nix develop -c python3 tools/paper/plot_nullrpc_breakdown.py
```

This emits `nullrpc-hot-latency-breakdown.pdf`: two horizontal stacked bars for
Lauberhorn and DPDK, sharing handler, RPC runtime, network stack and CPU–MAC
categories. Components come from the two central MAC-ranked requests, so each
bar sums to the actual sample median; marginal component medians are not summed.
The DPDK bar is a separate profiled cohort, with its probe-off control preserved.
[Boundary definitions and reproduction](../../data/paper/nullrpc/breakdown/README.md)
explain included runtime/probe overhead and the combined CPU–MAC residual.
The chart is included in `evaluation.tex`.
