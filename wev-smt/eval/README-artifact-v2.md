# WEV-SMT artifact — reproduction guide (v2)

This is the **one-command** artifact for the WEV-SMT paper. Every inline number and every
table body in `paper/wev-smt.tex` is generated from a run; nothing in the paper is
hand-typed.

```
python eval/run_all.py
```

That single command runs every experiment, writes `eval/results.json` with the raw numbers,
regenerates `eval/numbers.tex` (one `\newcommand` per inline number) and `eval/tables.tex`
(bodies for Tables 1–4), and prints a human-readable summary.

> Use `python`, **not** `python3`. On Git-Bash for Windows the `python3` name resolves to the
> non-functional Microsoft-Store stub.

---

## 1. Prerequisites

| Requirement | Version used | Notes |
|---|---|---|
| JDK | Temurin 21 (LTS) | 17+ should work; the build targets 21 |
| Maven | 3.8+ | only needed for the initial compile |
| Python | 3.11 | standard library only — no pip install |
| Z3 | bundled | native libs are copied into `target/native` by the build |

One-time build (the driver runs this for you if `target/` is missing):

```
mvn -q test-compile
```

This compiles the sources **and** copies the Z3 native libraries (`libz3.dll` /
`libz3java.dll` / `.so`) into `target/native`. Every Java experiment must run with
`-Djava.library.path=target/native`; `run_all.py` sets this for you, as do the
`eval/run_wev.sh` / `eval/run_wev.ps1` wrappers if you want to invoke a single CLI by hand:

```
eval/run_wev.sh  wev.smt.cli.SymbolicRfRun ladder      # bash / Git-Bash
eval\run_wev.ps1 wev.smt.cli.SymbolicRfRun ladder      # PowerShell
```

---

## 2. Running

```
python eval/run_all.py                       # everything (default)
python eval/run_all.py --quick               # skip the two long steps
python eval/run_all.py --only jmm20 dat3m41  # a subset (dependencies pulled in automatically)
python eval/run_all.py --skip scalability    # everything but the sweep
python eval/run_all.py --no-build            # never invoke maven
```

A failing experiment is recorded and the run continues; the exit code is non-zero if any
step failed, and the summary lists the failures at the bottom.

**Partial runs stay complete.** When you use `--only`, `--skip` or `--quick`, the driver
seeds itself from the existing `results.json` so `numbers.tex` is still regenerated with
*every* macro — a partial run never silently drops macros and breaks the paper build. Blocks
carried over from an earlier invocation are marked `CARRIED` in the summary and keep the
`ran_at` timestamp of the run that actually produced them, so it is always visible which
numbers are fresh.

Each block in `results.json` carries a `source` field:

* `run` — produced by a Java process this invocation launched.
* `artifact` — derived from a committed CSV that this invocation did **not** regenerate.
  Currently `coverage`, `hierarchy` and `herd7` (see §5): the first two read the five-model
  `corpus-validation-*.csv` files, and `herd7` reads herd7's own recorded verdicts.
  Re-running either underlying job is a multi-hour task (and `herd7` additionally needs the
  herd7 distribution installed), so both are deliberately outside the one-command path.

---

## 3. What each experiment measures

| Step | What it measures | Driver | Output |
|---|---|---|---|
| `coverage` | How much of the 12,324-file corpus is in the supported-syntax fragment, and how the v1→v2 correction (Layer-2 Category-B move-out) changes it. | reads manifest + validation CSVs | — |
| `layer2` | Detector Layer-2 audit: how many value/address expressions still fall back to the conservative *always-semantic* classification instead of being decided exactly by the QF_BV constancy oracle. The paper claims this is **empty** on the corrected fragment. | `wev.smt.cli.Layer2AuditRun` | `layer2-audit.csv` |
| `ladder` | The 9-step C1 validation ladder: the fixed programs whose symbolic verdicts pin down the encoder's behaviour (LB-real FORBIDDEN, the three fake-dep programs ALLOWED, TC06/TC18 value-unreachable FORBIDDEN, CoWW/CoWR FORBIDDEN). | `wev.smt.cli.SymbolicRfRun ladder` | stdout |
| `corpus_symbolic` | The headline run: every test in the corrected manifest under **both** the wired single-candidate encoding and the symbolic rf+value-propagation encoding, plus solve-time distribution. Yields the verdict-change count and the runtime percentiles. | `wev.smt.cli.SymbolicRfRun corpus` | `symbolic-rf-results.csv` |
| `examples` | Runs the 41 published/Dat3M example programs once in both modes; `dat3m41`, `jmm20` and `published34` all join against this one run rather than re-solving. | `wev.smt.cli.SymbolicRfRun corpus` | (temp) |
| `dat3m41` | Agreement with the Dat3M/LKMM reference verdicts over the 41-test cross-check set, broken down by provenance suite (`jmm` / `weakestmo` / `paper` / `leaky-semicolon`), plus every wired→symbolic flip and whether it moves toward the reference. | join | — |
| `jmm20` | Agreement with the JMM causality suite classifications, TC01–TC20. | join | — |
| `published34` | Agreement with the classifications published in three independent sources: Weakestmo (8), Leaky Semicolon / PwT (6), JMM (20). This is Table 3. | join | — |
| `ra_atlas` | The RA formal atlas: does the formally-derived `RA_FORMAL` axiom agree with the encoded RA on all 40 programs, does the sub-chain SC ⊒ TSO ⊒ RA_FORMAL hold, and which programs witness RA ⊉ WEAKEST. | `wev.smt.cli.RaFormalAtlasRun` | `ra-formal-atlas.csv` |
| `rc11_direction` | The RC11 containment direction: how often RC11 forbids what WEAKEST allows, and — the claim that matters — how often the **reverse** happens (expected: never). | `wev.smt.cli.Rc11DirectionCheck` | `rc11-direction-check.csv` |
| `nthread` | Grounded-LB admission at scale: `LBFakeChain(n)` for n = 2…8 must be ALLOWED for every n. | `wev.smt.cli.NThreadGroundedAdmission` | `nthread-grounded-admission.csv` |
| `pwt` | PwT equivalence sweep: on branch-free arithmetic, does free-valuation `sdep` agree with PwT's predicate-transformer criterion on every extracted expression/register pair? | `wev.smt.cli.PwtEquivalenceSweep` | `pwt-equivalence-sweep.csv` |
| `pso_canonical` | How far the encoder's PSO′ (which retains cross-location W→R order) diverges from canonical SPARC PSO. | `wev.smt.cli.PsoCanonicalComparison` | `pso-canonical-comparison.csv` |
| `qfbv_detector` | Re-classifies every dependency in the fragment with the exact QF_BV constancy oracle installed as the primary decision procedure, and counts how many verdicts differ from the solver-free heuristic — the `sdep_impl = sdep_true` exactness claim. | `wev.smt.cli.QfbvDetectorRun` | `qfbv-detector-results.csv`, `qfbv-summary.md` |
| `detector_audit` | Census of every realized dependency edge in the fragment: how many are classified fake, how many semantic, and how many sit on a candidate (dep ∪ rf) cycle and so can actually change a verdict. | `wev.smt.cli.DetectorQualitySweep` | `detector-quality-report.csv`, `pattern-frequency.csv` |
| `scalability` | Four parametric families (`LBChain`, `LBFakeChain`, `SBNThread`, `IRIWFan`) over n = 2…16 across all five models — the growth-rate evidence and Table 2. | `wev.smt.cli.ScalabilitySweep` | `scalability-consistency.csv`, `scalability-separation.csv` |
| `hierarchy` | Hierarchy preservation: over every test with all five model verdicts, how often is an execution SC-consistent but inconsistent under a weaker model (the four SC-to-weak edges)? Also reports the two intermediate edges that are *not* claimed to hold. | derived from `corpus-validation-*.csv` | — |
| `herd7` | herd7 cross-check: joins herd7's own SC/TSO verdicts with WEV's for the same files and reports agreement split by ARM / non-ARM. | derived from `herd7-results.csv` + `herd7-wev-verdicts.csv` | — |

---

## 4. Expected outputs and runtimes

Measured on Windows 11, Temurin 21, Z3 bundled, warm build. Times are wall clock for the
step, including JVM startup.

| Step | Runtime | Expected result |
|---|---|---|
| `coverage` | < 1 s | 2,972 of 12,324 in the fragment (24.1 %); 7,461 skipped + 1,865 parse errors + 26 audit-excluded, and the four buckets partition the corpus exactly |
| `layer2` | ~4 s | **0** Layer-2 sites, 0 tests |
| `ladder` | ~1 s | **9/9 PASS** |
| `corpus_symbolic` | 90–105 s | 2,972 tests; **536** verdict changes (413 A→F, 123 F→A); 0 errors, 0 skipped; median solve ~12 ms, p95 ~25–35 ms, max ~70 ms |
| `examples` | ~2 s | 41 programs, both modes |
| `dat3m41` | < 1 s | **35/41** symbolic (31/41 wired); all four flips move toward the reference |
| `jmm20` | < 1 s | **18/20** symbolic (17/20 wired); divergences TC04, TC13 |
| `published34` | < 1 s | **31/34** — Weakestmo 8/8, PwT 5/6, JMM 18/20 |
| `ra_atlas` | ~3 s | 40/40 RA_FORMAL ≡ RA; **0** sub-chain violations; 3 RA ⊉ WEAKEST witnesses |
| `rc11_direction` | ~1 s | 27 tests; **6** RC11-forbids/WEAKEST-allows; **0** in the reverse direction |
| `nthread` | ~1 s | 7/7 ALLOWED for n = 2…8 |
| `pwt` | ~1 s | **79/79** pairs agree |
| `pso_canonical` | ~2 s | 2/40 divergences from canonical SPARC PSO |
| `qfbv_detector` | ~2 s | **0/2,972** classification changes vs the solver-free detector |
| `detector_audit` | ~1 s | 318 realized dependency edges (301 fake, 17 semantic, 5 verdict-relevant) |
| `scalability` | **~30 min** | 4 families × 9 values of n × 5 models; slowest WEAKEST query ~120 ms, PSO/RA up to ~7 s |
| `hierarchy` | < 1 s | **0** violations across the four SC-to-weak edges; 0 on the two intermediate edges |
| `herd7` | < 1 s | non-ARM SC 86.2 %, non-ARM TSO 73.9 %, ARM SC 38.3 % |

**Total: ~2.5 minutes with `--quick`; ~35 minutes for the full run, almost all of it the
scalability sweep.**

Note on `pwt`: the sweep extracts expression/register pairs from the manifest, so running it
against the corrected v2 manifest yields **79** pairs, not the 80 the v1 manifest produced —
one pair came from a test the Layer-2 audit moved out. Agreement is still total.

### Generated files

| File | Contents |
|---|---|
| `eval/results.json` | Every raw number, per step, with `source` provenance. The single source of truth. |
| `eval/numbers.tex` | One `\newcommand` per inline number in the paper. `\input` near the top of `paper/wev-smt.tex`. |
| `eval/tables.tex` | `\FidelityTableBody`, `\ScalabilityTableBody`, `\SourcePaperTableBody`, `\RcElevenTableBody` — tabular **bodies** only; the surrounding `table`/`tabular`/`caption` stays in the paper. |

Both `.tex` files begin with a "GENERATED — do not edit by hand" banner and a timestamp.

---

## 5. Notes and caveats

**Three steps are marked `artifact`, not `run`.** `coverage` and `hierarchy` derive their
numbers from the committed `corpus-validation-dat3m.csv` / `corpus-validation-herd7.csv`,
which hold five model verdicts for every corpus file; `herd7` joins `herd7-results.csv`
(herd7's verdicts) with `herd7-wev-verdicts.csv`. Regenerating the first two means
re-running the full five-model validation over 12,324 files — hours, not minutes — so it is
not on the one-command path, and regenerating the third needs a herd7 installation. To
regenerate the validation CSVs:

```
eval/run_wev.sh wev.smt.cli.CorpusValidation eval/corpus/Dat3M     eval csv=corpus-validation-dat3m.csv
eval/run_wev.sh wev.smt.cli.CorpusValidation eval/corpus/herdtools7 eval csv=corpus-validation-herd7.csv
```

**The atlas "chain" column.** `ra-formal-atlas.csv` has a `chainOK` column covering the whole
SC ⊒ TSO ⊒ RA_FORMAL ⊒ WEAKEST chain, including the RA ⊒ WEAKEST rung that the paper
explicitly states does **not** hold by construction. `run_all.py` therefore reports two
separate numbers: `subchain_violations` (the part that is claimed to hold — expected 0) and
`ra_weakest_witnesses` (the programs that witness RA ⊉ WEAKEST — expected 3, all real- or
address-dependency LB). Reading `chainOK` as a pass/fail count would wrongly report 3
failures.

**Reference labels are inputs, not measurements.** The Dat3M/LKMM verdicts, the JMM
classifications, and the source-paper classifications are reference data read from
`dat3m-comparison-v2.csv`, `jmm-results.csv` and `source-paper-results.csv`. `run_all.py`
measures the checker's verdicts and joins; it never regenerates a reference label. The same
holds for the literature column of Table 1 and the dependency-type prose of Table 4, which
are declared as constants at the top of the tables section of `run_all.py`.

**The scalability sweep is budgeted.** `ScalabilitySweep` takes a total wall-clock budget
(the driver passes 30 minutes) and a per-call cap (60 s). Cells that exceed the budget are
recorded as `TIMEOUT` rather than failing the run, so a slow machine degrades the table
rather than breaking it.

**Determinism.** Verdicts are deterministic — they are SAT/UNSAT results, not samples.
Timings are not: the millisecond columns, the median/p95/max macros, and the Table 2 timing
cells will differ run to run and machine to machine. Verdict-derived numbers (agreement
fractions, violation counts, verdict-change counts) should reproduce exactly.

**Corpus provenance.** `eval/corpus/` holds the herd7 catalogue and the Dat3M benchmark
suite, 12,324 `.litmus` files. `eval/corpus-weakest-manifest-v2.csv` is the corrected
supported-syntax manifest: v1 minus the 26 tests carrying instructions outside the modeled
vocabulary (`CSEL`, `CSET`, predicated moves, PC-relative branch targets, named branch
labels, relocation symbols). See `eval/layer2-audit-summary.md` for the audit that produced
it and `eval/layer2-excluded-tests.csv` for the exclusion list.
