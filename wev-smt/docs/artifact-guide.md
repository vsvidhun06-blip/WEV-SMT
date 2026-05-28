# Artifact guide (POPL Artifact Evaluation)

Reviewer-facing companion to the packaged artifact. The user-facing quick start
lives in [`../artifact/README.md`](../artifact/README.md); this document is the
"how to evaluate it" reference: what each step proves, how long it takes, what is
and is not claimed, and how to reach the (anonymised) authors.

The artifact corresponds to commit `adb65a7` (verdict/atlas snapshots) and the
Day-13 packaging, extended in Day-14 with an edge-case robustness sweep (step 7).

## 1. Claims this artifact supports

| # | Paper claim | Reproduced by | Pass condition |
|---|-------------|---------------|----------------|
| C1 | The consistency encoding is correct against the textbook atlas. | atlas step | 190 compared, **190 matched, 0 mismatched** |
| C2 | The decision procedure scales polynomially, not exponentially, in thread count. | scalability-consistency sweep | verdicts match snapshot; WEAKEST near-linear, no ≥2.5×/step blow-up |
| C3 | First-class fences/RMW add bounded overhead. | scalability-fences sweep | verdicts match snapshot; fence/RMW overhead **< 3×** baseline at n≤4 (≈2.6× on reference HW) |
| C4 | The encoding is internally sound on real corpora (§6.2). | optional corpus step | **0** hierarchy-soundness violations |
| C5 | The back end degrades gracefully under malformed/oversized input (§6.4). | robustness sweep | **11/11** edge cases handled (verdict, validator rejection, resource refusal, or capped timeout); never a crash, hang, or silent wrong answer |

C1–C3 and C5 need no external data and run from the image alone (Functional
badge). C4 needs a mounted `.litmus` corpus (Reusable badge); see README §4. C5 is
backed by `docs/limits.md` and the `wev.smt.edgecase.EdgeCaseTest` suite.

## 2. Step-by-step (what the reviewer runs)

```bash
cd wev-smt
docker build -f artifact/Dockerfile -t wev-smt-artifact:adb65a7 .   # ~5–10 min, online
docker run --rm wev-smt-artifact:adb65a7                            # ~15–20 min, offline
```

`reproduce.sh` (the image ENTRYPOINT) runs seven steps and prints a checklist:

1. **Unit tests** — `mvn -o test`; asserts surefire totals are `tests=45,
   failures=0, errors=0`.
2. **Atlas** — `exec:exec@atlas`; asserts `consistency-validation.csv` has 190
   compared cells, 0 mismatched, and that its `(litmus,model,expected,actual,
   match)` projection equals `expected-outputs/atlas-expected.txt`.
3. **Scalability (consistency)** — `exec:exec@scalability-sweep`; diffs the
   `(family,n,events,model,verdict)` projection of `scalability-consistency.csv`.
4. **Scalability (fences/RMW)** — `exec:exec@scalability-fences`; diffs the same
   projection of `scalability-fences.csv` and echoes the worst overhead ratio.
5. **Corpus hierarchy-soundness** — only if `/corpus` is mounted; re-derives the
   violation count from a fresh `corpus-validation.csv` and asserts 0.
6. **Plots** — `artifact/plots/generate-all.py`; writes the three figures
   (PDF+PNG) to `eval/plots/`.
7. **Robustness sweep** — `exec:exec@robustness`; runs the 11 edge cases A–K, asserts
   the tool exits 0 (no case threw), 11 cases were recorded, and the deterministic
   `<id> | <outcome>` projection of `robustness-report.txt` equals
   `expected-outputs/robustness-report-expected.txt` (the per-case time/mem column
   is dropped). Self-bounded by a 90 s per-case cap, so it cannot hang the run.

### Why the diffs ignore most columns

Only **verdicts** and **match outcomes** are deterministic. Timing (`*_ms`),
memory (`used_mem_mb`), and the minimum-witness-size column (which flips to a
sentinel once a budget-derived deadline passes) vary by machine and budget, so
they are projected out before diffing. The separation atlas and the wall-clock
summary are likewise budget-dependent and are reported, never diffed. This is why
a rerun on different hardware still passes byte-for-byte.

## 3. Expected runtime

| Step | Time (recommended HW) | Notes |
|------|-----------------------|-------|
| build | 5–10 min | one-off; downloads JDK/Maven deps + Z3 native + apt plotting stack |
| 1 tests | ~30–60 s | cache already warm from build |
| 2 atlas | ~8 min | self-bounded (separation search deadline); validation itself is seconds |
| 3 sweep | ~3 min | at default `SWEEP_BUDGET_MIN=3` |
| 4 fences | ~3 min | at default budget |
| 5 corpus | 0 (skipped) … up to `CORPUS_BUDGET_MIN` | optional |
| 6 plots | <1 min | |
| 7 robustness | ~1 min | 11 cases, self-bounded at a 90 s per-case cap |
| **total (1–4 + plots + robustness)** | **< 30 min** | the AE time box |

Slower hardware: raise `SWEEP_PERCALL_SEC` (and `SWEEP_BUDGET_MIN`) so no verdict
falls back to `TIMEOUT`; the atlas budget is fixed in code and always completes.

## 4. Known limitations

* **Determinism scope.** Reproduced byte-for-byte: verdicts, match outcomes,
  hierarchy-soundness, and the robustness edge-case outcomes. **Not** reproduced
  exactly: timings, memory, the separation-atlas cell contents, the
  minimum-witness-size column, the per-robustness-case time/mem, and the absolute
  corpus file count (all machine/budget-dependent — by design, see §2).
* **Platform.** The image is `linux/amd64`; the Maven profile pulls the x86-64 Z3
  native. Apple Silicon works under `--platform linux/amd64` emulation (slower).
* **Corpus not shipped.** The 203 MB / 36 655-file herd7 + Dat3M corpora are
  gitignored and excluded from the image; C4 requires mounting them. Parse
  coverage on the raw corpora is ~24% (documented boundaries, not soundness gaps;
  see `corpus-validation-findings.md`).
* **Scope of the encoding.** Fence/RMW support is first-class as of Day 12, but
  some ISA surface remains unparsed (e.g. RISC-V `ori`/`.aq`/`.rl`, AArch64 MTE,
  AT&T-syntax x86, SIMD); these are coverage limits, enumerated in
  `litmus-parser-coverage.md`, and never produce a soundness violation.
* **No `com.weakest.*` edits.** The artifact adds only packaging (Dockerfile,
  scripts, docs, Maven exec profiles); the model and `ConsistencyChecker` are
  untouched, so the artifact runs exactly the reviewed code.
* **Vendored model jar.** `wev-smt` depends on the visualiser's model classes,
  shipped as the shaded `com.weakest:visualising-weakest-executions:1.0-SNAPSHOT`
  jar (218 KB, no transitive deps). It is vendored at `artifact/lib/` and
  `install-file`'d during the image build, so the image is self-contained and does
  not need the (JavaFX/GraphStream) visualiser source. To rebuild that jar from
  source instead: `mvn -f <repo-root>/pom.xml install -DskipTests` (requires the
  JavaFX toolchain), which installs the identical artifact into your local repo.

## 5. Reproducing on bare metal (no Docker)

```bash
cd wev-smt
mvn test                                   # 45 tests; copies the Z3 native into target/native
WEV_REPO="$PWD" SWEEP_BUDGET_MIN=3 bash artifact/reproduce.sh
```

Requires JDK 21, Maven 3.9+, and Python 3 with matplotlib + pandas. `reproduce.sh`
honours `WEV_REPO`; outputs land in `eval/`. (Docker is recommended — it pins the
toolchain and the Z3 native so the run is hermetic.)

## 6. Support / contact

Anonymised for double-blind review. Please route questions through the **POPL
Artifact Evaluation HotCRP** thread for this submission; the authors monitor it
and will respond there. A non-anonymous contact and a DOI'd archival copy will
replace this section in the camera-ready.
