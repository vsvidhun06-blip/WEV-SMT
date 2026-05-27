#!/usr/bin/env python3
"""Orchestrator: generate every artifact plot from the eval CSVs.

Runs each plotting script as an isolated subprocess so a single failure (e.g. a
missing CSV when a step was skipped) degrades gracefully instead of aborting the
whole reproduction. Each script writes both a PDF and a PNG.

Resolution:
  EVAL_DIR  argv[1], else $WEV_EVAL_DIR, else <wev-smt>/eval  (repo root inferred
            from this file's location: artifact/plots/generate-all.py)
  OUT_DIR   argv[2], else EVAL_DIR/plots

Usage: generate-all.py [EVAL_DIR] [OUT_DIR]
"""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent          # artifact/plots
REPO = HERE.parent.parent                        # wev-smt

SCRIPTS = ["scalability-curves.py", "atlas-heatmap.py", "separation-matrix.py"]


def main():
    eval_dir = Path(sys.argv[1]) if len(sys.argv) > 1 \
        else Path(os.environ.get("WEV_EVAL_DIR", REPO / "eval"))
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else eval_dir / "plots"
    eval_dir = eval_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"=== plot generation ===")
    print(f"  eval dir: {eval_dir}")
    print(f"  out  dir: {out_dir.resolve()}")

    failures = []
    for script in SCRIPTS:
        path = HERE / script
        print(f"\n--- {script} ---")
        r = subprocess.run([sys.executable, str(path), str(eval_dir), str(out_dir)])
        if r.returncode != 0:
            failures.append(script)
            print(f"  !! {script} exited {r.returncode} (continuing)")

    produced = sorted(p.name for p in out_dir.glob("*.pdf")) + \
        sorted(p.name for p in out_dir.glob("*.png"))
    print(f"\n=== plot generation complete: {len(produced)} files in {out_dir.resolve()} ===")
    for f in produced:
        print(f"  {f}")
    if failures:
        print(f"\nWARNING: {len(failures)} plot script(s) failed: {', '.join(failures)}")
    # A failed plot is not fatal to the reproduction (the numbers, not the figures,
    # are the result), so exit 0 unless every script failed.
    return 1 if len(failures) == len(SCRIPTS) else 0


if __name__ == "__main__":
    sys.exit(main())
