# §6 sdep ablation — is the dependency-classification mechanism empirically necessary?

This ablation addresses paper **risk #3** ("the dependency detector is just a regex
heuristic"). It runs the *otherwise unchanged* WEAKEST checker under three semantic-
dependency configurations and asks whether the middle one (the shipped detector) is
the only one that gets the load-buffering family right.

## Setup

* **Semantic core frozen at commit `41db4b2`.** The three configurations are obtained
  *without modifying any frozen file*: a new package `wev.smt.ablation` re-flags the
  `DependencyInfo` sidecar that `AxiomaticConsistency.jfCoherence` reads via
  `semanticEdges()`. `SdepConfig.transform` is the only knob.
  * `none` — `sdep = ∅`: every syntactic dependency re-flagged `isSemantic=false`
    (syntactic edges and their inert decision vars retained, so encoding cost is
    unchanged; only their semantic status is stripped). No-thin-air degenerates to
    `acyclic(jf)`.
  * `all-deps-semantic` — `sdep = dep`: every syntactic dependency re-flagged
    `isSemantic=true`, no fake/real stratification.
  * `current` — the shipped 10-pattern detector, returned **unchanged**
    (`transform` is the identity). `sdep_true ⊆ sdep_impl ⊆ dep`.
* **Selection mechanism:** runtime, via `WEV_SDEP_MODE` env var or the
  `configs=none,all-deps-semantic,current` CLI token on `wev.smt.ablation.AblationRun`.
  No branches, no frozen-file edits. (Driver: `src/main/java/wev/smt/ablation/`.)
* **Model:** WEAKEST only. Verdict = `isUnsat(wellFormed ∧ consistencyWEAKEST())`:
  UNSAT ⇒ FORBIDDEN, SAT ⇒ ALLOWED. Same Z3 solver, same bounds as the existing
  checker (`perFileSec=30`, `maxEvents=64`); bounds **not** raised.
* **Parity check (config = `current`):** the corpus-slice verdicts reproduce the
  recorded §6.2 WEAKEST baseline **200 / 200, zero mismatches**, and the curated cases
  reproduce `LitmusCorpus.classics()` / `PaperExampleTest` expectations exactly. By
  construction `current` *is* the unmodified checker (identity transform); this confirms
  it empirically.
* **Reproducibility:** corpus slice = 200 files drawn by `Collections.shuffle(seed=42)`
  from the **2,998-file** fully-validated §6.2 set (`697` herd7 + `2301` Dat3M, the
  WEAKEST-OK rows of `eval/corpus-validation-{herd7,dat3m}.csv`, reconstructed in
  `eval/corpus-weakest-manifest.csv`). The exact 200 paths are in `ablation-slice.txt`;
  raw per-cell rows in `ablation-raw.csv`.

## Test groups

| Group | Members (parser-ingested today) |
|---|---|
| **LB-fake** (4) | `LBdep-fake`, `3.LBdep-fake` (corpus, data dep `isSemantic=false`); `LB-fake-xor-cycle.litmus`, `LB-fake-xor.litmus` (paper §4 `r^r^1` / `r^r`) |
| **LB-real** (4) | `LBdep-real`, `LBdep-addr`, `3.LBdep-real` (corpus, real data/addr dep); `LB-real.litmus` (paper §4 `r+1`) |
| **3-thread** (3) | `ISA2`, `WRC`, `IRIW` from the regression set — **carry no dependency annotations** (`DependencyInfo.empty()`) |
| **corpus-slice** (200) | seed-42 slice of the 2,998-file §6.2 set |

> **Note on group 3.** The task named `ISA2, WRC, CYC, IRIW`. `CYC` does **not** exist
> in the wev-smt corpus (`LitmusCorpus.classics()` has no such case; it is a
> *visualiser* quiz concept). It was **not** fabricated — the group runs the three that
> exist. All three are dependency-free, so the sdep transform is a no-op on them, which
> is itself the expected outcome.

## Results — 3 × 4 table

Cell = `(#ALLOWED, #FORBIDDEN, #TIMEOUT, #ERROR)` then wall-clock for the cell.

| Config | LB-fake (4) | LB-real (4) | 3-thread (3) | corpus-slice (200) |
|---|---|---|---|---|
| **none**              | (4, 0, 0, 0) — 358 ms\* | (4, 0, 0, 0) — 73 ms | (3, 0, 0, 0) — 95 ms | (194, 6, 0, 0) — 3474 ms |
| **all-deps-semantic** | (1, 3, 0, 0) — 64 ms  | (0, 4, 0, 0) — 63 ms | (3, 0, 0, 0) — 50 ms | (194, 6, 0, 0) — 3207 ms |
| **current**           | (4, 0, 0, 0) — 66 ms  | (0, 4, 0, 0) — 59 ms | (3, 0, 0, 0) — 33 ms | (194, 6, 0, 0) — 3003 ms |

\* The `none`/LB-fake cell ran first and absorbs JVM + first Z3-context warm-up; it is
not a per-solve cost (max single solve across the whole run was 49 ms).

Total measured solve+context wall-clock across all three configs ≈ **10.6 s** — far
inside the 4-hour budget, so the slice was **kept at 200** (no reduction needed).

## Batty et al. §4 control examples (separate table)

These are new `.litmus` files added for this ablation (`eval/examples/paper/`); the
wev-smt C parser flattens the conditional store to *branch-on-read → constant store* and
records the `read → store` control edge `isSemantic=true` unconditionally
(`LitmusParser:593`, `dep_ctrl ⊆ sdep_impl`). Cell = verdict.

| Config | LB+ctrldata+ctrl-double | LB+ctrldata+ctrl-single |
|---|---|---|
| **none**              | ALLOWED   | ALLOWED   |
| **all-deps-semantic** | FORBIDDEN | FORBIDDEN |
| **current**           | **FORBIDDEN** | **FORBIDDEN** |

**§7 load-bearing check passes:** `LB+ctrldata+ctrl-double` is **FORBIDDEN** under
`current` (not ALLOWED). The §7 Batty paragraph — that the detector conservatively
folds control dependencies into `sdep` and so over-forbids the same-value-both-branches
case rather than admitting it — is consistent with the measured verdict. **No STOP
condition triggered.**

## Divergences from `current` (one sentence each)

* **LB-real / `none` — (4,0,0,0) vs (0,4,0,0): all 4 diverge.** Stripping the real
  data/addr dependency from `sdep` removes the only edge that closes the
  `sdep ∪ jf` load-buffering cycle, so the genuine out-of-thin-air execution becomes
  consistent — `none` **admits thin-air**, exactly the failure the mechanism exists to
  prevent.
* **LB-fake / `all-deps-semantic` — (1,3,0,0) vs (4,0,0,0): 3 diverge.** Treating the
  fake (identity-XOR / value-irrelevant) dependency as semantic re-introduces an
  `sdep` edge that closes the cycle, so `LBdep-fake`, `3.LBdep-fake` and
  `LB-fake-xor-cycle` are **over-forbidden** — `all-deps-semantic` rejects grounded
  executions WEAKEST should permit. (The 4th, `LB-fake-xor.litmus`, stays ALLOWED: its
  `r^r` folds to a constant-0 write, so the wired candidate is the degenerate all-zeros
  outcome with no LB cycle to forbid regardless of `sdep` — a documented wiring
  artifact, not a sdep effect; see `PaperExampleTest`.)
* **Batty (both) / `none` — ALLOWED vs FORBIDDEN: both diverge.** Stripping the control
  dependency from `sdep` admits the control-mediated LB cycle — `none` again admits
  thin-air, here via a `ctrl` edge.
* **3-thread — no divergence (all three identical).** The cases carry no dependency
  annotations, so the transform is a no-op; correct and expected.
* **corpus-slice — no divergence (0 of 200 files differ across configs).** 14 of the
  200 sampled files carry syntactic dependency edges (mostly PPC `rfi-data` chains),
  but every file's WEAKEST verdict is identical under all three configs — including
  the 3 dep-bearing files that are FORBIDDEN (`LB+PPO0196`, `DETOUR0388`, `DETOUR0181`),
  which are forbidden under `none` too. Their forbidding comes from per-location
  coherence (`coherencePerLocation`, config-independent), **not** from the `sdep ∪ jf`
  cycle, so re-flagging dependencies cannot move them.

## Surprises

**None.** Every verdict matches the expected baseline:

* LB-fake/`current` = all ALLOWED; LB-real/`current` = all FORBIDDEN.
* 3-thread/`current` = ISA2/WRC/IRIW all ALLOWED, matching the regression baseline.
* corpus-slice/`current` = 200/200 identical to the recorded §6.2 manifest.
* Batty double & single /`current` = FORBIDDEN (the §7-safe outcome).
* No TIMEOUT and no ERROR in any cell.

The only result worth pre-empting as a possible surprise — `LB-fake-xor.litmus`
remaining ALLOWED under `all-deps-semantic` despite carrying two now-"semantic" edges —
is **not** a model surprise: that file's candidate execution is the degenerate
all-zeros wiring (the store value `r^r` evaluates to 0, so no `r=r=1` LB cycle is
wired), and it is already documented as trivially-SAT in `PaperExampleTest`. It is
therefore not flagged 🔴.

## What §6 prose this supports

> **§6.x  Ablating the dependency classifier.** To test whether the semantic-dependency
> stratification is doing real work — rather than the no-thin-air axiom alone — we ran
> the WEAKEST checker under three configurations of `sdep`, leaving the rest of the
> encoding fixed: `none` (`sdep = ∅`), `all-deps-semantic` (`sdep = dep`, every syntactic
> dependency retained), and the shipped detector (`sdep_true ⊆ sdep_impl ⊆ dep`). The
> configuration is a runtime flag over a re-flagged dependency sidecar; the shipped
> configuration reproduces the unmodified checker on all 200 sampled corpus files
> (200/200). On the load-buffering family the two endpoints fail in opposite,
> predictable directions: with `sdep = ∅` all four real-dependency LB tests
> (`LBdep-real`, `LBdep-addr`, `3.LBdep-real`, the `r+1` paper example) become
> consistent — the checker admits out-of-thin-air — and with `sdep = dep` three of the
> four fake-dependency tests become inconsistent, rejecting grounded executions WEAKEST
> permits. The shipped detector is the only configuration that allows every fake-
> dependency LB and forbids every real-dependency LB. The two Batty et al. control
> examples behave as their §7 discussion predicts: the conservative inclusion of control
> dependencies forbids both the single- and the same-value-double-branch cycle under the
> shipped detector, whereas `none` admits them. On the 200-file public-corpus slice all
> three configurations agree, because no sampled file's verdict is decided by the
> dependency cycle — the dependency-bearing files are resolved by per-location coherence,
> which is independent of `sdep`. We therefore claim only what the data support: the
> stratification is **necessary on the load-buffering / thin-air family**, where removing
> or coarsening it flips verdicts in opposite unsound directions; on the broader public
> corpus it is verdict-neutral at this sample size, and the mechanism's cost is
> negligible (no solve exceeded 50 ms, no timeouts, no errors). We make no claim that the
> regex detector is complete — only that, on the cases where dependencies are the
> deciding factor, it is empirically the right middle point between the two unsound
> extremes.

---

### Artifacts

| File | Contents |
|---|---|
| `src/main/java/wev/smt/ablation/SdepConfig.java` | the sdep runtime flag (`none`/`all-deps-semantic`/`current`), zero frozen-file touch |
| `src/main/java/wev/smt/ablation/AblationRun.java` | the driver |
| `eval/examples/paper/LB+ctrldata+ctrl-{double,single}.litmus` | the two Batty §4 control examples (new) |
| `eval/corpus-weakest-manifest.csv` | reconstructed 2,998-file §6.2 manifest with baseline verdicts |
| `ablation-raw.csv` | one row per (config, group, case/file): verdict, ms, `semanticEdges`, `totalDeps` |
| `ablation-slice.txt` | the exact seed-42 200-file slice (reproducible) |
| `ablation-run.log` | full console output |

**Reproduce:**

```
mvn -q compile
java -Djava.library.path=target/native \
     -cp "target/classes;$(cat target/ablation-cp.txt)" \
     wev.smt.ablation.AblationRun configs=none,all-deps-semantic,current slice=200 seed=42
```
