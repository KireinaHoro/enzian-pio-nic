# Paper charts

`paper_chart.py` is the shared plotting library. `figure()` creates one axes at
one ACM sigconf column width; `save()` writes a vector PDF with a fixed canvas.
Geometry is obtained from the installed `acmart` class after `\maketitle` (before
that point acmart still reports a full-width column). Typography is Libertine
with New TX math. Axis text is 9 pt, ticks and legends 8 pt. The paper class is
`\documentclass[sigconf,screen,10pt,dvipsnames]{acmart}`. No captions, titles,
footnotes or annotations are emitted. The default aspect ratio is 0.68.

The reusable skill is [paper-chart](skills/paper-chart/SKILL.md). Its installed
copy lives at `~/.codex/skills/paper-chart/SKILL.md`; keep it in sync with this
tracked source when updating the skill.

From the platform root:

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf
# Once all four worker-pool measurements are populated in the manifest:
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
It prints the CDF sample counts and percentiles. All measured latency samples
are included, including tails; RTT is the raw client exit-minus-entry interval.
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
   gzip files in Git. Commit its source plan and the manifests too.
7. Render all five PDFs with `plot_nullrpc.py --charts all`, check the numeric
   summaries and visuals, and commit the PDFs in the paper repository.

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
