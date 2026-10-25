"""Shared ACM sigconf paper geometry and typography. Import before pyplot."""
from pathlib import Path
from functools import lru_cache
import os
import subprocess
import tempfile

os.environ.setdefault("MPLCONFIGDIR", "/tmp/lauberhorn-paper-matplotlib")
import matplotlib

matplotlib.use("pgf")
import matplotlib.pyplot as plt

DOCUMENTCLASS = r"\documentclass[sigconf,screen,10pt,dvipsnames]{acmart}"
PREAMBLE = r"\usepackage[T1]{fontenc}\usepackage{libertine}\usepackage[libertine]{newtxmath}"
COLORS = ("#0072B2", "#D55E00", "#009E73", "#CC79A7")


@lru_cache(maxsize=1)
def dimensions():
    """Ask the installed paper class, rather than assuming a nominal column."""
    with tempfile.TemporaryDirectory(prefix="paper-geometry-") as directory:
        root = Path(directory)
        (root / "geometry.tex").write_text(
            DOCUMENTCLASS + "\n" + r"\begin{document}\title{Geometry}\maketitle" + "\n"
            + r"\typeout{PAPER-COLUMN=\the\columnwidth}" + "\n"
            + r"\typeout{PAPER-TEXT=\the\textwidth}" + "\n"
            + r"\end{document}" + "\n")
        result = subprocess.run(
            ["pdflatex", "-interaction=nonstopmode", "-halt-on-error", "geometry.tex"],
            cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError("Paper class geometry probe failed:\n" + result.stdout[-4000:])
        import re
        return {name.lower(): float(re.search(r"PAPER-" + name + r"=([\d.]+)pt", result.stdout)[1]) / 72.27
                for name in ("COLUMN", "TEXT")}


def loaded_panel_height(system_count):
    """Fixed canvas for one loaded-chart row per observed system."""
    return 2 * system_count + .5


def figure(*, columns=1, height=None):
    """One axes in one column by default; no title, caption or annotation."""
    if columns not in (1, 2):
        raise ValueError("columns must be 1 or 2")
    width = dimensions()["column" if columns == 1 else "text"]
    plt.rcParams.update({
        "pgf.texsystem": "pdflatex", "pgf.rcfonts": False,
        "pgf.preamble": PREAMBLE, "text.usetex": True,
        "font.family": "serif", "font.size": 9,
        "axes.labelsize": 9, "xtick.labelsize": 8, "ytick.labelsize": 8,
        "legend.fontsize": 8, "axes.linewidth": .6,
        "lines.linewidth": 1.2, "lines.markersize": 3,
        "axes.spines.top": False, "axes.spines.right": False,
        "axes.prop_cycle": matplotlib.cycler(color=COLORS),
        "legend.frameon": False, "savefig.transparent": False,
    })
    fig, ax = plt.subplots(figsize=(width, height or width * .68))
    # Fixed canvas width survives export; tight bounding boxes would change it.
    fig.subplots_adjust(left=.18, right=.975, bottom=.21, top=.96)
    ax.tick_params(direction="out", width=.6, length=3)
    ax.grid(axis="y", color=".9", linewidth=.4)
    ax.set_axisbelow(True)
    return fig, ax


def save(fig, destination):
    destination = Path(destination)
    if destination.suffix != ".pdf":
        raise ValueError("Paper charts are vector PDF files")
    destination.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(destination, metadata={"Creator": "Lauberhorn paper_chart", "CreationDate": None})
    plt.close(fig)


def paper_output(destination):
    """The paper repository holds figures and compact source references only."""
    return Path(destination).resolve() == Path(__file__).resolve().parents[3] / "paper/plots"


def diagnostics(destination, name, values):
    """Retain detailed statistics for platform validation, not paper publication."""
    if not paper_output(destination):
        import json
        (Path(destination) / name).write_text(json.dumps(values, indent=2) + "\n")


def provenance(destination, source, mode):
    """Record the original input, never a temporary archive extraction path."""
    if not paper_output(destination):
        return
    import json
    root = Path(__file__).resolve().parents[2]
    revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    record = dict(platform_git=revision,
                  dataset=os.path.relpath(Path(source).resolve(), root))
    (Path(destination) / ("echo-" + mode + "-source.json")).write_text(json.dumps(record, indent=2) + "\n")
