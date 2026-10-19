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


Comparison charts use **DPDK**, **SH-A64**, and **LH** for the DPDK control,
Caladan-derived AArch64 baseline, and Lauberhorn, respectively. Define SH-A64
once in the experimental setup using the [baseline description](../../docs/research/caladan-enzian.md#implementation-scope).
The port and measurements are pending; add its series only when accepted data
are available. 

## Active echo measurements

Use the [canonical campaign workflow](../../docs/development/benchmark-campaign.md)
and [benchmark-campaign skill](skills/benchmark-campaign/SKILL.md). Both loaded
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

`echo-overload-loss.pdf` retains the complete overloaded grid for inspection.
The inventory is [charts.json](charts.json); commented TeX inclusions remain
pending until validated replacements exist. Do not replace active PDFs with
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
