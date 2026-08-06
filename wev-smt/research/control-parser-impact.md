PARSER DEFECT: **CONFIRMED**

SEVERITY: **S2** — one or more secondary evaluation claims become unsupported.

S2, not lower, because a motivating claim in §1 is causally misattributed and five
"agreement" data points carry no information. S2, not S3, because no headline number
moves, no verdict changes, and no theorem is touched — I could not find a way to make
this reach S3 and I looked for one.

```
AFFECTED TESTS                = 31   (12 published/example + 19 corpus)
SEMANTIC COLLISIONS           = 3 control groups, covering 7 tests
PAPER-RELEVANT AFFECTED TESTS = 12   (the 19 corpus ones are inert: 17 encode zero events)
```

---

## 1. THE DEFECT — verified, not inferred

**Mechanism.** `LitmusParser.Lexer.lexC` dispatches in order. `C_WRITE_ONCE` is matched
with **`.find()`** — a *substring* search — at **line 1000**. The `if|while|for` test is
at **line 1041**. So for the statement `if (r0==1) WRITE_ONCE(*x,1)`, the store pattern
matches *inside* the guarded statement, `lexC` returns a bare store, and control never
reaches the `BRANCH` case. **The guard is discarded with no diagnostic.**

Thread bodies are split at **line 574**, `inner.split(";")`, which is why the
`if (c) ;` idiom survives at all: the empty arm leaves `if (c)` as a statement of its
own, which *does* fall through to line 1041.

**Empirical proof.** Complete parsed representations, canonically hashed
(`wev.smt.research.ControlParserImpactRun`, event list + po + rf + co + dependency
sidecar, event ids renumbered per file):

| input | source | ctrl edges | writes to x | hash (pre-fix) |
|---|---|---|---|---|
| A | `WRITE_ONCE(*x,1)` | 0 | 1 | `58d293ceec35c4d3` |
| B | `if (r0==1) WRITE_ONCE(*x,1)` | 0 | 1 | **`58d293ceec35c4d3`** |
| C | `if (r0==1) W(*x,1); else W(*x,1)` | 0 | 2 | `2460a6a78b5d3530` |
| D | `if (r0==1) W(*x,1); else W(*x,0)` | 0 | 2 | `186dcc1839b5dbf0` |
| E | `if (r0==1) ; WRITE_ONCE(*x,1)` | 1 | 1 | `be3d0d8a6482d4be` |

**A and B are byte-identical.** Guard and branch structure are both lost.

C and D differ from each other only because their arm *values* differ — the branch is
gone in both, and each arm becomes an unconditional store, so the parser emits **two**
writes to `x` where the program performs one. That is a fabricated event, not merely a
lost annotation.

**A methodological note.** My first hashing pass reported C/D and single/double as
*non*-colliding. That was wrong: the canonical form iterated `Map`s keyed by the parser's
*global* event ids, which differ between parses, so line order was unstable. Fixed by
sorting every relation before hashing; all hashes in this report are from the corrected
form.

## 2. BLAST RADIUS

`eval/control-parser-impact.csv` — 3,062 rows, one per evaluation input.

**Scoping result that limits the damage: the `if (c) STMT` form appears in ZERO shipped
evaluation inputs.** Every control-bearing test in every population uses the degenerate
`if (c) ;` idiom. The defect is real and was live, but nothing in the current evaluation
ever exercised it.

| population | files | control-bearing | shape | control preserved |
|---|---|---|---|---|
| published/paper | 7 | 2 | `if (c) ;` | degenerate idiom |
| published/JMM | 20 | 7 | `if (c) ;` | degenerate idiom |
| published/weakestmo | 8 | 1 | `if (c) ;` | degenerate idiom |
| published/PwT | 6 | 2 | `if (c) ;` | degenerate idiom |
| mined | 48 | 0 | — | n/a |
| corpus-2972 | 2,972 | 19 (0.6%) | 18 asm-branch, 1 loop | NO |

The 19 corpus tests are inert: **17 of them parse to zero events**, all carry
`sdep=0` in `eval/qfbv-detector-results.csv`, and **none appears in the herd7 comparison
population**.

## 3. SEMANTIC COLLISIONS

Three groups of *different published programs with different intended semantics* mapping
to one parsed representation:

| hash | tests | intended difference | expected verdicts | actual (all) |
|---|---|---|---|---|
| `fc46f9431d6daa23` | **TC03, TC04** | guarded write vs both-arms-write | FORBIDDEN / **ALLOWED** | FORBIDDEN |
| `dbdf008c2b5dd632` | **LB+ctrl, LBcd, LBfc** | genuine ctrl ×2 vs false ctrl | FORBIDDEN, FORBIDDEN / **ALLOWED** | FORBIDDEN |
| `afa7e2a3fbef61a0` | **ctrl-single, ctrl-double** | one arm vs both arms | FORBIDDEN / **ALLOWED** | FORBIDDEN |

**The collisions are in the litmus FILES, not the parser.** All three pairs have
byte-identical program text (verified by diff, comments stripped). The corpus authors,
working around the parser limitation, encoded both members of each pair the same way —
and said so: TC04's own comment reads *"the wev C parser has no brace/else handling, so
both-arms-write flattens to the SAME event structure as genuine TC03."*

**LB+ctrl also collides** — a three-way group spanning weakestmo *and* PwT. The audit
brief listed only TC03/TC04, single/double and LBcd/LBfc; the weakestmo member was
missed.

### Can this create false agreement? Yes.

Five tests are **counted as agreements** whose parsed input is identical to a test the
tool gets wrong:

| counted agreement | identical input to | reported before |
|---|---|---|
| LB+ctrl (weakestmo) | LBfc (**disagreement**) | FORBIDDEN, match=yes |
| LBcd (PwT) | LBfc (**disagreement**) | FORBIDDEN, match=yes |
| TC03 (JMM) | TC04 (**disagreement**) | FORBIDDEN, match=yes |
| ctrl-single | ctrl-double | FORBIDDEN, agree |
| TC07 (JMM) | — (same shape class) | FORBIDDEN, match=yes |

The tool answers FORBIDDEN to every member of every group. Its correctness on the
FORBIDDEN members is therefore **not evidence**: a constant-FORBIDDEN oracle would score
identically on all seven. These agreements are real verdicts but carry zero
discriminating information.

The same applies to the cross-checks: **12 of the 41 Dat3M** comparison points and
**9 of the 27 RC11** points are control tests, all agreeing — but they contain only
9 and 7 *distinct* parsed inputs respectively.

## 4. HEADLINES RECOMPUTED

`eval/control-parser-headline-delta.csv`. "UNAFFECTED" removes every test whose control
structure is not faithfully represented.

| headline | BEFORE | UNAFFECTED | excluded | POST-FIX |
|---|---|---|---|---|
| Published examples | 31/34 | **23/24** | 10 | 31/34 |
| JMM (symbolic) | 18/20 | **12/13** | 7 | 18/20 |
| JMM (wired) | 17/20 | **11/13** | 7 | 17/20 |
| PwT examples | 5/6 | **4/4** | 2 | 5/6 |
| Weakestmo | 8/8 | **7/7** | 1 | 8/8 |
| Dat3M (symbolic) | 35/41 | **23/29** | 12 | 35/41 |
| Dat3M (wired) | 31/41 | **19/29** | 12 | 31/41 |
| RC11 | 21/27 | **12/18** | 9 | 21/27 |
| Corpus | 2972 | **2953** | 19 | 2972 |
| Anchor/discriminating | 2/34 | **2/24** | 10 | 2/34 |
| herd7 SC/TSO | 38.3 / 86.2 / 73.9 % | **unchanged** | 0 | unchanged |

**Every headline survives exclusion.** No agreement ratio degrades: PwT and Weakestmo
become *perfect* on the unaffected subset (the excluded tests were the only failures),
and the others lose roughly proportionally. The two known divergences (LBfc, TC04) are
both control tests, so excluding control makes the remaining evaluation look better, not
worse.

## 5. SEVERITY: S2

Reasoning, stated so it can be challenged:

- **Not S0** — published results do depend on affected tests: 10 of 34 published
  examples, 12 of 41 Dat3M points, 9 of 27 RC11 points.
- **Not S1** — this is more than shifting denominators. A claim in the paper is
  *causally wrong*, see below.
- **S2** — these become unsupported:
  1. §1 (lines 160–164): *"Our checker forbids ctrl-single correctly; it also
     conservatively forbids ctrl-double, the companion program that must be allowed — a
     known limitation of **the per-execution setting** for control dependencies."*
     **The attribution is false.** ctrl-single and ctrl-double are the *same parsed
     input*. The over-forbidding has nothing to do with the per-execution setting; it is
     a front-end encoding limitation. No per-execution model, however refined, could
     separate two identical inputs. The same misattribution recurs at lines 570, 584,
     1010 and 1049.
  2. The evidential value of the five agreements listed in §3.
  3. "Conservatively semantic" (lines 672, 1010, 1049) describes a *policy* choice that
     could in principle be relaxed. For these tests it is instead a *representational*
     limit: even a perfect semantic-control classifier could not fix them.
- **Not S3** — no headline number changes, no verdict changes, the soundness theorem is
  untouched, and the divergence count (2: LBfc, TC04) was already correctly reported and
  correctly attributed *in the eval CSVs*. The wrong attribution is in the prose.

## 6. THE FIX

```
implemented      = YES   (branch research/control-parser-fix, commit 1cb0cc0)
regression tests = 10    (src/test/java/wev/smt/parse/ControlGuardParsingTest.java)
full suite       = PASS  (69/69)
```

Split `if (c) STMT` into `if (c)` + `STMT` at the statement boundary
(`LitmusParser` line 574), balanced-paren aware so `if ((r0 & 1) == 1)` works, plus
leading-`else` stripping. **Lexical repair only** — no lexer rule, no dependency policy,
no `isSemantic` flag changed. Control edges remain unconditionally semantic, as required.

Post-fix representations — A/B/C/D now four distinct hashes:

| input | ctrl edges | writes to x | hash |
|---|---|---|---|
| A | 0 | 1 | `58d293ceec35c4d3` |
| B | **1** | 1 | `be3d0d8a6482d4be` |
| C | **2** | 2 | `78e4bc517792a866` |
| D | **2** | 2 | `a00012a8e713e86b` |

### What the fix does NOT fix

- `if(c) W` still parses identically to `if(c); W` (B and E share a hash). A guarded
  store and an unconditional store after an empty guard remain indistinguishable.
- A two-armed branch still emits **two** write events where the program performs one.
- **All three published collision groups are unchanged post-fix**, because they
  originate in the litmus encodings. Rewriting those files into real two-armed form
  would *not* help either — the fixed parser would then emit two writes per branch.

A real repair needs branch structure in the event model. That is out of scope here.

## 7. POST-FIX VERDICT CHANGES

```
POST-FIX VERDICT CHANGES = 0
```

Full `eval/run_all.py` re-run, all 19 experiment blocks. `eval/numbers.tex` diffed
against the frozen pre-fix copy: **every verdict, agreement and count macro is
identical.** The four result CSVs (`source-paper-results`, `jmm-results`,
`dat3m-comparison-v2`, `rc11-direction-check`) are **byte-identical**.

24 macros differ, all wall-clock timings (`*Ms`, `CorpusWallSeconds`, `Scal*`,
`LbRealFakeRatio`) — machine-load noise, not measurements of the fix.

This is the expected result and confirms §2: no evaluation input used the broken form.

```
CENTRAL SCIENTIFIC CONCLUSIONS CHANGED = NO
```

## 8. CONTROL CLAIMS — STATUS

| # | claim | location | status |
|---|---|---|---|
| 1 | "forbids ctrl-single correctly; also conservatively forbids ctrl-double — a known limitation of **the per-execution setting**" | §1, 160–164 | **NEEDS NARROWING** — verdicts right, cause wrong. It is a front-end limitation. |
| 2 | "ctrl-double is the known per-execution control limitation" | 570 | **NEEDS NARROWING** — same misattribution |
| 3 | "$^\dagger$Per-execution over-forbids ctrl-double" | 584 (Table 1 note) | **NEEDS NARROWING** — same |
| 4 | `dep_ctrl ⊆ sdep_impl` (conservative) | 328, 672 | **UNCHANGED** — true and verified; `ctrlFake=0` on every anchor row |
| 5 | "The single divergence is LBfc (both-arms-write control dependency)" | 857–858 | **UNCHANGED** — correct, and its stated cause (no brace/else handling) is right |
| 6 | "TC04 … the same control-dependency limitation as LBfc" | 874–878 | **UNCHANGED** — correct |
| 7 | "of 34 published examples, two (LBfc, TC04) arise from the [control limitation]" | 894 | **NEEDS NEW NUMBER** — count right; should disclose that 10 of 34 are control tests and 5 agreements are non-discriminating |
| 8 | "Control: conservatively semantic; affects TC04, LBfc" | 1010, 1049 | **NEEDS NARROWING** — "conservatively semantic" implies a relaxable policy; for these tests the representation is the binding constraint |
| 9 | PwT agreement 5/6 | numbers.tex | **NEEDS NEW NUMBER** — add that the unaffected subset is 4/4 and the sole failure is a collision member |
| 10 | JMM agreement 18/20 | numbers.tex | **NEEDS NEW NUMBER** — 12/13 unaffected; TC03/TC04 are one input |
| 11 | Dat3M 35/41, RC11 21/27 | numbers.tex | **NEEDS NEW NUMBER** — 12/41 and 9/27 are control tests containing 9 and 7 distinct inputs |
| 12 | Theorem 1 (soundness) | thm:soundness | **UNCHANGED** — a theorem, not an empirical claim |
| 13 | Detector exactness on branch-free fragment | §T1.2 | **UNCHANGED** — branch-free by construction |
| 14 | herd7 SC/TSO agreement | numbers.tex | **UNCHANGED** — 0 control tests in that population |

**DELETE: none.** No control claim is false enough to remove; three need their *cause*
corrected and five need supplementary numbers.

## 9. EXACT PAPER SECTIONS REQUIRING REVISION

| location | what |
|---|---|
| §1, lines 160–164 | re-attribute the ctrl-double limitation: front end, not per-execution setting |
| line 570 | same |
| line 584, Table 1 footnote | same |
| §6 limitations, lines 1010, 1049 | distinguish policy conservatism from representational limitation |
| §5 eval, line 894 | disclose control-test share of the 34 and the non-discriminating agreements |
| numbers.tex consumers: PwT / JMM / Dat3M / RC11 | add unaffected-subset figures |

`paper/wev-smt.tex` was **not edited** — verified by mtime (last modified 2026-07-23,
before this session).

## 10. INCIDENTAL FINDING — out of scope, flagging anyway

**151 of the 2,972 corpus tests (5.1%) parse to ZERO events**, and 140 of them share one
canonical hash (`abba846743b5704b`, the empty representation `arch=ARM`). They still
contribute to `\CorpusSize`, `\HierarchyTests` ("tests with all five model verdicts") and
`\QfbvTests`. A model verdict on an empty event structure is vacuous, so these inflate
three denominators.

This is unrelated to the control defect and I have not investigated it. It deserves its
own audit.

## 11. ARTIFACT VERSIONING

Branch `research/control-parser-fix`. `main` untouched. No history rewritten.

| tag | commit | state |
|---|---|---|
| `artifact-v2-pre-control-parser-fix` | `8c2d118` | parser **unfixed**; audit CSVs + frozen `numbers-BEFORE.tex`, `results-BEFORE.json`, `BEFORE-*.csv` |
| `artifact-v3-control-parser-fixed` | `1cb0cc0` | fix + 10 regression tests + post-fix evaluation |

Both commits also carry uncommitted work from earlier sessions (paper, oracle, parser,
eval artifacts). It is included deliberately: every number here — before and after — was
produced against that working state, so omitting it would make neither state
reproducible.

## 12. ARTIFACTS

| path | what |
|---|---|
| `eval/control-parser-impact.csv` | per-test impact, 3,062 rows, required columns |
| `eval/control-parser-headline-delta.csv` | headline recomputation |
| `eval/research/parser-probe/*.litmus` | minimal A/B/C/D/E inputs |
| `eval/research/control/BEFORE-*` | frozen pre-fix results |
| `src/main/java/wev/smt/research/ControlParserImpactRun.java` | read-only representation dumper |
| `eval/research/control/build_impact_csvs.py` | join + collision detection |
| `src/test/java/wev/smt/parse/ControlGuardParsingTest.java` | 10 regression tests |

Detector and encoder unmodified — verified: `DependencyInfo.java`,
`AxiomaticConsistency.java`, `EventStructureEncoder.java` and `QfbvConstancyOracle.java`
carry no edits from this session.

---

## FINAL RECOMMENDATION

**KEEP** claims 4, 5, 6, 12, 13, 14 — verified and correctly stated.

**NARROW** claims 1, 2, 3, 8 — the verdicts are right; the *cause* is wrong. Say the
front end cannot represent a two-armed branch, so ctrl-single and ctrl-double are the
same input to the checker. This is a stronger and more honest statement than the current
one, and it is independently checkable from `eval/control-parser-impact.csv`.

**ADD NUMBERS** to claims 7, 9, 10, 11 — report the unaffected subset alongside each
agreement ratio, and state that 5 of the 31 published agreements are non-discriminating.

**DELETE** nothing.

The evidence is not contaminated in the sense of being *wrong*. It is contaminated in the
sense of being *thinner than it looks*: seven control tests are three inputs, and the
paper's explanation of why one of them fails points at the wrong cause.
