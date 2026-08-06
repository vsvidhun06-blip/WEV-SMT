CONTROL RESULT STATUS:
A. SURVIVES — MECHANIZED + INTEGRATION VALIDATED
B. SURVIVES MATHEMATICALLY — INTEGRATION CLAIM NARROWED
**C. CORE THEOREM SURVIVES — jfCoherence CONNECTION REFUTED**  ← this one
D. CORE THEOREM REFUTED

The F0 core theorem is mechanized with no `sorry` and no axioms, and it holds. The
jfCoherence integration claim is refuted: four counterexamples, each verified end to
end through the real `AxiomaticConsistency.jfCoherence` encoder. The fragment on which
the connection does survive (F0 exactly) cannot be expressed by the shipped front-end
at all, so the surviving part is vacuous in practice.

---

## PREMISE FAILURE — READ THIS FIRST

The two inputs this session was told to build on **do not exist**:

- `research/control-positive-result.md` — absent
- `eval/research/control/search.py` — absent

Searched: the working tree, `git log --all` including deleted paths, both branches,
the single stash, and every prior session scratchpad under
`AppData/Local/Temp/claude/`. No file of either name, and no occurrence of the string
`N4` anywhere in the repo outside `eval/corpus/` (third-party vendored code).

Consequence: **"8,652 exhaustive small-BV checks, 0 mismatches" is unverifiable and is
not cited anywhere below.** It was re-derived from scratch instead. Every number in
this report was produced by an artifact committed in this session.

The prompt specified F0, `effect`, N4 and P1/P2/P3 verbatim, so the gates remained
executable without the missing files.

---

## F0 MATHEMATICAL RESULT

P1 = **PROVED** (`P1_lift_correct`). Evaluation of the ITE-lifted expression equals
`effect`. Proved for the *implementable* lift, not a HOL `if`: the oracle's grammar
(`QfbvConstancyOracle.BvParser`, lines 215–296) has **no ternary operator**, so the lift
is the arithmetic term the grammar does admit,

```
(g != 0) * e_t  +  (1 - (g != 0)) * e_f
```

which is well-formed because a relational node already evaluates to the bitvector 0 or 1
(`QfbvConstancyOracle` lines 197–199). P1 is therefore a real statement about 32-bit
machine arithmetic — that multiplying by the guard bit and its complement selects the
arms exactly, with no wrap-around escape — rather than a definitional unfolding.

P2 = **PROVED** (`P2_nonconst_iff_N4`). Non-constancy of the lifted effect in `r` iff N4.

P3 = **PROVED** (`P3_exact_control_on_F0`). The two-copy self-composition criterion —
the query the oracle actually poses, one shared valuation of the other registers and two
independent witness words for `r`, mirroring `QfbvConstancyOracle.compute` lines 103–116
— is **exact** for N4 on F0. Both directions are available separately as
`F0_criterion_sound` and `F0_criterion_complete`.

The two evidence tiers are kept apart, as instructed:

- **MATHEMATICAL THEOREM** — N4 iff two agreeing-off-`r` valuations give different
  effects. Mechanized. This is P1–P3 above.
- **IMPLEMENTATION FACT** — Z3 decides that QF_BV formula. *Not* in HOL, *not*
  axiomatized. Checked outside, by running the oracle (Gate 2).

## ISABELLE

```
BUILD_EXIT   = 0
THEOREMS     = 29 new, in PwT_Control.thy (of which P1, P2, P3 are the core);
               the 114 pre-existing theorems across both sessions still build green
               under a forced clean rebuild (isabelle build -d . -c)
SORRY COUNT  = 0
ASSUMPTIONS  = none beyond the definitions in PwT_Control.thy and PwT_Expr.thy
```

New file `isabelle/pwt/PwT_Control.thy`, added to the `WEV_SMT_PwT` session in
`isabelle/ROOT`. No existing theory was modified.

`ASSUMPTIONS = none` is checkable, not asserted: the import chain is
`PwT_Control → PwT_Expr → HOL-Library.Word`. It does **not** reach `WEV_SMT_Defs`,
which is the only theory in the repo containing `axiomatization`. So none of the
detector axioms are load-bearing for P1–P3. No `sorry`, no `oops`, no new
`axiomatization`, no `consts`.

Build command (Isabelle is not on PATH on this machine; it is driven through its
bundled Cygwin):

```sh
"/c/Users/Vidhu/AppData/Local/Isabelle/Isabelle2024/contrib/cygwin/bin/bash.exe" --login -c \
  'cd "$(cygpath -u ".../wev-smt/isabelle")" && \
   /cygdrive/c/.../Isabelle2024/bin/isabelle build -d . WEV_SMT WEV_SMT_PwT'
```

## SMALL-BV

```
checks      = 804,384        (802,816 pointwise P1 + 1,568 P3 criterion checks)
mismatches  = 0
```

Re-derived, not inherited. `eval/research/control/smallbv_check.py`, exhaustive at
3 bits over 3 registers (512 valuations per triple), 8 guards × 14 arm expressions ×
14 arm expressions, signed relational operators matching JavaSMT's `signed = true`.

This is a redundancy check — P1–P3 are proved for all cases in Isabelle — and is
reported because the brief asked for it.

## JFCOHERENCE INTEGRATION

```
tests       = 18   (6 constructed F0 cases A–F, 12 existing published control examples)
expected    = 4    (unchanged and correct: TC08, TC17, TC19, TC20)
unexpected  = 8    (3 genuine gains, 5 regressions — all SURPRISEs, investigated below)
unsupported = 6    (all six constructed cases A–F)
```

Prototype: `src/main/java/wev/smt/research/ControlIntegrationRun.java`. It reads the
sidecar the shipped parser produces, builds a **second** `DependencyInfo` with control
edges re-classified by N4, and runs each through the existing
`AxiomaticConsistency.consistencyWEAKEST()`. **The shipped detector is untouched**;
nothing is wired into any shipped path.

### Why all six constructed cases are UNSUPPORTED

Not a limitation of the criterion — a **front-end fidelity failure**, and a silent one.

In `LitmusParser.lexC`, `C_WRITE_ONCE` is matched with `.find()` at line 1000, *before*
the `if|while|for` check at line 1041. A statement like `if (r0 == 1) WRITE_ONCE(*x, 1)`
therefore matches the store pattern anywhere in the string, and **the guard is discarded
without a diagnostic**. The `BRANCH` case is never reached, so no control edge is even
recorded.

Measured, not inferred — the probe counts source-level branch shapes and compares them
against recorded edges:

| case | `if`+body | `else` arms | ctrl edges recorded |
|---|---|---|---|
| ctrlA–ctrlF | 1–2 each | 0–2 each | **0 in every case** |

Case A parses to **two unconditional writes** `W(x,1)`, `W(x,1)` on the same thread. The
program written says "exactly one of these executes". The program analysed says "both
execute". That is a semantic corruption, not a precision loss — which is exactly why
these are reported UNSUPPORTED rather than being scored.

**F0 is not representable in the shipped front-end.** There is no two-way branch, no
join, no arm structure. `case BRANCH` merely appends to a monotonically growing
`ctrlReads` list, and every later store in the thread gets `addCtrlDep(wr, cr, true)` —
the flag hard-coded `true` at line 676.

### Why the 12 existing examples are all the degenerate idiom

Every published control example in the repo uses `if (r0 == 1) ;` — an **empty** arm with
the write *after* the join. That is the only branch form the parser accepts, because an
empty arm leaves the statement as bare `if (...)`, which does fall through to `BRANCH`.
So the entire published control corpus consists of writes that execute unconditionally.

## CURRENT vs SEMANTIC CONTROL

Ground truth is each file's own stated expectation (JMM decision from Pugh 2004, or the
source model the file cites).

| test | current_control_edges | semantic_control_edges | current_verdict | semantic_verdict | expected | reason |
|---|---|---|---|---|---|---|
| ctrlA-fake-const | 0 | 0 | ALLOWED | ALLOWED | ALLOWED | **UNSUPPORTED** — guard discarded; 2 unconditional `W(x,1)` analysed instead |
| ctrlB-real | 0 | 0 | ALLOWED | ALLOWED | FORBIDDEN | **UNSUPPORTED** — guard discarded |
| ctrlC-existence | 0 | 0 | ALLOWED | ALLOWED | FORBIDDEN | **UNSUPPORTED** — guard discarded |
| ctrlD-equiv-arms | 0 | 0 | ALLOWED | ALLOWED | ALLOWED | **UNSUPPORTED** — guard discarded |
| ctrlE-guard-only | 0 | 0 | ALLOWED | ALLOWED | ALLOWED | **UNSUPPORTED** — guard discarded |
| ctrlF-correlated-temp | 0 | 0 | ALLOWED | ALLOWED | ALLOWED | **UNSUPPORTED** — guard discarded |
| LB+ctrl | 2 | 0 | FORBIDDEN | ALLOWED | FORBIDDEN (weakestmo) | **REGRESSION** — effect `1` const in `R165` |
| LB+ctrldata+ctrl-single | 2 | 0 | FORBIDDEN | ALLOWED | FORBIDDEN | **REGRESSION** — effect `42` const |
| LB+ctrldata+ctrl-double | 2 | 0 | FORBIDDEN | ALLOWED | ALLOWED | **GAIN** |
| LBcd | 2 | 0 | FORBIDDEN | ALLOWED | FORBIDDEN (PwT) | **REGRESSION** |
| LBfc | 2 | 0 | FORBIDDEN | ALLOWED | ALLOWED (PwT) | **GAIN** |
| TC03 | 1 | 0 | FORBIDDEN | ALLOWED | FORBIDDEN (JMM) | **REGRESSION** |
| TC04 | 1 | 0 | FORBIDDEN | ALLOWED | ALLOWED (JMM) | **GAIN** |
| TC07 | 2 | 0 | FORBIDDEN | ALLOWED | FORBIDDEN (JMM) | **REGRESSION** |
| TC08 | 2 | 0 | FORBIDDEN | FORBIDDEN | FORBIDDEN (JMM) | UNCHANGED — data dep carries it |
| TC17 | 2 | 1 | FORBIDDEN | FORBIDDEN | FORBIDDEN (JMM) | UNCHANGED — `(R224)+1` genuinely varies |
| TC19 | 2 | 0 | ALLOWED | ALLOWED | ALLOWED (JMM) | UNCHANGED |
| TC20 | 1 | 1 | FORBIDDEN | FORBIDDEN | FORBIDDEN (JMM) | UNCHANGED |

**Accuracy against ground truth on the 12 runnable tests: current 9/12, semantic 7/12.
The change is a net regression of 2.**

### SURPRISE investigation — the front-end collapses three FORBIDDEN/ALLOWED pairs

Every one of the 8 unexpected results has one root cause, and it is worse than a
criterion problem. Three pairs of tests have **opposite ground truth** but parse to
**byte-identical** representations:

| pair | intended difference | ground truth | parsed as |
|---|---|---|---|
| TC03 / TC04 | guarded write vs both-arms-write | FORBIDDEN / ALLOWED | *identical source text* |
| LB+ctrldata+ctrl-single / -double | one arm vs both arms | FORBIDDEN / ALLOWED | *identical source text* |
| LBcd / LBfc | genuine ctrl vs false ctrl | FORBIDDEN / ALLOWED | identical event structure |

The repo's own file comments already admit this. TC04: *"the wev C parser has no
brace/else handling, so both-arms-write flattens to the SAME event structure as genuine
TC03."* LBfc: *"both-arms-write is flattened to the same event structure as the genuine
LBcd."*

**No classifier downstream of this parser can separate these pairs — they are the same
input.** The current detector answers FORBIDDEN to both members and so scores the
FORBIDDEN member of each pair; N4 answers ALLOWED to both and scores the ALLOWED member.
Each is 3/6 on the six paired tests. The criterion is not gaining precision; it is
**trading which half of each pair it gets wrong.**

## N4 SOUNDNESS ATTACK

```
counterexample found = YES  (4 of them)
```

`src/main/java/wev/smt/research/ControlN4AttackRun.java`. Event structures are built by
hand — the only way to put a genuine branch in front of `jfCoherence`, since the parser
discards guards. Each scenario runs **twice against the same event structure and the
same wiring**, differing only in whether the control edge is kept: baseline (shipped
behaviour) vs dropped (what N4 would do). Verified through the real encoder.

| scenario | shape | N4 says | baseline | N4-dropped | must be | outcome |
|---|---|---|---|---|---|---|
| F0-both-arms-write-1 | `if(r0) W(x,1) else W(x,1)` | FAKE | FORBIDDEN | ALLOWED | ALLOWED | flip, **correct** (positive control) |
| F2-write-then-overwrite | `if(r0){W(x,1);W(x,2)} else W(x,2)` | FAKE | FORBIDDEN | ALLOWED | FORBIDDEN | **COUNTEREXAMPLE** |
| F6-existence-control | `if(r0) W(x,1)` (no else) | FAKE | FORBIDDEN | ALLOWED | FORBIDDEN | **COUNTEREXAMPLE** |
| F7-different-locations | `if(r0) W(x,1) else W(z,1)` | FAKE | FORBIDDEN | ALLOWED | FORBIDDEN | **COUNTEREXAMPLE** |
| F2-co-visible-intermediate | `if(r0){W(x,3);W(x,2)} else W(x,2)` | FAKE | FORBIDDEN | ALLOWED | FORBIDDEN | **COUNTEREXAMPLE** |

F0 flips correctly. The four extensions flip incorrectly. The wiring is identical
across all five; only the branch shape differs.

### Smallest counterexample

**F6, existence control** — 6 events:

```
P0:  r0 = R(y);  if (r0 == 1) { W(x,1) }        // no else arm
P1:  r1 = R(x);  W(y, r1)                        // real data dep
```

The write's *value* is the constant 1, so any value-only effect abstraction reports
FAKE. But whether the write **exists at all** is what depends on `r0`. Dropping the
edge breaks the cycle

```
R(y)=1 --ctrl--> W(x,1) --jf--> R(x)=1 --data--> W(y,1) --jf--> R(y)=1
```

and admits a self-justifying thin-air outcome. **This is exactly the shape of JMM TC03
and TC07** — so the counterexample is not hypothetical, it is live in the shipped corpus.

Smallest counterexample in which *both arms write* (preserving more of F0's hypotheses,
relaxing only "exactly once") is **F2**:

```
P0:  r0 = R(y);  if (r0) { W(x,1); W(x,2) } else { W(x,2) }
P1:  r1 = R(x);  W(y, r1)
```

Both arms leave `x` holding 2, so the final-value effect is constant in `r0` and N4 says
FAKE. But the true arm additionally makes the value **1** readable at `x`, and `jf` can
target that write. `P1` reads it, writes `y=1`, which justifies `r0=1`. Pure thin air.

### Why N4 is too weak — the actual diagnosis

`jf` edges point from a **write event** to the read it justifies. What `jfCoherence` can
see is therefore **the set of write events an arm contributes**, any one of which some
other thread may read from — *not* the final value at a location. N4 abstracts a branch
by one word. That abstraction is faithful exactly when each arm contributes exactly one
write event to one location, i.e. exactly on F0.

Mechanized in `PwT_Control.thy`:

- `F0_abstraction_faithful` / `F0_observable_iff_effect` — on F0 the observable write-event
  set is the singleton of the effect value, so nothing is lost.
- `F2_refutes_final_value_abstraction` — explicit witness where `final_at` agrees but
  `observable_at` differs.
- `F2_final_value_constancy_insufficient` — the implication the integration claim needs
  is false as a universally quantified statement.
- `F6_omitted_write_observable`, `F7_different_locations_observable` — likewise.

**Required effect summary.** Not the final value at one location. The correct summary is
the **multiset of write events (location, value) each arm contributes**, compared arm to
arm — i.e. an arm-indexed event-set equality, not a word equality. That is precisely what
PwP's coalescing already computes (below), which is why PwP gets F2/F6/F7 right.

### Residual assumptions

- The attack uses the single-execution encoding, where `jf = rf` and branch arms are
  distinct events gated by `active`. A different encoding of branch existence might shift
  where the boundary falls, but not the F6 argument, which is about event existence and
  is encoding-independent.
- Scenarios use relaxed accesses only; adding fences can only forbid more, so it cannot
  rescue a dropped edge.

## F1–F7 EVIDENCE TIERS

The previous report labelled all seven "SUPPORTED". That is not sustainable.

```
F1 nested branches         = CONJECTURED
F2 multiple writes         = REFUTED
F3 branch-local temporaries= CONJECTURED
F4 correlated registers    = PROVED (mathematically), UNSUPPORTED (in the tool)
F5 infeasible paths        = CONJECTURED
F6 omitted write           = REFUTED
F7 different locations     = REFUTED
```

- **F2 = REFUTED**, as the brief anticipated. Mechanized
  (`F2_refutes_final_value_abstraction`) *and* demonstrated through `jfCoherence` (two
  scenarios: via the readable intermediate value, and via the coherence order). A
  final-value abstraction erases exactly the event-structure information `jf` consumes.
- **F6 = REFUTED.** Existence has no word to compare; `final_at` returns `None`.
  Mechanized + counterexample.
- **F7 = REFUTED.** Same value, different location. Mechanized + counterexample.
- **F4 = PROVED mathematically** — `caseF_correlated_temp_not_N4` shows the substituted
  effect `(r+1)-r` is the constant 1, so the criterion beats a syntactic detector here.
  But case F is UNSUPPORTED in the tool (guard discarded), so there is no integration
  evidence.
- **F1, F3, F5 = CONJECTURED.** No proof, no mechanization, and no integration evidence,
  because none of them is representable in the front-end. They should not be asserted at
  all until a front-end exists that can express them.

## PwT

**exact relevant definition** — Jagadeesan, Jeffrey & Riely, *Pomsets with Preconditions:
A Simple Model of Relaxed Memory*, PACMPL 4(OOPSLA), Article 194, 2020.

- **Definition 2.5** (§2.5, "Conditional, Register Assignment, and Skip"): the conditional
  is defined via parallel composition and filtering,
  `⟦if(M){S₁} else {S₂}⟧ = (M ▷ ⟦S₁⟧) ∥ (¬M ▷ ⟦S₂⟧)`.
- **Definition 2.4** (§2.4): parallel composition "allows events with the same label to
  **coalesce, taking the disjunction of their preconditions**."
- **Definition 2.1** (§2.1): "We say φ is **independent** of r if, for every M,
  φ ⟺ φ[M/r]; it is **dependent** otherwise."

**relationship** — This is the same construction, arrived at independently. §2 (p. 194:5)
states the case-A result outright: *"there is **no control dependency** between reading y
and writing x, since the (W x 1) event happens **on both sides of the conditional**."* The
worked precondition calculation is `(r=0 ∨ r=1 | W x 1)`, a tautology, hence no
dependency — that **is** the ITE lift, expressed as a disjunction of preconditions
instead of an arithmetic select. The contrast case is also there: in `oota3`, "the writes
to x on either side of the conditional **cannot be combined, since one side writes 1 and
the other side writes 2**", leaving `(r=1 | W x 1)` — which is case B.

And Definition 2.1 *is* the non-constancy/self-composition criterion, stated as
substitution-invariance of a formula in a register.

Two things follow. First, **the mathematical core of this session's F0 result is PwP's
Definitions 2.1 + 2.4 + 2.5.** Second, and more importantly: **PwP's version is correct
where N4 is broken.** PwP coalesces per *event*, keyed on the label (action = location +
value), not on a final value. So an arm that writes twice contributes an extra event that
cannot coalesce (F2 handled), an arm that omits the write leaves an uncoalesced event
(F6 handled), and arms writing different locations have different labels (F7 handled).
The abstraction N4 uses is strictly coarser than the published one, and the four
counterexamples are exactly where it is coarser.

The repo's existing `R4b` work (`PwT_R4b.thy`, from *The Leaky Semicolon*, Jeffrey et al.
POPL 2022) concerns the two-point value check on **branch-free** expressions. It is a
different question from cross-branch merging and does not cover this.

## MRD

**exact relevant definition** — Paviotti, Cooksey, Paradis, Wright, Owens & Batty,
*Modular Relaxed Dependencies in Weak Memory Concurrency*, ESOP 2020, LNCS 12075,
pp. 599–625.

- §6.1, worked example **"LB+ctrl-double"**: the program is
  `r1 := x; if (r1==1){ y := 1 } else { y := 1 }` — case A verbatim, and the same test the
  repo ships as `LB+ctrldata+ctrl-double.litmus`. MRD's verdict: *"Coproduct ensures that
  the denotations of P1 and P2 are identical... From compositionality (Lemma 1) and
  equality of the denotations, we have equal behaviour of P1 and P2 in any context, and
  **the optimisation is allowed**."*
- §1: MRD's stated purpose is to compute a semantic-dependency relation `dp` that
  "identifies false program dependencies that might be removed by compiler optimisation,
  and leaves in place just the dependencies necessary to rule out thin-air reads."

**relationship** — MRD already decides the exact motivating example, by denotational
equality of event structures rather than by a value abstraction. Like PwP, it works at
event granularity and so is not vulnerable to F2/F6/F7.

Note both papers are **already cited in `paper/wev-smt.tex`** (lines 828 and 1033).

## NOVELTY CLASSIFICATION

```
ALREADY PRESENT IN PRIOR WORK
```

Being conservative, as instructed. The claimed contribution is:

1. **ITE-lifting a branch's write effect** — PwP Def. 2.4 (disjunction of preconditions
   over coalesced events), 2020.
2. **Deciding non-constancy in the controlling register** — PwP Def. 2.1 (independence by
   substitution), 2020.
3. **Using this to erase false control dependencies for thin-air** — PwP §2 and MRD §6.1,
   both 2020, both on this exact example.
4. **Feeding the result to a single-candidate consistency check** — this is the only
   genuinely distinct element, and it is a packaging decision, not a semantic one. It is
   also the element that was **refuted** in Gate 3.

There is no residual "NEW INTEGRATION / APPLICATION" claim to fall back on. The
integration is the part that broke, and it broke because it used a strictly weaker
abstraction than the prior work it would have been distinguished from.

## STRONGEST CLAIM SAFE FOR TACAS

> WEAKEST's QF_BV constancy oracle, which the paper already uses to classify **data**
> dependencies, decides the same non-constancy question that PwP's independence condition
> (Def. 2.1) poses. On a fragment with one two-way branch whose arms each perform exactly
> one write to the same location, an ITE-lifted effect expression reduces semantic control
> dependence to that same query, and the reduction is exact — mechanized in Isabelle,
> `BUILD_EXIT=0`, no `sorry`, no axioms.
>
> This is **not** claimed as a new semantic result: the construction is PwP's coalescing
> rule (Defs. 2.1/2.4/2.5) and MRD's §6.1 example. It is claimed only as evidence that the
> existing oracle is *already strong enough* to answer control questions on that fragment,
> should a front-end able to express branches ever be built.

Even this needs the caveat that the fragment is currently empty in the tool.

## CLAIMS THAT MUST BE DELETED

1. **"F1–F7 SUPPORTED."** F2, F6, F7 are **REFUTED**. F1, F3, F5 are unproved conjectures.
2. **"Dropping an N4-fake edge cannot admit thin air because the observable effect is
   constant in r."** False. Four counterexamples through the real encoder.
3. **Any claim that semantic control classification improves precision on the corpus.**
   Measured: 9/12 → 7/12, a net regression of 2.
4. **"jfCoherence preservation argued"** — argued for a property (final-value constancy)
   that is not the property `jfCoherence` depends on (write-event sets).
5. **Any novelty claim over PwT/MRD** for ITE lifting, cross-branch semantic equivalence,
   or non-interference-style dependency removal.
6. **"8,652 exhaustive small-BV checks"** — unreproducible; the script is absent. Cite the
   804,384 checks from `smallbv_check.py` instead, or nothing.
7. **Any statement that cases A–F were tested.** They were not; the front-end silently
   analysed different programs.

## DOES THIS NOW REMOVE "THE PAPER PUNTS CONTROL"?

```
NO
```

Three independent reasons, in increasing order of severity:

1. **The criterion is not sound for `jfCoherence`** beyond F0 — four counterexamples,
   two of which (F6) match live corpus tests TC03 and TC07.
2. **The criterion is not new** — it is PwP Defs. 2.1/2.4/2.5 and MRD §6.1, both already
   cited in the paper, and both correct where this version is not.
3. **The front-end cannot represent control at all.** `LitmusParser.lexC` discards any
   guard whose body sits on the same statement, with no diagnostic; control edges are
   hard-coded `isSemantic = true` at line 676; and the corpus collapses three
   FORBIDDEN/ALLOWED pairs into three identical inputs. Until that is fixed, no control
   result — this one or any other — can be evaluated, let alone claimed.

Reason 3 is the finding with the most value. It is a **prerequisite** for the control
work, it is independently checkable, and it was invisible while every control test in the
corpus used the one branch form that happens to survive the parser.

The honest paper statement remains: *WEAKEST classifies data and address dependencies
semantically and treats control dependencies syntactically.* That is what the code does.

---

## ARTIFACTS ADDED BY THIS SESSION

| path | what |
|---|---|
| `isabelle/pwt/PwT_Control.thy` | Gate 1: P1/P2/P3 + the F0 boundary and F2/F6/F7 refutations |
| `isabelle/ROOT` | one line, adding `PwT_Control` to `WEV_SMT_PwT` |
| `eval/research/control/*.litmus` | the six constructed cases A–F |
| `eval/research/control/smallbv_check.py` | exhaustive small-BV re-derivation |
| `src/main/java/wev/smt/research/ControlIntegrationRun.java` | Gate 2 prototype + fidelity checker |
| `src/main/java/wev/smt/research/ControlN4AttackRun.java` | Gate 3 attack |
| `eval/research/control-integration.csv` | the Gate 2 table |
| `research/control-formal-validation.md` | this file |

`paper/wev-smt.tex` was **not** edited. The shipped detector was **not** changed. No
existing theory was modified.
