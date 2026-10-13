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
# Once the load measurements are populated in the manifest:
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
