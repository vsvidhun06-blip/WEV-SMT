# WEV-SMT — POPL Artifact

This artifact reproduces every evaluation result for the paper's SMT
minimum-witness backend for weak-memory consistency (`wev-smt`):

* the **34-test** unit suite,
* the **weak-memory atlas** — 40 litmus tests × 5 models, **190 compared / 190
  matched / 0 mismatched**,
* the **Day-9 scalability sweep** (consistency decision time vs thread count),
* the **Day-12 fence/RMW scalability sweep** (two new parametric families), and
* an **optional corpus hierarchy-soundness check** (0 violations across the real
  herd7 + Dat3M corpora).

It is packaged as a single Docker image. One `docker run` executes all of the
above, diffs each numeric result against a committed known-good snapshot
(`artifact/expected-outputs/`, taken at commit `adb65a7`), generates
publication-quality plots, and prints a pass/fail validation checklist.

---

## 1. Hardware requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM      | 4 GB    | 8 GB        |
| Disk     | 3 GB free (image ≈ 1.3 GB + Maven cache) | 5 GB |
| CPU      | 2 cores | 4+ cores    |
| Arch     | linux/amd64 (x86-64) — see Troubleshooting for Apple Silicon | |

## 2. Software requirements

* **Docker** (Engine 20.10+ / Docker Desktop). Nothing else — the JDK 21, Maven,
  Z3 (via JavaSMT's bundled native), and the Python plotting stack are all inside
  the image. No internet access is needed at **run** time (only to **build** the
  image).

> **Vendored dependency.** `wev-smt` compiles against the WEV visualiser's model
> classes, published as the shaded, dependency-reduced jar
> `com.weakest:visualising-weakest-executions:1.0-SNAPSHOT` (218 KB, **zero**
> transitive dependencies — no JavaFX/GraphStream). That jar is not on Maven
> Central, so it is vendored at `artifact/lib/` and `install-file`'d into the
> image's local repository during the build. This keeps the artifact
> self-contained without pulling the visualiser's GUI dependency tree into the
> image.

## 3. Quick start (Functional badge)

```bash
# from the wev-smt/ directory (the build context must be the project root so the
# whole project is copied — Docker cannot COPY from outside the context):
cd wev-smt
docker build --provenance=false -f artifact/Dockerfile -t wev-smt-artifact:adb65a7 .
docker run --rm wev-smt-artifact:adb65a7
```

The run prints a step-by-step log and ends with a checklist. Expected tail:

```
=== Validation checklist ===
  PASS  unit tests 34/34 green
  PASS  atlas 190/190 matched, 0 mismatched
  PASS  atlas validation projection vs expected
  PASS  scalability-consistency verdicts vs expected
  PASS  scalability-fences verdicts vs expected
  SKIP  corpus hierarchy-soundness (/corpus not mounted — optional, Reusable-badge extra)
  PASS  plots generated (eval/plots/*.pdf,*.png)
=== Reproduction complete: ALL CHECKS PASSED ===
```

Exit code `0` means every check passed; non-zero means at least one diff failed
(the offending diff is printed inline).

### Getting the outputs out of the container

The figures and CSVs are written inside the container at `/opt/wev-smt/eval`.
Mount a host directory there to keep them:

```bash
docker run --rm -v "$PWD/out":/opt/wev-smt/eval wev-smt-artifact:adb65a7
# → ./out/plots/*.pdf, ./out/consistency-validation.csv, ./out/scalability-*.csv
```

## 4. Full reproduction (Reusable badge — optional corpus check)

The hierarchy-soundness result over the **real** litmus corpora is the optional
extra for the Reusable badge. The corpora (~200 MB, 36 k files) are **not** baked
into the image; mount them at `/corpus`:

```bash
# /abs/path/to/litmus contains .litmus files (e.g. a checkout of
# herd/herdtools7 and/or hernanponcedeleon/Dat3M):
docker run --rm -v /abs/path/to/litmus:/corpus wev-smt-artifact:adb65a7
```

reproduce.sh then runs the corpus sweep and re-derives the hierarchy-soundness
violation count from the fresh `corpus-validation.csv`, asserting **0 violations**
(see `expected-outputs/hierarchy-soundness-expected.txt`). The file *count*
depends on which corpus you mount and the budget you allow; only the violation
count (0) is asserted. To reproduce the paper's 2998-file / 0-violation figure,
mount the full herd7 + Dat3M trees and allow the default 30-minute corpus budget.

### Tuning the run

| Env var | Default | Meaning |
|---------|---------|---------|
| `SWEEP_BUDGET_MIN`  | `3`  | per-sweep total budget (min). Consistency verdicts — the diffed result — are budget-independent; a longer budget only fills in more (timing-only) separation cells. |
| `SWEEP_PERCALL_SEC` | `30` | per-solver-call cap (s). |
| `CORPUS_BUDGET_MIN` | `30` | corpus sweep budget (min); only used when `/corpus` is mounted. |

```bash
docker run --rm -e SWEEP_BUDGET_MIN=10 wev-smt-artifact:adb65a7
```

## 5. Expected outputs

| File (under `eval/`)                | What to look for |
|-------------------------------------|------------------|
| stdout `Step 2`                     | `compared=190  mismatched=0` |
| `consistency-validation.csv`        | 200 rows; 190 with `match=true`, 0 with `match=false`, 10 `n/a` |
| `scalability-consistency.csv`       | 180 rows; `WEAKEST` decision time near-linear up to `n=16` |
| `scalability-fences.csv`            | 135 rows; `SBChainMfence`/`RMWChain` overhead < 3× the `SBNThread` baseline (≈2.6× on reference HW) |
| `atlas-separations.csv`             | the separation atlas (budget-limited; informational, not diffed) |
| `plots/scalability-curves.{pdf,png}`| log-log decision time vs `n`, per family, per model |
| `plots/atlas-heatmap.{pdf,png}`     | all-green (match) grid, 10 grey (unknown) cells |
| `plots/separation-matrix.{pdf,png}` | min separating-witness size per model pair |

### Validation checklist

```
[ ] 34/34 unit tests green
[ ] Atlas: 190/190 matched, 0 mismatched
[ ] Atlas validation projection == expected-outputs/atlas-expected.txt
[ ] Scalability-consistency: verdicts == expected; WEAKEST near-linear at n=16
[ ] Scalability-fences: verdicts == expected; worst overhead < 3× at n≤4 (≈2.6× on reference HW)
[ ] Hierarchy-soundness: 0 violations  (only if /corpus mounted)
[ ] Plots produced (PDF + PNG)
```

The diffs compare only **deterministic** columns (verdicts / match outcomes);
timing, memory, and minimum-witness-size columns are machine- and budget-dependent
and are intentionally excluded, as are the separation-atlas cells and wall-clock
lines.

## 6. Runtime

On the recommended hardware, steps 1–4 plus plots complete in **well under 30
minutes** (the atlas is self-bounded at ≈8 minutes; each sweep ≈3 minutes at the
default budget; tests and plots ≈1 minute each). The optional corpus step adds up
to `CORPUS_BUDGET_MIN` minutes.

## 7. Troubleshooting

* **`Cannot connect to the Docker daemon`** — start Docker Desktop / the Docker
  service first.
* **Apple Silicon (arm64) / "no matching manifest" / Z3 `UnsatisfiedLinkError`** —
  the image is `linux/amd64` (the Maven build pulls the x86-64 Z3 native). On an
  M-series Mac, build and run under emulation:
  ```bash
  docker build --platform linux/amd64 -f artifact/Dockerfile -t wev-smt-artifact:adb64 .
  docker run --rm --platform linux/amd64 wev-smt-artifact:adb64
  ```
  Emulation is slower; raise `SWEEP_PERCALL_SEC` if any verdict shows `TIMEOUT`.
* **A verdict shows `TIMEOUT` in a diff** — the host is slower than the per-call
  cap allows; re-run with `-e SWEEP_PERCALL_SEC=120 -e SWEEP_BUDGET_MIN=15`.
* **Out of memory during a large-`n` sweep** — give Docker ≥ 4 GB (Docker Desktop
  → Settings → Resources).
* **Plots missing but numbers pass** — a plotting failure is non-fatal; matplotlib +
  pandas are pip-installed into the image (headless Agg backend), so the figures are
  produced without any GUI toolkit.

## 8. Citation

```bibtex
@inproceedings{wev-smt-popl,
  title     = {{Minimum-Witness Reconstruction of the Weak-Memory Consistency Atlas}},
  author    = {Anonymous (POPL Artifact Evaluation)},
  booktitle = {Proceedings of the ACM on Programming Languages (POPL)},
  year      = {2026},
  note      = {Artifact: wev-smt, commit adb65a7. \url{https://doi.org/REPLACE-WITH-DOI}}
}
```

---

For per-step reviewer instructions, expected runtimes, known limitations, and the
support contact, see [`../docs/artifact-guide.md`](../docs/artifact-guide.md).
