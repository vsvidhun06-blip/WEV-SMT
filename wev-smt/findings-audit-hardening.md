# findings-audit-hardening.md

Permanent differential detector audit. Engineering record.

The detector is frozen. Nothing in `classify()`, the parser, the encoder, the Isabelle
theories or the paper was modified in this work.

---

## 1. Methodology

For every audited pair `(expr, reg)` the audit compares two independently computed
verdicts:

| | |
|---|---|
| `detector_verdict(e,r)` | the shipped `LitmusParser.Job.classify` path with `CONSTANCY_ORACLE = null` — idiom folding followed by linear-cancellation normalisation |
| `oracle_verdict(e,r)` | `QfbvConstancyOracle.isConstant(e,r)` — an exact QF_BV constancy query over BV32 |

`classify` is private to a private nested class. The audit reaches it by reflection. It
**observes** the shipped method; no accessor was added to the parser and no production
logic was changed.

### Two divergence directions, only one of which is a defect

Strict equality of the two verdicts is not the shipped contract and does not hold. The
solver-free fallback is documented to bail to `REAL` whenever the linear pass cannot
normalise an expression, so it is deliberately less precise than the oracle. The audit
therefore separates:

| class | detector | oracle | meaning | build |
|---|---|---|---|---|
| **UNSOUND** | `FAKE` | VARIES | a value-carrying dependency was dropped from `sdep`; removing an edge can break a `jf ∪ sdep` cycle and turn FORBIDDEN into ALLOWED | **FAILS** |
| **CONSERVATIVE** | `REAL` | CONSTANT | an edge was kept that need not have been; the documented fallback behaviour | passes, count **pinned** |

The raw strict-equality mismatch count is reported as `unsound + conservative`, so the
number originally asked for is visible rather than defined away.

The conservative count is pinned (`--max-conservative` in the CLI, `CONSERVATIVE_PIN` in
the unit test) so that a silent **precision** regression also fails the build, not only a
soundness regression.

---

## 2. Audited population

Two independent sources.

**(a) Enumerated.** Flat expressions with 1–2 binary operators over
`{+, −, &, ^, |, *}`, atoms `r0`, `~r0`, constants `{0,1,2,3,7,8,255}` and their `~`
forms; only expressions mentioning `r0` are kept. Deterministic order, capped by
`--limit` so the audited set is reproducible. Plus 8 **pinned regression witnesses** — the
precedence-spanning expressions from the 2026-07-28 folding defect — retained so they are
tested even if the enumeration alphabet is ever narrowed.

**(b) Corpus.** Every `(expr, reg)` pair the parser actually hands to `classify` while
parsing the supported-syntax fragment, harvested through `LitmusParser.DEP_TRACE`.

Measured harvest over the fragment:

```
files parsed              2972
parse errors                 0
total classify() calls    3210
distinct (expr, reg)        98
```

98 distinct pairs from 3,210 calls: corpus expressions are highly repetitive. The most
frequent are bare registers (`r1` ×1062, `r3` ×932), the cancellation idiom (`r1^r1` ×123,
`W0^W0` ×35), increments (`r3+1` ×123) and the guard form `(r0 != 0)` ×79.

> Observed while harvesting: the expression `W2+MOV W2` appears 30 times. That is the
> known `stripImm`/`lexRegisterAsm` defect (a `#`-immediate is not stripped, `#` trips
> comment handling and the row is swallowed) showing up in real corpus data. It is a
> pre-existing front-end defect, unrelated to this audit, and is recorded in §6.

---

## 3. Mismatch count — current tree

Full sweep (`eval/run_all.py`, step `detector_oracle_audit`):

```
enumerated expressions checked   : 12008
corpus (expr,reg) pairs checked  : 98
total audited                    : 12106
outside the oracle grammar       : 0 (not comparable)
strict-equality mismatches       : 990
  of which CONSERVATIVE (sound)  : 990
  of which UNSOUND (defect)      : 0
wall seconds                     : 119.0
RESULT: PASS
```

**Unsound divergences: 0 over 12,106 audited pairs.** Every strict-equality mismatch is in
the conservative direction. The most common conservative shapes are multi-operator bitwise
constants the linear pass cannot normalise:

| shape | count | example |
|---|---:|---|
| `\|\|~` | 32 | `r0 \| 0 \| ~0` |
| `\|~\|~` | 31 | `r0 \| ~0 \| ~1` |
| `\|~\|` | 25 | `r0 \| ~0 \| 0` |
| `&&~` | 24 | `r0 & 0 & ~r0` |
| `&~\|~` | 24 | `r0 & ~0 \| ~0` |
| `&&` | 17 | `r0 & 1 & 0` |

Per-divergence detail: `eval/detector-oracle-audit.csv`.

This is a statement about the audited population only. It is not a claim that the detector
is sound.

---

## 4. Regression demonstration

The two fix hunks in `LitmusParser.classify` were temporarily reverted (`foldSubterms(...)`
→ `e.replaceAll(bounded(...), ...)`, i.e. pre-2026-07-28 behaviour), the tree rebuilt, both
gates run, and the fix then restored.

### BEFORE fix (reverted detector)

```
total audited                    : 12106
strict-equality mismatches       : 1239
  of which CONSERVATIVE (sound)  : 946
  of which UNSOUND (defect)      : 293
RESULT: FAIL          (exit code 1)
```

**293 unsound divergences across 55 distinct expression shapes.** All 8 pinned regression
witnesses were caught:

```
~r0 - r0            r0 ^ r0 + 1         r0 & ~r0 + 1        r0 & r0 - r0 + 3
~7 - r0 ^ r0        ~r0 ^ r0 + r0 ^ r0  ~r0 & r0 ^ r0       t0 ^ t0 & 7 & 7
```

Minimal counterexample per shape (extract):

| shape | cases | minimal counterexample |
|---|---:|---|
| `&+` | 6 | `r0 & 0 + 1` |
| `&-` | 6 | `r0 & 0 - 1` |
| `&~+` | 7 | `r0 & ~r0 + 1` |
| `&~*` | 6 | `r0 & ~r0 * 2` |
| `~-` | — | `~r0 - r0` |
| `~-^` | 8 | `~r0 - r0 ^ 0` |
| `~\|~` | 1 | `~r0 \| ~r0` |
| `*&~` | 1 | `r0 * r0 & ~r0` |

The unit gate failed identically:

```
DetectorOracleAuditTest.enumeratedExpressionsHaveNoUnsoundDivergence
  UNSOUND divergences (detector=FAKE, oracle=VARIES):
  [~r0 - r0, ~r0 & ~r0, ~r0 | ~r0, ~0 & r0, ~0 * r0]
BUILD FAILURE
```

### AFTER fix (current tree)

```
total audited                    : 12106
  of which UNSOUND (defect)      : 0
RESULT: PASS
mvn -o test                      : 59/59, BUILD SUCCESS
```

### Tree cleanliness

The revert was restored and verified by checksum against the manifest recorded in the
previous session:

```
LitmusParser.java sha256[:16] = be8f33384561a8d7
expected (eval/artifact-refs/MANIFEST.json) = be8f33384561a8d7   RESTORED CORRECTLY
occurrences of reverted code (replaceAll(bounded(toZero...)))  : 0
occurrences of the fix       (foldSubterms(e, bounded(toZero...))): 1
```

No reverted code remains in the tree.

---

## 5. CI integration

Two tiers, sharing the same alphabet and comparison logic.

| tier | what runs | population | wall | fails on |
|---|---|---|---|---|
| **every `mvn test`** | `wev.smt.parse.DetectorOracleAuditTest` | 8 pinned witnesses + 600 enumerated | ~5.5 s | any unsound divergence; any change to the pinned conservative count (95) |
| **`python eval/run_all.py`** | step `detector_oracle_audit` → `wev.smt.cli.DetectorOracleAudit` | 12,008 enumerated + 98 corpus | ~119 s | any unsound divergence (`exit 1` → the step raises and the run aborts) |

The unit tier runs automatically as part of normal regression testing — no separate
invocation. The full tier is a first-class `run_all` step and its numbers are reported in
the standard summary:

```
differential detector audit        0 UNSOUND / 12106 audited (12008 enumerated + 98 corpus)
  of which conservative (sound)    990 (detector REAL, oracle CONSTANT)
```

and recorded in `eval/results.json` under `detector_oracle_audit` (`total_audited`,
`enumerated_checked`, `corpus_pairs_checked`, `strict_equality_mismatches`,
`conservative`, `unsound`, `outside_grammar`).

Documentation stating the contract appears in three places — the class javadoc of
`DetectorOracleAudit`, the class javadoc of `DetectorOracleAuditTest`, and the docstring of
`step_detector_oracle_audit`:

> "This audit checks that the shipped detector and the exact SMT oracle agree on every
> audited expression. Any divergence fails the build."

with the unsound/conservative distinction spelled out immediately below in each.

A wider nightly sweep is available via `--limit 0` (uncapped enumeration); the cap exists
only because the full depth-2 cross-product is ~10⁵ expressions and every one costs a Z3
query.

---

## 6. Coverage matrix

Measured, not inferred — each row was verified by feeding a **known-fake** idiom into that
position and observing whether the resulting edge came out fake:

| dependency class | construction site | is `classify` called? | is its verdict used for the edge? | audit effective? |
|---|---|---|---|---|
| **DATA** | `addDataDeps(wr, expr, env)` → `referencedReads` | yes, on the store value expression | **yes** | **yes** |
| **ADDR** | `addAddrDeps(consumer, in, env)` → `RegSrc.reads` of the index register | yes, on the *defining assignment* of the index register | **yes** (the flag stored in `RegSrc`) | **yes** |
| **CTRL** | `case BRANCH` → `readsOf(referencedReads(guard, env))`, then `deps.addCtrlDep(wr, cr, true)` | yes, on the guard text | **no — discarded** | **no** |

Measurement:

```
DATA   store r0 ^ r0        classify('r0 ^ r0', r0) = FAKE   ->  edge DATA=fake    verdict consumed
ADDR   EOR W3,W0,W0; LDR W4,[X2,W3]
                            classify('W0^W0', W0)   = FAKE   ->  edge ADDR=fake    verdict consumed
ADDR   ADD W3,W0,1;  LDR W4,[X2,W3]
                            classify('W0+1',  W0)   = REAL   ->  edge ADDR=SEM     verdict consumed
CTRL   if (r0 ^ r0)         classify('if (r0 ^ r0)', r0) = FAKE -> edge CTRL=SEM   verdict DISCARDED
CTRL   if (r0)              classify('if (r0)', r0)      = REAL -> edge CTRL=SEM   verdict DISCARDED
```

### Uncovered: control dependencies

**Why.** `readsOf(...)` takes only the keys of the `ReadEvent → Boolean` map that
`referencedReads` returns, discarding the semantic flag. The edge is then written with a
hard-coded `true`:

```java
case BRANCH -> ctrlReads.addAll(readsOf(referencedReads(in.expr(), regEnv)));
...
for (ReadEvent cr : ctrlReads) deps.addCtrlDep(wr, cr, true);
```

**Code path.** `LitmusParser.Job.buildThread` → `case BRANCH` → `referencedReads` (which
*does* call `classify`, so the guard expression **is** in the audited population) →
`readsOf` discards the verdict → `addCtrlDep(..., true)`.

**Consequence for this audit.** The guard expression is audited, but a control-dependency
misclassification cannot manifest as a wrong edge, so the audit cannot detect one. Equally,
because the flag is hard-coded `true`, the *unsound-drop* failure mode this audit exists to
catch **cannot occur on control edges at all** — a ctrl edge is never demoted to fake. The
gap is therefore a limitation in what the audit can observe, not an unguarded failure mode.

**Intentional exclusion or future work?** Neither, precisely: it is a pre-existing
front-end limitation, documented elsewhere in the repository as the `LBfc` case (both arms
of a guard writing the same value are flattened, and the ctrl edge is recorded semantic
unconditionally). It is an over-approximation. Making control edges classification-driven
would be a **detector change**, which is out of scope here — the detector is frozen. It is
recorded as future work in §7.

### Other limits of the audited population

- **Outside the oracle grammar**: 0 pairs in the current run. Expressions the oracle cannot
  model (`isConstant` returns `null`) are counted and excluded from comparison rather than
  silently skipped. Shifts and division are not in the modelled grammar; none occurred.
- **RMW sites**: `case RMW` records no data dependency (the loaded value flows on as a
  constant placeholder), so no `classify` call arises and there is nothing to audit.
- **Multi-register expressions**: the enumeration uses a single register `r0`. Corpus
  expressions with several registers are audited per register through population (b), but
  the synthetic enumeration does not generate them.
- The audit compares against the **QF_BV oracle**, so it inherits the oracle's own
  modelling assumptions (BV32, wrapping arithmetic). It cannot detect an error common to
  both the detector and the oracle.

---

## 7. Recommendations

1. **Make the audit able to see control dependencies.** Currently `addCtrlDep(wr, cr, true)`
   discards the `classify` verdict. Propagating it (as DATA and ADDR already do) would put
   control edges inside the audit's reach. This is a detector/front-end change and was not
   made here.
2. **Move the `!mentionsReg` short-circuit in `classify` to after the `CONSTANCY_ORACLE`
   consultation.** At present the fold returns `FAKE` before the oracle is consulted, so an
   installed exact oracle cannot override a folding mistake. This is a ~3-line change,
   carried over from the previous session's recommendation and still open. It would also
   make `QfbvDetectorRun` sensitive to folding defects, which it currently is not.
3. **Re-pin deliberately.** `CONSERVATIVE_PIN = 95` (unit tier) and the 990 reported by the
   full tier are measurements of the current detector's precision. If the fallback is ever
   changed intentionally, both must be re-measured and re-pinned in the same commit.
4. **Consider a nightly uncapped run** (`--limit 0`) so the enumeration cap does not become
   a permanent blind spot. The cap is a CI-time compromise, not a statement about which
   expressions matter.
5. **The `stripImm` defect is visible in corpus data** (`W2+MOV W2`, 30 occurrences). It
   pre-dates this work and is unrelated to the folding fix, but it means those 30 classify
   calls are made on a corrupted expression string. Worth fixing independently.

---

## Artifacts

| path | contents |
|---|---|
| `src/main/java/wev/smt/cli/DetectorOracleAudit.java` | full-sweep audit driver (new) |
| `src/test/java/wev/smt/parse/DetectorOracleAuditTest.java` | fast build gate, runs on every `mvn test` (new) |
| `eval/run_all.py` | new step `detector_oracle_audit` + summary rows |
| `eval/detector-oracle-audit.csv` | every divergence, with kind, shape and origin |
| `eval/results.json` → `detector_oracle_audit` | recorded counts |

Reproduce:

```bash
mvn -o test                                        # fast gate, 59/59
python eval/run_all.py --only detector_oracle_audit  # full sweep
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.cli.DetectorOracleAudit --limit 0     # uncapped nightly sweep
```
