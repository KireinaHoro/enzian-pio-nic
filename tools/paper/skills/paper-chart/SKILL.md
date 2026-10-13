---
name: paper-chart
description: Create reproducible Lauberhorn paper charts from measurements, using the shared ACM plotting library and Git-tracked data and plotting scripts. Use for paper microbenchmark and evaluation figures.
---

# Paper charts

Work from the Lauberhorn platform repository. The reusable library is
`tools/paper/paper_chart.py`; establish or update that library before emitting
individual plot scripts. Import it before pyplot. It probes the installed
`\documentclass[sigconf,screen,10pt,dvipsnames]{acmart}` for exact geometry
and uses Libertine and New TX math through LaTeX.

Default to one chart on one column. Save fixed-canvas vector PDFs; never use
`bbox_inches="tight"`, which changes physical dimensions. Use readable axis
labels with units and legends as needed. Do not add titles, captions, footnotes,
sample-count callouts, reference lines, or other annotations unless requested.
Put measurement qualifications in tracked provenance, not inside the chart.

Emit an executable plotting script that bridges the actual acquisition format
to the chart: load and validate source rows, exclude explicitly marked warmup,
compute the requested statistics, and render with the shared library in one
documented command. Do not leave manual spreadsheet conversions or invented
aggregate inputs between analysis and plotting. Keep raw observations and
provenance under `data/paper/`, and scripts under `tools/paper/`, tracked in
platform Git. Track acquisition commands/scripts, software/hardware revisions,
hashes, units, instrumentation, failure accounting and regeneration instructions.
Check timestamp placement across acquisition modes before comparing latency;
record the selected endpoints in the data manifest.
Small source datasets should be preserved verbatim; never manufacture samples
from percentiles. CDFs use every valid measured sample, without tail clipping.

Write emitted plots to `../paper/plots/` and track them in that repository.
Inspect its instructions and existing modifications first. Do not commit others'
edits or add figure inclusion/caption text unless requested. The default output
location must be relative to the script's repository, not the caller's directory.

Run plotting via `nix develop` and validate the actual PDF dimensions, embedded
fonts, plotted statistics and visual readability. Rendering success alone does
not validate data. Keep previews and caches in ignored output directories.

For missing measurements, follow the platform hardware-test and preservation
rules. Reuse historical data only with its original provenance. Never relabel it
as a current run or use reciprocal latency as saturation throughput. Throughput
requires successful completions divided by the recorded elapsed observation
window, with preparation time explicitly included or excluded by the workload
contract. Distinguish requested worker pool size from observed active workers.
Resolve ambiguous cold-workload semantics before acquiring a dependent curve.
If a measurement is blocked, preserve the runnable pipeline and explain the
missing evidence; do not render placeholder points as results.
