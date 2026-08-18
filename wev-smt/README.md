# WEV-SMT — artifact

Artifact for *Distinguishing Semantic from Syntactic Dependencies for Thin-Air on a Single
Execution*.

**Contents.** The WEV-SMT prototype and its SMT encoder (`src/`), the Isabelle/HOL
development (`isabelle/`), the evaluation pipelines and every result file cited by the
paper (`eval/`), the bitwidth-synthesis scripts (`research/claimD/`), and the litmus
subset the measurements run over (`eval/corpus/`).

**What it validates.** That the semantic-dependency criterion the paper defines is not
cheaply approximable — the headline being Table 2, where a solver-free constant-folding
classifier over-forbids 770 of 3,656 grounded ring instances and the SMT-backed criterion
misses none — together with the mechanised results, the model-comparison baselines, and
an external Dat3M/LKMM check that the all-dependencies baseline reflects deployed tool
behaviour rather than an authored strawman.

---

## 1. Quick start

| requirement | version used | needed for |
|---|---|---|
| JDK | Temurin 21 (17+ should work) | the checker and Dat3M |
| Maven | 3.8+ | the initial compile; also copies the Z3 natives |
| Python | 3.11 (standard library only — no `pip install`) | the pipelines |
| Z3 | bundled | copied into `target/native` by the build |
| Isabelle2024 | optional | **only** to re-check the mechanisation |
| Dat3M v4.4.1 | optional, built from source | **only** to re-run the Dat3M/LKMM baseline live (§8) |

On Git-Bash for Windows use `python`, not `python3` — the latter resolves to the
non-functional Microsoft-Store stub.

```bash
./reproduce.sh --quick
```

---

## 2. One-command reproduction

```bash
./reproduce.sh
```

Full mode runs, in order: the build; the three-way LB smoke test; verification of every
shipped evidence file (Table 2 recomputed cell by cell, Wilson intervals recomputed, the
Dat3M counts, the SHA-256 manifest, and a `sorry`/`oops` scan of all 11 theories); the
five Dat3M calibration controls; the Claim D synthesis; the Isabelle build; and
regeneration of the two Java benchmarks into `out/`, each diffed against its shipped CSV.

**It writes nothing outside `out/` and `target/`.** See §9.

## 3. Reproduction levels

| command | what it does | time |
|---|---|---|
| `./reproduce.sh --quick` | prerequisites, build, LB smoke test, Dat3M PASS/FAIL semantics | **15 s measured** (warm build) |
| `./reproduce.sh --verify` | all of the shipped evidence, no long runs, no build | ~1 min |
| `./reproduce.sh` | the above plus both Java benchmark regenerations | long — see §6 |
| `./reproduce.sh --regenerate` | additionally re-runs the two **in-place** pipelines | +~45 min |

---

## 4. Claim map

| paper | claim | entrypoint | evidence |
|---|---|---|---|
| §6.2, Table 2 | 3,656/3,656 WEAKEST · 2,886 folding · 2,588 all-deps | `wev.smt.cli.HardExprBenchRun` | `eval/generated-hard-pairs.csv` |
| App. A.4 | matched-pair sweep 1,600 / 1,440 / 1,015 | `wev.smt.cli.GeneratedPairsRun` | `eval/generated-pairs-folding.csv` |
| §3.3, App. A.5 | bitwidth synthesis (Claim D) | `research/claimD/claimD_{synth,restricted,probe}.py` | `research/claimD/*.json` |
| §6.2, App. A.4 | **Dat3M/LKMM 1,350/1,350 and 390/390** | `eval/dat3m_lkmm_hardbench.py` | `eval/dat3m-lkmm-*.csv`, `eval/dat3m-lkmm-README.md` |
| §4.3, Table 1 | mechanisation, no `sorry`/`oops` | `isabelle build -d isabelle -c WEV_SMT WEV_SMT_PwT` | `isabelle/` (11 theories) |
| §6.3, App. A.4 | corpus, comparison, audit, scalability, hierarchy | `python eval/run_all.py` (19 steps) | `eval/results.json` + ~95 CSVs |

`eval/README-generated-benchmarks.md` documents the four results that sit outside
`run_all.py`; `eval/dat3m-lkmm-README.md` covers the Dat3M experiment in full.

---

## 5. Expected outputs

Dat3M/LKMM external baseline — reproduced on three independent full runs:

| quantity | expected |
|---|---:|
| total instances | 3,656 |
| Dat3M-representable (scored) | 1,350 |
| parser-excluded (`~` unsupported) | 2,306 |
| agreement with the all-dependencies baseline | **1,350 / 1,350** |
| mismatches | 0 |
| scored timeouts / tool failures / parse failures | 0 / 0 / 0 |
| grounded rings | 390 (195 expressions × 2 ring sizes) |
| grounded rings Dat3M forbids | **390 / 390** |
| grounded rings Dat3M allows | 0 |

Table 2: WEAKEST 3,656/3,656 (0 FP, 0 FN) · folding 2,886/3,656 (770 FP) · all-deps
2,588/3,656. Smoke test: `LB-real` FORBIDDEN, `LB-fake-xor-cycle` ALLOWED, `LBfd` ALLOWED.
Isabelle: `BUILD_EXIT=0`, no `sorry`, no `oops` in any of the 11 theories.

---

## 6. Runtimes

Only measured or previously documented figures are given. Where a duration was not
measured in this environment, that is stated rather than estimated.

| step | time |
|---|---|
| `--quick` | **15 s** (measured, warm build) |
| Dat3M full run, 3,656 instances | **~10 min** (measured: 593 s and 630 s) |
| Isabelle clean rebuild, both sessions | **33 s** (measured) |
| Claim D, all three scripts | seconds (measured) |
| `eval/run_all.py` | ~35 min full, ~2.5 min `--quick` (documented; scalability dominates) |
| `HardExprBenchRun`, 3,656 solver instances | **not measured in this environment** |
| `GeneratedPairsRun`, n = 2…6 | **not measured in this environment** |

---

## 7. Artifact structure

```
src/                 the WEV-SMT checker, SMT encoder and CLI drivers
isabelle/            ROOT + 11 theories: WEV_SMT and WEV_SMT_PwT
eval/                pipelines, result CSVs, READMEs, litmus examples
  run_all.py           the 19-step pipeline
  dat3m_lkmm_hardbench.py   the Dat3M/LKMM baseline driver
  generated-dat3m/     1,350 rendered Dat3M inputs + the 5 controls
  corpus/              the litmus subset the measurements run over (§8)
research/claimD/     the bitwidth-synthesis scripts and their JSON outputs
reproduce.sh         this artifact's entrypoint
out/                 created by reproduce.sh; nothing else writes here
```

---

## 8. Corpus note

`eval/corpus/` ships the **measured reproduction subset**, not the full development tree:

* the **2,972 litmus files** named by `eval/corpus-weakest-manifest-v2.csv` — the corrected
  supported-syntax fragment every corpus measurement runs over (1.5 MB);
* `eval/corpus/Dat3M/cat/` — the `.cat` memory models, including `linux-kernel.cat`;
* both upstream licence notices.

That is a few MB rather than the 327 MB working tree, most of which is Maven build output,
upstream git history, and litmus files no measurement reads.

**The Dat3M binary is not redistributed.** `dartagnan.jar` is a *thin* jar — its manifest
declares `Class-Path: libs/<31 jars>`, so it needs the 53 MB `libs/` directory beside it,
which contains native solver binaries (including `mathsat.dll`) shipped with no licence
files, plus LGPL components. Rather than guess at those terms, build Dat3M from the pinned
commit instead — see `eval/corpus/MANIFEST.md`. **Nothing else in the artifact depends on
it:** the Dat3M results ship as data, with the 1,350 rendered inputs beside them, so every
reported number is auditable without running Dat3M. `reproduce.sh` skips the live Dat3M
control checks with an explanatory message when the binary is absent.

Upstream provenance, for anyone wanting to rebuild the corpus from source:

| component | version |
|---|---|
| Dat3M / Dartagnan | v4.4.1, commit `d8de5e8ef6aa4a697ff2bbe88fa8416dfcd4ac7c` |
| herdtools7 | `7.58+1` |

```bash
cd eval/corpus/Dat3M && mvn clean install -DskipTests -pl dartagnan -am
```

Dat3M is distributed under an MIT-style licence and herdtools7 under CeCILL-B; both
notices are retained with the corresponding files.

One consequence to be aware of: the corpus-coverage figure (2,972 of 12,324 files) is
derived from the shipped manifest, so with only the subset present it cannot be recounted
from files on disk.

---

## 9. Reproducibility model

Every result CSV is shipped, and each is regenerable by the entrypoint in §4.
`reproduce.sh` **verifies before it regenerates**, for a specific reason: two pipelines —
`eval/run_all.py` and `eval/dat3m_lkmm_hardbench.py` — write their results back over the
shipped files in place, with no output-directory option, and both embed per-run timings.
Re-running them changes those files byte-wise even when every reported number reproduces
exactly, which also replaces the SHA-256 manifest in `eval/dat3m-lkmm-environment.txt`.

So they run only under `--regenerate`. Everything that can be redirected is: the two Java
benchmarks and the Claim D scripts write into `out/` and are then compared against the
shipped files.

The Dat3M driver is self-checking: it runs the five calibration controls first and aborts
without writing if any control disagrees with its expected verdict, or if any of eight
expected counts fails to reproduce. It will not emit partial or adjusted evidence.

---

## 10. Superseded material

Two manuscript copies in this tree are **older forks and are not the submitted paper**:

* `paper/wev-smt.tex` and `paper/refs.bib` — an August-6 draft (29 bibliography entries,
  `\input`s `eval/numbers.tex`);
* `submission/` — an August-6 Overleaf upload package built from that same draft.

The submitted manuscript inlines every number literally and contains no `\input`, so
`eval/numbers.tex` and `eval/tables.tex` are shipped as **provenance** — the traceable link
from each printed number back to `eval/results.json` — not as build dependencies.

This README and the guides under `eval/` describe **the artifact**, not those superseded
copies. Also superseded: `artifact/` (an earlier POPL package that diffs against a
snapshot from commit `adb65a7`; use `reproduce.sh` and `eval/run_all.py` instead) and
`eval/README-artifact.md` (v1 — see `eval/README-artifact-v2.md`).
