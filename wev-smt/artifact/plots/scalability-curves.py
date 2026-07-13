#!/usr/bin/env python3
"""Log-log decision-time vs thread-count curves, one panel per parametric family,
one line per memory model.

Reads ``scalability-consistency.csv`` (Day-9 families) and, if present,
``scalability-fences.csv`` (Day-12 fence/RMW families) from the eval directory and
writes ``scalability-curves.{pdf,png}`` to the output directory.

Usage: scalability-curves.py [EVAL_DIR] [OUT_DIR]
       (defaults: EVAL_DIR=eval, OUT_DIR=EVAL_DIR/plots)
"""
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")  # headless: no display in the container
import matplotlib.pyplot as plt
import pandas as pd

# Publication style: sans-serif, tight bounding box, decent raster DPI.
plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["DejaVu Sans", "Arial", "Helvetica"],
    "axes.titlesize": 11,
    "figure.dpi": 150,
    "savefig.bbox": "tight",
})

MODELS = ["SC", "TSO", "PSO", "RA", "WEAKEST"]


def _load(eval_dir: Path):
    frames = []
    for name in ("scalability-consistency.csv", "scalability-fences.csv"):
        p = eval_dir / name
        if p.exists():
            df = pd.read_csv(p)
            df["source"] = name
            frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else None


def make(eval_dir: Path, out_dir: Path):
    df = _load(eval_dir)
    if df is None:
        print("[scalability-curves] no scalability CSVs found; skipping")
        return []

    families = list(dict.fromkeys(df["family"]))  # unique, source order
    ncol = 2
    nrow = (len(families) + ncol - 1) // ncol
    fig, axes = plt.subplots(nrow, ncol, figsize=(6 * ncol, 3.6 * nrow), squeeze=False)

    # viridis is perceptually uniform and colour-blind friendly (sampled at 5 points).
    cmap = plt.get_cmap("viridis")
    colors = {m: cmap(i / (len(MODELS) - 1)) for i, m in enumerate(MODELS)}

    for idx, fam in enumerate(families):
        ax = axes[idx // ncol][idx % ncol]
        sub = df[df["family"] == fam]
        for m in MODELS:
            s = sub[sub["model"] == m].sort_values("n")
            if s.empty:
                continue
            # Clamp at 1 ms so the log axis tolerates sub-millisecond timer noise.
            y = s["fullexec_ms"].clip(lower=1)
            ax.plot(s["n"], y, marker="o", ms=4, lw=1.6, label=m, color=colors[m])
        ax.set_xscale("log", base=2)
        ax.set_yscale("log")
        ax.set_title(fam)
        ax.set_xlabel("thread count $n$ (log$_2$)")
        ax.set_ylabel("decision time (ms, log)")
        ax.grid(True, which="both", ls=":", alpha=0.4)
        ax.legend(fontsize=8, title="model", framealpha=0.9)

    for j in range(len(families), nrow * ncol):
        axes[j // ncol][j % ncol].axis("off")

    fig.suptitle("Consistency decision time vs thread count (per family, per model)",
                 y=1.005, fontsize=13)
    fig.tight_layout()

    out_dir.mkdir(parents=True, exist_ok=True)
    outs = []
    for ext in ("pdf", "png"):
        o = out_dir / f"scalability-curves.{ext}"
        fig.savefig(o)
        outs.append(str(o))
    plt.close(fig)
    print(f"[scalability-curves] wrote {', '.join(outs)}")
    return outs


if __name__ == "__main__":
    ev = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eval")
    od = Path(sys.argv[2]) if len(sys.argv) > 2 else ev / "plots"
    make(ev, od)
