#!/usr/bin/env python3
"""Model x litmus heatmap of the atlas validation outcome (match / mismatch / unknown).

Reads ``consistency-validation.csv`` from the eval directory (the per-cell verdict
table written by ``wev.smt.cli.AtlasReconstruct``) and writes
``atlas-heatmap.{pdf,png}``.

Each cell is the agreement between the SMT verdict and the textbook outcome:
matched, mismatched, or unknown (a cell with no textbook ground truth, recorded
match=n/a). The headline result is an all-matched grid: 190/190 matched, 0
mismatched, 10 unknown.

Usage: atlas-heatmap.py [EVAL_DIR] [OUT_DIR]
"""
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm
from matplotlib.patches import Patch
import numpy as np
import pandas as pd

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["DejaVu Sans", "Arial", "Helvetica"],
    "figure.dpi": 150,
    "savefig.bbox": "tight",
})

MODELS = ["SC", "TSO", "PSO", "RA", "WEAKEST"]

# Okabe-Ito colour-blind-safe categories: mismatch=vermillion, unknown=grey,
# match=bluish-green. Distinguishable under deuteranopia/protanopia/tritanopia.
CODE = {"false": 0, "n/a": 1, "true": 2}
CAT_COLORS = ["#D55E00", "#999999", "#009E73"]
CAT_LABELS = ["mismatch", "unknown (no ground truth)", "match"]


def make(eval_dir: Path, out_dir: Path):
    p = eval_dir / "consistency-validation.csv"
    if not p.exists():
        print(f"[atlas-heatmap] {p} not found; skipping")
        return []
    df = pd.read_csv(p)

    litmus = list(dict.fromkeys(df["litmus"]))
    grid = np.full((len(MODELS), len(litmus)), np.nan)
    for _, r in df.iterrows():
        if r["model"] not in MODELS or r["litmus"] not in litmus:
            continue
        i = MODELS.index(r["model"])
        j = litmus.index(r["litmus"])
        grid[i, j] = CODE.get(str(r["match"]).strip().lower(), 1)

    cmap = ListedColormap(CAT_COLORS)
    norm = BoundaryNorm([-0.5, 0.5, 1.5, 2.5], cmap.N)

    fig_w = max(8.0, 0.32 * len(litmus))
    fig, ax = plt.subplots(figsize=(fig_w, 3.4))
    ax.imshow(grid, aspect="auto", cmap=cmap, norm=norm)

    ax.set_yticks(range(len(MODELS)))
    ax.set_yticklabels(MODELS)
    ax.set_xticks(range(len(litmus)))
    ax.set_xticklabels(litmus, rotation=90, fontsize=7)
    ax.set_xlabel("litmus test")
    ax.set_ylabel("memory model")

    # thin gridlines between cells
    ax.set_xticks(np.arange(-0.5, len(litmus), 1), minor=True)
    ax.set_yticks(np.arange(-0.5, len(MODELS), 1), minor=True)
    ax.grid(which="minor", color="white", lw=0.6)
    ax.tick_params(which="minor", length=0)

    n_match = int((grid == 2).sum())
    n_mismatch = int((grid == 0).sum())
    n_unknown = int((grid == 1).sum())
    ax.set_title(f"Atlas validation: SMT verdict vs textbook "
                 f"({n_match} matched, {n_mismatch} mismatched, {n_unknown} unknown)")

    legend = [Patch(facecolor=c, label=l) for c, l in zip(CAT_COLORS, CAT_LABELS)]
    ax.legend(handles=legend, ncol=3, loc="upper center",
              bbox_to_anchor=(0.5, -0.32), fontsize=8, frameon=False)

    fig.tight_layout()
    out_dir.mkdir(parents=True, exist_ok=True)
    outs = []
    for ext in ("pdf", "png"):
        o = out_dir / f"atlas-heatmap.{ext}"
        fig.savefig(o)
        outs.append(str(o))
    plt.close(fig)
    print(f"[atlas-heatmap] wrote {', '.join(outs)}")
    return outs


if __name__ == "__main__":
    ev = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eval")
    od = Path(sys.argv[2]) if len(sys.argv) > 2 else ev / "plots"
    make(ev, od)
