#!/usr/bin/env python3
"""Model-pair separation matrix: smallest witness that one model allows and another
forbids.

Reads ``atlas-separations.csv`` from the eval directory (written by
``wev.smt.cli.AtlasReconstruct``) and writes ``separation-matrix.{pdf,png}``.

Rows are the *allowing* model, columns the *forbidding* model. A cell shows the
minimum witness cardinality found among all litmus tests that separate that ordered
pair, annotated with the number of separating tests. Cells with no solved separation
(none exists, or all searches hit the budget) are left blank; the diagonal is N/A.

Usage: separation-matrix.py [EVAL_DIR] [OUT_DIR]
"""
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["DejaVu Sans", "Arial", "Helvetica"],
    "figure.dpi": 150,
    "savefig.bbox": "tight",
})

MODELS = ["SC", "TSO", "PSO", "RA", "WEAKEST"]


def make(eval_dir: Path, out_dir: Path):
    p = eval_dir / "atlas-separations.csv"
    if not p.exists():
        print(f"[separation-matrix] {p} not found; skipping")
        return []
    df = pd.read_csv(p)
    solved = df[df["status"] == "SOLVED"].copy()
    if not solved.empty:
        solved["witnessSize"] = pd.to_numeric(solved["witnessSize"], errors="coerce")

    n = len(MODELS)
    minsize = np.full((n, n), np.nan)
    counts = np.zeros((n, n), dtype=int)
    for i, allow in enumerate(MODELS):
        for j, forbid in enumerate(MODELS):
            if i == j:
                continue
            cell = solved[(solved["allowedBy"] == allow) & (solved["forbiddenBy"] == forbid)]
            if not cell.empty:
                counts[i, j] = len(cell)
                minsize[i, j] = cell["witnessSize"].min()

    fig, ax = plt.subplots(figsize=(6.4, 5.4))
    masked = np.ma.masked_invalid(minsize)
    cmap = plt.get_cmap("viridis_r").copy()  # smaller witness = "stronger" = brighter
    cmap.set_bad(color="#eeeeee")
    im = ax.imshow(masked, cmap=cmap, aspect="equal")

    ax.set_xticks(range(n)); ax.set_xticklabels(MODELS, rotation=45, ha="right")
    ax.set_yticks(range(n)); ax.set_yticklabels(MODELS)
    ax.set_xlabel("forbidden by")
    ax.set_ylabel("allowed by")
    ax.set_title("Minimum separating witness per ordered model pair")

    for i in range(n):
        for j in range(n):
            if i == j:
                ax.text(j, i, "—", ha="center", va="center", color="#888888")
            elif not np.isnan(minsize[i, j]):
                ax.text(j, i, f"{int(minsize[i, j])}\n({counts[i, j]} tests)",
                        ha="center", va="center", fontsize=8,
                        color="white" if minsize[i, j] <= np.nanmedian(minsize) else "black")

    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label("min witness size (events)")

    fig.tight_layout()
    out_dir.mkdir(parents=True, exist_ok=True)
    outs = []
    for ext in ("pdf", "png"):
        o = out_dir / f"separation-matrix.{ext}"
        fig.savefig(o)
        outs.append(str(o))
    plt.close(fig)
    n_solved = int(counts.sum())
    print(f"[separation-matrix] wrote {', '.join(outs)} "
          f"({n_solved} solved separations across {int((counts>0).sum())} model pairs)")
    return outs


if __name__ == "__main__":
    ev = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eval")
    od = Path(sys.argv[2]) if len(sys.argv) > 2 else ev / "plots"
    make(ev, od)
