## WEV-SMT Artifact — TACAS 2027

Artifact for the WEAKEST semantic-dependency checker. Every empirical claim in the
paper maps to a CSV in `eval/` and a command that regenerates it.

**Scope note.** This document is the claim-to-command index. It supersedes the older
POPL-era `artifact/README.md` + `artifact/reproduce.sh`, which cover only a subset
(unit tests, atlas, scalability, robustness, plots) and still diff against a snapshot
from commit `adb65a7`. Every claim below now has a runnable producer. Commands were
transcribed from each tool's argument parsing and, where noted, re-executed against
the current tree; see §Verification status for exactly which.

### Getting started (smoke test, ~10 s)

```
bash eval/smoke-test.sh              # Linux / macOS / Git Bash
pwsh -File eval/smoke-test.ps1       # Windows PowerShell
```

Builds the project and checks the three-way LB separation that is the paper's central
claim. Expected output (verified; 4 s on Windows, 8 s under Git Bash — the 60 s budget
is not close to binding):

```
  [PASS] LB-real -> FORBIDDEN
  [PASS] LB-fake-xor-cycle -> ALLOWED
  [PASS] LBfd -> ALLOWED
=== SMOKE TEST PASSED (3/3) ===
```

`LB-real` carries a genuine (semantic) dependency, so the thin-air cycle is real and
WEAKEST forbids it. `LB-fake-xor-cycle` (`r^r^1`) and `LBfd` (fake linear dep) carry
dependencies that fold to constants, so no semantic cycle exists and WEAKEST allows
them. That FORBIDDEN/ALLOWED split *is* the contribution.

> **Do not substitute `LB-fake-xor.litmus` for `LB-fake-xor-cycle.litmus`.** It writes
> `r^r` = 0, so the parser finds no write of 1, both reads default to the initial
> write, and the LB cycle is never wired — *every* model trivially allows it. It
> would pass the smoke test for entirely the wrong reason. `LB-fake-xor-cycle`
> (`r^r^1`, folds to the constant 1) wires the cycle, so its ALLOWED is a model
> verdict rather than a parse artifact.

### Claim-to-artifact mapping

| Paper claim | CSV file | Reproducing command |
|---|---|---|
| 0/2998 hierarchy violations | `eval/corpus-validation-dat3m.csv` + `eval/corpus-validation-herd7.csv` | `CorpusValidation` ×2 — §C1 |
| 31/34 published-example agreement | `eval/source-paper-results.csv` (14) **+** `eval/jmm-results.csv` (20) | `wev.smt.ablation.PaperExamplesRun` ×3 dirs — §C2 |
| 80/80 PwT equivalence | `eval/pwt-equivalence-sweep.csv` | `wev.smt.cli.PwtEquivalenceSweep` — §C3 |
| 2 discriminating corpus tests | `eval/sdep-discriminating-all-fake-tests.csv` | `wev.smt.ablation.SdepFakeDiscriminatingRun` — §C4 |
| Dat3M 31/41 agreement | `eval/dat3m-comparison.csv` | `wev.smt.cli.WevBatch` + external Dat3M — §C5 |
| Scalability (WEAKEST near-linear) | `eval/scalability-consistency.csv` | `mvn exec:exec@scalability-sweep` — §C6 |
| RC11 6 divergences | `eval/rc11-direction-check.csv` | `wev.smt.cli.Rc11DirectionCheck` — §C7 |
| QF_BV detector 0 verdict changes | `eval/qfbv-detector-results.csv` | `wev.smt.cli.QfbvDetectorRun` — §C8 |
| PSO canonical 2/40 divergences | `eval/pso-canonical-comparison.csv` | `wev.smt.cli.PsoCanonicalComparison` — §C9 |
| n-thread grounded admission ALLOWED | `eval/nthread-grounded-admission.csv` | `wev.smt.cli.NThreadGroundedAdmission` — §C10 |

### Build instructions

Requires JDK 21+ and Maven 3.9+. Z3 ships as a Maven dependency; the platform-activated
`z3-natives-*` profile in `pom.xml` copies the native libraries into `target/native`.

```
mvn package -DskipTests
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

**Z3 native library path.** Every direct `java` invocation must set it, or JavaSMT
fails to load Z3 at `SolverContextFactory.createSolverContext`:

```
# Windows (PowerShell)
$cp = "target/classes;" + (Get-Content target/cp.txt)
java "-Djava.library.path=target\native" -cp $cp wev.smt.cli.<Main> <args>

# Linux / macOS
CP="target/classes:$(cat target/cp.txt)"
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.<Main> <args>
```

The quoting on `"-Djava.library.path=..."` matters in PowerShell — unquoted, PowerShell
splits the argument and the JVM silently receives a truncated path. The `mvn exec:exec@*`
profiles set the library path themselves; only direct `java` runs need the flag.

Note the classpath separator differs (`;` Windows, `:` Unix) and must match the one
Maven wrote into `cp.txt`. `eval/smoke-test.sh` infers it from the file so it also
works under Git Bash on Windows.

### Full reproduce commands for each claim

Commands below use the Unix form; substitute the PowerShell classpath line above on
Windows. All are run from the repo root.

#### C1 — 0/2998 hierarchy violations

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.CorpusValidation \
     eval/corpus/herdtools7 eval csv=corpus-validation-herd7.csv budgetMin=60 perFileSec=30
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.CorpusValidation \
     eval/corpus/Dat3M eval csv=corpus-validation-dat3m.csv budgetMin=60 perFileSec=30
```

Expected: 697 validated files (herd7) + 2301 (Dat3M) = **2998**, **0** hierarchy
violations. Each parseable file is checked under all five models; the checks are
`SC ⊆ TSO ⊆ PSO` and `{SC,TSO,PSO} ⊨ ALLOWED ⟹ WEAKEST ⊨ ALLOWED`. Long-running
(~1 h/corpus); the violation count is budget-independent, but the *validated-file*
count is not — a short budget yields fewer files and a smaller denominator.
`artifact/reproduce.sh` Step 5 re-derives the violation count from the CSV via awk.

#### C2 — 31/34 published-example agreement

The 34 published examples span **two** CSVs, and the claim only reconciles across both:

| CSV | examples | agree | source |
|---|--:|--:|---|
| `eval/source-paper-results.csv` | 14 | **13** | Weakestmo 8 (Chakraborty & Vafeiadis, POPL 2019) + PwT / Leaky-Semicolon 6 (Jeffrey et al., POPL 2022) |
| `eval/jmm-results.csv` | 20 | **18** | JMM causality tests TC01–TC20 (Pugh 2004) |
| **total** | **34** | **31** | **agreement 31/34 = 91.2 %** |

Both are produced by the same driver over different example directories:

```
# source-paper-results.csv (14 examples: Weakestmo 8 + PwT 6)
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.ablation.PaperExamplesRun \
     dir=eval/examples/weakestmo out=eval/source-paper-results.csv
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.ablation.PaperExamplesRun \
     dir=eval/examples/leaky-semicolon out=eval/source-paper-results.csv

# jmm-results.csv (20 examples: TC01-TC20)
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.ablation.PaperExamplesRun \
     dir=eval/examples/jmm out=eval/jmm-results.csv
```

Verified by counting the committed CSVs: `match=yes` on 13/14 and 18/20 respectively.
**Cite both files together** — `eval/source-paper-summary.md` reports 13/14 = 92.9 % and
`eval/jmm-taxonomy.md` reports 18/20 for their own subsets, so quoting either summary
alone will not reproduce the paper's 31/34. The 4 disagreements are analysed in those
two documents; per `jmm-taxonomy.md` the JMM set is "a taxonomy, not a match rate" —
the *mechanism* behind each verdict is the contribution, not the ratio.

#### C3 — 80/80 PwT equivalence

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.PwtEquivalenceSweep \
     eval/corpus-weakest-manifest.csv eval/corpus eval/pwt-equivalence-sweep.csv
```

Expected: 80 rows, `agree=true` on **all 80** (verified: `80 true`, 0 false). Confirms
`sdep_impl = sdep_true` on the branch-free fragment.

#### C4 — 2 discriminating corpus tests

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.ablation.SdepFakeDiscriminatingRun \
     out=eval timeoutSec=30 maxEvents=64
```

Expected: 231 fake-carrying tests, `discriminating=true` on exactly **2** (verified:
`2 true`, 229 false) — `LB+datas` (PPC) and `DETOUR0236` (PPC). Needs the native
library path. Note the mutation study (`eval/mutation-summary.md`) does **not** raise
this count: it mechanically confirms 1 of the 2 and misses `DETOUR0236`; the honest
headline stays **2 independently-authored discriminators**.

#### C5 — Dat3M 31/41 agreement

Two-part; the Dat3M half needs an external tool and is not self-contained.

```
# WEV half (self-contained):
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.WevBatch \
     eval/examples/paper/*.litmus eval/examples/jmm/*.litmus \
     eval/examples/weakestmo/*.litmus eval/examples/leaky-semicolon/*.litmus

# Dat3M half: Dat3M @ commit d8de5e8e (v4.4.1), built with
#   mvn clean install -DskipTests -pl dartagnan -am
# Exact invocations are recorded in eval/dat3m-command-lines.txt
```

Expected: 41 tests, **31 agree (75.6 %)**, 10 disagreements, 0 Dat3M errors/timeouts.
Two calibration points a reviewer must not skip: Dartagnan `PASS` = `ALLOWED` and
`FAIL` = `FORBIDDEN` (the *opposite* of the naive reading), and the model is LKMM
(`--target=lkmm`) because IMM/RC11 reject `READ_ONCE`/`WRITE_ONCE` programs outright.
All 10 disagreements are analysed in `eval/dat3m-summary.md` §"Disagreement analysis".

#### C6 — Scalability (WEAKEST near-linear)

```
mvn -o -q exec:exec@scalability-sweep -Dsweep.budget=8 -Dsweep.percall=30
```

Writes `eval/scalability-consistency.csv`. Verdict columns are budget-independent and
diffable against `artifact/expected-outputs/scalability-consistency-expected.csv`
(`artifact/reproduce.sh` Step 3); **timing columns are machine-dependent and must not
be diffed** — the near-linear trend is the claim, not the absolute milliseconds.

#### C7 — RC11 6 divergences

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.Rc11DirectionCheck \
     eval/rc11-direction-check.csv eval/examples/paper eval/examples/jmm
```

(Both arguments are optional; the defaults are exactly those paths.)

Expected, and **verified byte-for-byte against the committed CSV during this packaging**:
27 tests (7 paper + 20 jmm), **21 agree**, **6 `rc11_forbids_weakest_allows`**, and
**0 `rc11_allows_weakest_forbids`**. The tool prints the tally and **exits non-zero** if
the unexpected direction or any error row ever appears, so a regression breaks a
reproduction run instead of being buried in a CSV.

That 0 is the actual claim. RC11's `acyclic(po ∪ rf)` is strictly stronger than
WEAKEST's `acyclic(sdep ∪ jf)` on this fragment (`sdep ⊆ po` and `jf ⊆ rf`), so
divergence *must* be one-directional — RC11 over-restricts grounded/fake LB shapes
that WEAKEST recovers. The 6 are `LB-fake-xor-cycle`, `LBfd`, `TC01`, `TC06`, `TC12`,
`TC18`. RC11 here is the deliberately simplified `acyclic(po ∪ rf)` — no
release/acquire, as our litmus format carries none.

Row order is by filename within each directory, which is what makes the output
byte-comparable. This puts `LB-fake-xor-cycle` before `LB-fake-xor` (`'-'` 0x2D sorts
before `'.'` 0x2E) — if you re-order the rows, the byte comparison breaks even though
the result is unchanged.

Distinct from `wev.smt.ablation.Rc11ComparisonRun`, which writes `eval/rc11-comparison.csv`
— a narrower hand-picked §6.4 panel (6 tests, different columns) carrying per-test
wiring diagnostics. Both are legitimate; they answer different questions.

#### C8 — QF_BV detector, 0 verdict changes

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.QfbvDetectorRun \
     eval/corpus-weakest-manifest.csv eval/corpus \
     eval/qfbv-detector-results.csv eval/qfbv-summary.md
```

Expected: 2998 rows, `changed=false` on **all 2998** (verified: `2998 false`, 0 true).
Method mix: 2044 `none`, 785 `qfbv`, 101 `conservative`, 68 `pattern`. Installing the
exact QF_BV constancy oracle as the primary detector changes **no** verdict.

#### C9 — PSO canonical 2/40 divergences

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.PsoCanonicalComparison \
     eval/pso-canonical-comparison.csv
```

Expected, and **verified by re-running against a scratch file during this packaging**:
40 tests, `differs=true` on exactly **2**; the tool prints `2 of 40 tests differ.`

#### C10 — n-thread grounded admission

```
java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.NThreadGroundedAdmission \
     eval 2 8
```

Expected, and **verified by re-running during this packaging**: n = 2..8 (events 6..24),
verdict **ALLOWED** at every n; the tool prints `All ALLOWED under WEAKEST: YES`.
`fullexec_ms` is machine-dependent and must not be diffed.

### Known gaps (must close before submission)

1. **Build was broken at `HEAD` (`7a3bb75`) — fixed in this working tree, uncommitted.**
   `LitmusCorpus.java` referenced an undeclared `lbFake`: commit `48a4a80`, a
   comment-stripping pass, deleted the comment block *and* the adjacent code line
   `EsDeps lbFake = buildLBFakeDep();` (the helper survived, orphaned, at line 611).
   `mvn package` failed on a clean tree for the last three commits, meaning results
   committed since were produced against a stale `target/classes`. The one-line
   restoration is applied here but **not committed**. An artifact that does not
   compile fails Functional outright, so this must be committed first — along with
   the new `Rc11DirectionCheck.java` and the three files in `eval/`.
2. The older `artifact/reproduce.sh` diffs against a snapshot from commit `adb65a7`
   and expects `45/45` unit tests; both should be refreshed against the current tree,
   and its 7 steps folded into (or cross-referenced from) this document. Adding
   §C7 to it is cheap and worthwhile — `Rc11DirectionCheck` already self-checks via
   its exit code.
3. Six of the ten claims (§C1, C3, C4, C5, C6, C8) have not been re-executed
   end-to-end against the current tree — see §Verification status. §C1 in particular
   is ~2 h and worth running once before submission, since its validated-file
   denominator (2998) is budget-sensitive in a way the violation count is not.

**Closed during this packaging:** the §C2 claim/CSV mismatch (31/34 reconciles across
two CSVs — it was never wrong, just under-documented) and the §C7 missing producer
(`Rc11DirectionCheck`, reproducing the committed CSV byte-for-byte).

### Verification status of this document

**Re-executed against the current tree** (after the `LitmusCorpus` build fix):

- the build itself — `mvn package -DskipTests` green;
- both smoke-test scripts, end-to-end, 3/3 passing (4 s PowerShell, 8 s Git Bash);
- **§C7** — output diffed byte-for-byte against the committed
  `eval/rc11-direction-check.csv`: identical;
- **§C9** — `2 of 40 tests differ`, matching the committed CSV;
- **§C10** — all-ALLOWED for n = 2..8, matching the committed CSV.

**Re-derived by counting the committed CSVs** (row counts and column tallies, not
copied from prose): §C1 (2998 files / 0 violations), §C2 (13/14 + 18/20 = 31/34),
§C3 (80/80), §C4 (2 of 231), §C5 (31/41), §C8 (0 of 2998).

**Not re-run end-to-end here:** §C1, C3, C4, C5, C6, C8 — all long-running (§C1 alone
is ~2 h across both corpora, §C5 additionally needs an external Dat3M build). Their
commands are transcribed from each tool's argument parsing, not from a fresh
execution, and should be exercised once before submission.
