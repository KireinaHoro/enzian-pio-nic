# Paper charts

`paper_chart.py` is the shared plotting library. `figure()` creates one axes at
one ACM sigconf column width; `save()` writes a vector PDF with a fixed canvas.
Geometry is obtained from the installed `acmart` class after `\maketitle` (before
that point acmart still reports a full-width column). Typography is Libertine
with New TX math. Axis text is 9 pt, ticks and legends 8 pt. The paper class is
`\documentclass[sigconf,screen,10pt,dvipsnames]{acmart}`. No captions, titles,
footnotes or annotations are emitted unless requested. The default aspect ratio is 0.68.

The project-independent [paper-chart skill](../../../../skills/paper-chart/SKILL.md)
is tracked in `~/work-local/skills`. The installed `~/.codex/skills/paper-chart`
links to that source. This page defines the Lauberhorn-specific chart contract.

Import `tools/paper/paper_chart.py` before pyplot. Track source observations and
provenance under `data/paper/`, plotting and acquisition scripts under
`tools/paper/`, and the active chart inventory in `tools/paper/charts.json`.
Track emitted paper PDFs in `../paper/plots/`; retain active figures until their
replacements are validated. Check the paper source for actual TeX inclusion.

Use the [hot measurement contract](../research/hot-measurement.md):
unloaded totals are paired Tofino wire durations; loaded latency is client RTT.
Software-only readiness data with null wire durations cannot supply a paper CDF
or CPU–wire residual. Preserve historical measurements' original boundaries.
For acquisition, follow the platform hardware-test and failure-preservation
rules. Distinguish requested worker pools from observed active workers and
resolve cold-workload semantics before collecting a dependent curve.


Comparison charts use **DPDK**, **CS-A64**, and **LH** for the DPDK control,
Caladan Simple AArch64 baseline, and Lauberhorn, respectively. Define CS-A64
once in the experimental setup using the [baseline description](../research/caladan-enzian.md#selected-setup).
The ARM/MLX5 port has partial native mechanism acceptance; include its series
only after the benchmark contract has been accepted.

## Active echo measurements

Use the [canonical campaign workflow](benchmark-campaign.md)
and [benchmark-campaign skill](../../tools/paper/skills/benchmark-campaign/SKILL.md). Both loaded
and unloaded workloads are schema-defined 8/128/1432-byte echo RPCs.

| Figure | Software probes | Capture | Boundary |
| --- | --- | --- | --- |
| Unloaded CDF | Compiled out | Tofino | Switch request-TX to reply-RX MAC interval |
| Unloaded breakdown | Enabled | Tofino | Switch request-TX to reply-RX MAC interval |
| Loaded latency/goodput | Compiled out | Disabled; no FPGA trace readout | Client RTT |


From the platform root:

```sh
nix develop -c python3 tools/paper/plot_echo.py --input CAMPAIGN/loaded.json
nix develop -c python3 tools/paper/plot_echo_unloaded.py --input CAMPAIGN/full-unloaded.json
```

Default output is `../paper/plots/`, independent of working directory. Use
`--output out/paper-preview` for temporary renders. The Nix development shell
supplies Python, NumPy and Matplotlib; TeX Live supplies `pdflatex`, `acmart`,
`libertine` and `newtxmath`. Missing fonts or classes fail explicitly.

The paper's requested filenames are:

- `echo-unloaded-hot-latency-cdf.pdf`
- `echo-unloaded-hot-latency-breakdown.pdf`
- `echo-loaded-hot-latency-goodput.pdf`
- `echo-loaded-hot-peak-throughput-workers.pdf`

`echo-overload-loss.pdf` retains the complete overloaded grid for inspection,
including 100% deadline-loss points with no replies. The latency/goodput curve
stops at the contiguous prefix meeting the 1% loss limit in every repetition.
When an overloaded point has been observed, a vertical upward arrow from the
last qualifying point, labelled infinity, marks that stop. Its x position is
the sampled endpoint, not an interpolated capacity threshold; later recovery
does not reconnect the curve.

Loaded percentiles use the inverse empirical CDF over **all transmitted
requests**: rank `ceil(p × transmitted / 100)`. On-time successful replies
supply finite RTTs; missing replies, deadline-late replies and unsuccessful
rejections occupy the infinite tail. Thus P99 is finite at exactly 1% deadline
loss and infinite above 1%. Pool repetitions by summing both RTT counts and
transmitted counts. The stop policy still applies to the worst repetition, so
it can stop a curve even when pooled P99 remains finite. Generator/admission
failures invalidate a trial rather than entering this server-loss population.
Trial statistics encode infinite quantiles as JSON `null` with an explicit
`p99_infinite`/`median_infinite` flag. Statistics retain the first excluded rate,
loss and all excluded points' goodput/loss; zero-reply trials have infinite P99.
The unloaded comparison uses the tracked [18-cohort archive](../../data/paper/echo/unloaded-20261021/README.md)
for LH, DPDK and CS-A64. Regenerate it with `--input data/paper/echo/unloaded-20261021/unloaded.json`.
Both unloaded PDFs are included by the current paper source. Loaded collection is incomplete.
The inventory is [charts.json](../../tools/paper/charts.json). Do not replace active PDFs with
rehearsal or synthetic inputs. Existing measurement archives retain their
original workload and provenance; use the echo campaign for new acquisition.

## Validation and iteration

Use `pdfinfo`, `pdffonts` and `pdftoppm` for dimensions, embedded fonts and
visual review. A default single-column chart is 240.247 PDF points wide.
Never crop with `bbox_inches="tight"`. Keep measurement boundaries explicit;
never label client RTT as MAC latency or assign instrumented components to an
uninstrumented total. Preserve original observations and hash their provenance.

Commit implementation, acquisition tooling and measurement provenance normally.
Leave chart layout, presentation and PDF iteration uncommitted until requested.

For the completed 8-byte hot rerun comparison, use
`--input data/paper/echo/unloaded-8b-20261021/unloaded.json --payload 8 --output out/caladan-8b-chart-update`.
This standalone review retains historical LH/DPDK and fresh Caladan provenance;
it does not replace the full-payload paper figures while collection continues.

The unloaded breakdown displays `rpc_receive + rpc_transmit` as **RPC (un)marshal**
and `cpu_other` as **Runtime/dispatch**. New accounting version 3 measures the
codec calls separately from allocation and TX-buffer reservation, and retains
runtime subintervals including Caladan dispatch wait. See the
[exact timestamp and segment contract](rpc-breakdown.md). Historical versions
1/2 use broader RPC spans containing wrapper/dispatch work. Comparisons using
version 3 require version 3 for every breakdown cohort. The split preserves the
same central observations and total instrumented median.
