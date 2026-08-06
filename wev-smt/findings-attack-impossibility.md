# findings-attack-impossibility.md

Adversarial audit of the impossibility claim. Reconstructed from the repository first;
`findings-semantic-transformations.md` was consulted only afterwards, for comparison.

Research-only. Nothing in `src/`, `isabelle/`, or the encoder was modified.

---

## 0. Verdicts

| claim | verdict |
|---|---|
| **T1** — no total function of `(e, r)` alone is exact | **SURVIVES**, but shallow, with a domain defect |
| **T2** — no local classifier `f(e,reg)` makes the *shipped composition* exact | **SURVIVES** (new; this is the one that bites the tool) |
| **T3** — no function of `(e, boolean dependence bits)` is exact | **SURVIVES**, needs an unstated hypothesis `w ≥ 2` |
| **Bounded-prefix subclaim** as phrased in the brief | **BROKEN** |
| Prior write-up's inference *T1 ⟹ the shipped detector cannot be fixed* | **INVALID** (non sequitur) |

### ⚠ The most important finding is not about the theorem

Verifying the theorem's weakest premise (obligation O17) turned up a **soundness bug in
the shipped detector**, confirmed against the repository's own QF_BV oracle:

> `classify`'s idiom folding rewrites the expression **string** without respecting
> operator precedence, so it folds matches that are not sub-terms. It therefore
> classifies **genuinely value-carrying dependencies as fake**, dropping them from `sdep`.
> This falsifies the documented `sdep_true ⊆ sdep_impl` property, and **installing the
> exact QF_BV oracle does not fix it** — the fold short-circuits before the oracle is
> consulted.

Minimal reproducer, verified end-to-end (§9):

```
WRITE_ONCE(*y, ~r0 - r0)
    truth               : (~r0) - r0  =  -2·r0 - 1     varies  => REAL
    shipped, no oracle  : FAKE
    shipped, QF_BV on   : FAKE          <- oracle bypassed
    direct oracle query : isConstant("~r0 - r0","r0") = false  (i.e. VARIES)
```

Exhaustive enumeration: **218 / 8448 (2.58 %)** small expressions are misclassified in the
unsound direction. Currently **latent** — 0 occurrences in the repository's corpora.

---

## 1. STOP — discrepancy between the repository and the previous write-up

Step 0 required halting if my reconstruction differs from the prior statement. It does.

**Reconstructed from `LitmusParser.java` (`referencedReads` + `deriveReg` + `classify`):**

```
F(x, reg) = true                       if reg IS the load x            (base case)
F(x, reg) = S(x, def(reg))             otherwise
S(x, e)   = ⋁ over reg ∈ ids(e) . [ classify(e, reg).real  ∧  F(x, reg) ]
```

`classify` is a function of `(expression, register)`. **`S` is not** — it recurses into
`def(reg)`, so the shipped detector inspects the *entire transitive definition chain*.

Confirmed by trace on the shipped Java (`scratchpad/audit1.jsh`):

```
t := r0+1 ; store t-r0
    classify('r0 + 1', r0) = REAL      <- inspects the DEFINITION, not just the site
    classify('t - r0', t ) = REAL
    classify('t - r0', r0) = REAL
    => shipped verdict REAL            (ground truth: FAKE)
```

**The discrepancy.** `findings-semantic-transformations.md` §8 states T1 and then concludes:

> "The shipped detector's shortfall is *not* a precision problem. Replacing its decision
> procedure with a stronger one … cannot close the gap."

**That inference is invalid.** The shipped detector is not of the form `D(e,r)`; it uses
strictly more information than T1's classifier class, so a theorem about `D(e,r)`
constrains it not at all. The conclusion is *true*, but the stated theorem does not
establish it.

Compounding this: the prior document's own probes (`site_real`) **do** implement the
chained recursion — the prose stated T1 while the measurements modelled T2. The write-up
is internally inconsistent between its formal statement and its evidence.

**Which version the repository supports:** T2/T3, not T1. I audit all three and supply
T2/T3 to repair the gap.

---

## 2. Formal statement, with explicit quantifiers

**Apparatus.** Width `w ≥ 1`, values in `BV_w`. Expression language `E`: identifiers,
numerals, `+ - * & | ^ ~`, unary `-`, and relational ops returning `0/1` — exactly
`QfbvConstancyOracle`'s grammar (**no shifts, no division**). A **block**
`B = (X, D, e)`: inputs `X = {x₁..x_m}`, ordered definitions `D = [t_i := ρ_i]`, store
expression `e`; loop-free, branch-free, memory-free. `⟦B⟧ : BV_w^m → BV_w` is the transfer
function. Semantic dependency:

```
Dep(B, x_j)  ⟺  ∃ σ ∈ BV_w^{m-1}. ∃ a,b ∈ BV_w.  ⟦B⟧(σ,a) ≠ ⟦B⟧(σ,b)
```

> **T1.** `¬ ∃ D : E × Ident → Bool. ∀ B, x. D(store(B), x) = Dep(B, x)`

> **T2.** With the shipped composition `S_f` of §1 and `classify` replaced by arbitrary `f`:
> `¬ ∃ f : E × Ident → Bool. ∀ B, x. S_f(x, store(B)) = Dep(B, x)`. `f` need not be computable.

> **T3.** With `bits(B,x)` the boolean dependence summary per register, **for `w ≥ 2`**:
> `¬ ∃ g. ∀ B, x. g(store(B), bits(B,x)) = Dep(B, x)`. `g` and the composition are arbitrary.

**Ambiguities recorded** — each a modelling choice, not a derived fact:

| # | ambiguity | resolution used | consequence |
|---|---|---|---|
| A1 | "distinguished source register" — register in `e`, or load event? | load event; `r` need not occur in `e` | makes T1's domain degenerate (§10) |
| A2 | other inputs shared or independently quantified in `Dep`? | shared (matches the oracle's documented query) | changes `Dep` if altered |
| A3 | signed-overflow semantics | BV wrapping (what the tool implements) | §3(b) |
| A4 | is `store(B)` the site expression or a substituted one? | site expression, opaque temporaries | **T1 is false if substituted** |
| A5 | width `w` | parametric; hypotheses per theorem | T3 needs `w ≥ 2` |

---

## 3. Attacks on the separating pair — all failed

Pair: `A = (t := r0+1 ; store t-r0)` vs `B = (t := r0+r0 ; store t-r0)`.

**(a) BV identities, Z3-proved over all `2^32` values:** `(x+1)-x == 1` HOLDS;
`(x+x)-x == x` HOLDS; both hold at `0xFFFFFFFF` (unsigned wrap) and `0x7FFFFFFF` (signed
overflow point).

**(b) Undefined behaviour.** Under real C, `x+1` on `signed int` is UB at `INT_MAX`. Under
wrapping semantics `(x+1)-x ≡ 1`; under a UB reading the compiler may *assume* no overflow,
making it `1` on every defined execution. **The separator survives both.** Hypothesis A3,
not a flaw.

**(c) Front-end collapse.** `deriveReg` stores `RegSrc(tryEval(expr), reads)`, and
`tryEval` yields a constant only when the reads are constant. For a symbolic load it is
`null`, so `t` is never replaced by `r0+1`. Both programs really reach `classify` with the
identical string `"t - r0"` — verified on the shipped Java.

**(d) Robustness — four independent separating pairs:**

| store expression | A | B | separates |
|---|---|---|---|
| `t - r0` | FAKE | REAL | yes |
| `t ^ r0` (bitwise) | FAKE | REAL | yes |
| `t & 2` (`r0` **not** in `e`) | FAKE | REAL | yes |
| `u` (depth 2, `r0` not in `e`) | FAKE | REAL | yes |

**(e) Width sweep — a hypothesis the previous work omitted.**

| `w` | `dep(r0+1, r0)` | `dep(r0+r0, r0)` | T3 witness |
|---:|---|---|---|
| **1** | REAL | **FAKE** | **degenerates** |
| 2, 3, 4, 8, 16, 32 | REAL | REAL | valid |

At `w = 1`, `r0+r0 ≡ 0`, so the programs become distinguishable at the dependence-bit
abstraction. **T3 requires `w ≥ 2`** — a hypothesis absent from the prior document. T1 and
T2 are width-robust (T2's contradiction never uses `f("r0+r0","r0")`; UNSAT at all widths).

---

## 4. SMT probes run

| probe | decides | result |
|---|---|---|
| `a1_audit_core.py` | ground truth of P1–P4 over BV32 | `REAL REAL FAKE REAL` |
| `a1_audit_core.py` | satisfiability of the T2 system (6 free booleans) | **UNSAT** |
| `a1_audit_core.py` | do P3/P4 present identical `(e, bits)`? | yes, different truth |
| `a2_attacks.py` | 4 BV32 identities + 4 alternative separators | all hold / all separate |
| `a2_attacks.py` | affine summary vs `chain-n`, `n ≤ 50` | decides **all** |
| `a3_widths.py` | truth + dep-bits + T2 SAT at `w ∈ {1,2,3,4,8,16,32}` | T3 fails at `w=1`; T2 UNSAT everywhere |
| `a4`+`a5` | 400-program differential vs shipped Java | **400/400 agree** |
| `a7_soundness.py` | exhaustive 1–2-operator enumeration | **218/8448 unsound** |
| `a8_corpus_scan.py` | does the bug fire on repo corpora? | 0 occurrences (latent) |
| `audit1–4.jsh` | shipped-Java verdicts, traces, oracle cross-check | see §9 |

The T2 contradiction is **purely propositional** and hand-derivable:

```
P1 (REAL) => f(t,t)=1 , f(r0,r0)=1
P2 (REAL) => f(r0+1,r0)=1
P3 (FAKE) => f(t-r0,r0)=0  and  f(t-r0,t) ∧ f(r0+1,r0)=0  =>  f(t-r0,t)=0
P4 (REAL) => [f(t-r0,t) ∧ f(r0+r0,r0)] ∨ f(t-r0,r0) = [0∧*]∨0 = 0    but truth REAL  ⨯
```

It appeals to no decision procedure, so T2 holds even for an **uncomputable** `f`.

---

## 5. BROKE or SURVIVED

**T1 — SURVIVED.** P3 and P4 present the identical argument `("t - r0","r0")` with
different truth; a function is single-valued. Robust to unlimited power (§7). But see §10 —
it is close to "a function of `X` cannot compute something that is not a function of `X`".

**T2 — SURVIVED,** and it is the theorem that constrains the artifact. UNSAT at every
width. This repairs the invalid inference of §1.

**T3 — SURVIVED for `w ≥ 2`,** and it is the sharpest: it fixes *no* composition scheme,
only the abstraction. The missing ingredient is neither precision nor composition but
**value information** — a boolean "depends / does not depend" summary cannot distinguish
`t = r0+1` from `t = 2·r0`, which require different answers under the same store expression.

---

## 6. Bounded-prefix verdict — **BROKEN**

Claim: *"No bounded amount of surrounding program information suffices; chain-n requires
unfold depth exactly n."* **False**, on two independent grounds.

**(i) A constant-size abstraction decides the entire `chain-n` family.** Summarise each
register by an affine form (coefficient per input, constant) — two `BV_w` values, a size
that does **not** grow with chain length. Verified for `n = 1 … 50`: summary `({x:0}, 0)`,
verdict FAKE, correct in every case. `chain-n` therefore does not witness the claim.

**(ii) "Bounded" is the wrong axis.** For fixed `w` and input count `m`, the set of transfer
functions `BV_w^m → BV_w` is **finite**. The transfer function is a sound and complete
summary, so a bounded-state exact abstraction *trivially exists*. Astronomically large, but
the claim as phrased is about boundedness.

**What survives:** only *"for every `d` there exist two blocks agreeing on the store
expression and on the first `d` definitions whose truths differ"* — a statement about
**prefix truncation**, true and uninteresting. The prior document's §8 states this
correctly; its executive summary over-states it into the false version. **That
over-statement must be withdrawn.**

---

## 7. Abstraction attack verdict (Step 5)

Granting the classifier unlimited power — symbolic rewriting, canonicalization, complete
QF_BV reasoning, SSA metadata, an uncomputable oracle — changes nothing for T1 and T2:

```
program A -> classifier input ('t - r0', 'r0')    truth FAKE
program B -> classifier input ('t - r0', 'r0')    truth REAL
```

Both inputs are literally the same object. The results are impossibilities **of locality**,
not of implementation — and that is also why they are shallow.

---

## 8. Isabelle-ready proof obligations

Target: **T3 for `w ≥ 2`**, with T2 as a corollary path. No step discharged by "clearly".

| id | statement | hypotheses | conclusion | depends | BV-specific? | injectivity? | difficulty |
|---|---|---|---|---|---|---|---|
| **O1** | datatype `expr`, evaluator `⟦_⟧ : expr ⇒ (ident ⇒ 'w word) ⇒ 'w word` | — | total evaluator | — | no (parametric `'w`) | no | **Low** |
| **O2** | `block` datatype; `subst` as least fixed point of one-step unfolding | defs acyclic, ordered | total, terminating | O1 | no | no | **Low** (well-founded on def index) |
| **O3** | `⟦subst B⟧ σ = transfer B σ` | O2 | adequacy of substitution | O1,O2 | no | no | **Medium** |
| **O4** | `Dep B x ≡ ∃σ a b. transfer B (σ[x:=a]) ≠ transfer B (σ[x:=b])` | — | definition | O3 | no | no | **Low** |
| **O5** | `dep_of ρ x`; `bits B x` as the register tuple | — | definition | O1 | no | no | **Low** |
| **O6** | `∀(v::'w word). (v+1) - v = 1` | — | BV identity | — | **yes** | no | **Low** |
| **O7** | `∀(v::'w word). (v+v) - v = v` | — | BV identity | — | **yes** | no | **Low** |
| **O8** | `transfer P3 = (λσ. 1)` | O3,O6 | P3 constant | O3,O6 | yes | no | **Low** |
| **O9** | `transfer P4 = (λσ. σ r0)` | O3,O7 | P4 = identity | O3,O7 | yes | no | **Low** |
| **O10** | `¬ Dep P3 r0` | O4,O8 | P3 FAKE | O4,O8 | no | no | **Low** |
| **O11** | `Dep P4 r0` | O4,O9, `w ≥ 1` | P4 REAL | O4,O9 | yes (needs `2^w > 1`) | no | **Low** |
| **O12** | `dep_of (r0+1) r0 = True` | `w ≥ 1` | P3's temp bit | O5,O6 | yes | no | **Low** |
| **O13** | `dep_of (r0+r0) r0 = True` | **`w ≥ 2`** | P4's temp bit | O5 | **yes — FAILS at `w=1`** | no | **Medium** (width hypothesis essential) |
| **O14** | `store P3 = store P4` | — | syntactic equality | O2 | no | no | **Low** |
| **O15** | `bits P3 r0 = bits P4 r0` | O12,O13 | identical abstraction input | O12,O13 | yes | no | **Low** |
| **O16** | **T3** | `w ≥ 2` | the theorem | O10,O11,O14,O15 | yes | no | **Low** (function extensionality) |
| **O17** | the shipped `S_f` equals the recursion of §1 | faithful model | model adequacy | — | no | no | **High — not mechanizable** (a claim about Java) |
| **O18** | **T2** | O17 | shipped architecture inexact | O10,O11,O17,+P1/P2 | no | no | **Low** given O17 |

**O17 is the weakest link** and is not a mathematical obligation. I therefore tested it
empirically rather than assuming it — see §9, which is where the soundness bug surfaced.

---

## 9. O17 tested — and the soundness bug it exposed

**Round 1 (invalid).** 300 random programs, model vs shipped Java: 17/256 divergences.
Diagnosis: my model instantiated `f =` exact QF_BV, while the shipped default has
`CONSTANCY_ORACLE = null` and uses the **solver-free fallback**. Different `f`s — the test
measured the classifier, not the composition. *(This also means the prior session's `SITE`
model was mislabelled: it measured "shipped architecture + exact oracle", not the shipped
detector. Its separators survive a fortiori, since the fallback is more conservative.)*

**Round 2 (also invalid).** Re-ran with a faithful reimplementation of the shipped
fallback: 14 divergences remained, and **10 apparent under-approximations**. Before
reporting a soundness violation I probed the front end directly and found two lexer
artifacts:

```
WRITE_ONCE(*y, ((~(r0 ^ r0)) - (255 | r0)))  ->  detector sees  '((~(r0 ^ r0'   TRUNCATED at first ')'
WRITE_ONCE(*y, (r0) )                        ->  detector sees  '(r0'
WRITE_ONCE(*y, 255 | r0)                     ->  ParseException  ('|' is the thread-column separator)
```

Ground truth had been computed on the full expression while the tool classified a
truncated one. **I retract that alarm — it demonstrated nothing.**

**Round 3 (valid).** 400 programs, parenthesis-free and pipe-free:

```
compared : 400    AGREE : 400    DISAGREE : 0
=> the reconstructed composition is faithful. O17 tested. T2's premise stands.
```

**But 7 under-approximations survived on the valid corpus.** Traces from the shipped Java:

| case | `classify` call | verdict | true parse | truth |
|---|---|---|---|---|
| #312 | `("~r0 - r0", r0)` | FAKE `[r-r]` | `(~r0) - r0 = -2r0-1` | **varies** |
| #72 | `("t0 ^ t0 & 7 & 7", t0)` | FAKE `[r^r]` | `t0 ^ (t0&7)` | **varies** |
| #91 | `("r0 & r0 - r0 + 3", r0)` | FAKE `[r-r+r&0]` | `r0 & 3` | **varies** |
| #205 | `("~7 - r0 ^ r0", r0)` | FAKE `[r^r]` | `(~7-r0) ^ r0` | **varies** |
| #313 | `("~r0 ^ r0 + r0 ^ r0", r0)` | FAKE `[r^r]` | `(~r0)^(r0+r0)^r0` | **varies** |
| #383 | `("~r0 & r0 ^ r0", r0)` | FAKE `[r^r+r&0]` | `(~r0&r0)^r0 = r0` | **varies** |

**Root cause.** `classify` folds by string rewriting:

```java
e = e.replaceAll(bounded(pattern), "0");
bounded(p) = "(?<![A-Za-z0-9_])(?:" + p + ")(?![A-Za-z0-9_])"
```

The guard only checks that the match is not adjacent to an identifier character. It does
**not** check that the matched `r OP r` is a **sub-term**. So `~r0 - r0` contains the
substring `r0-r0`, which is folded to `0`, leaving `~0` — which no longer mentions `r0`,
so the verdict is FAKE. The source comment calls this the "adjacency-based pattern
folder"; the defect is that adjacency is measured in the **string**, not the parse tree.

**The oracle does not save it.** `classify` returns FAKE on `!mentionsReg` *before*
consulting `CONSTANCY_ORACLE`:

```
expression ~r0 - r0        (ground truth VARIES => REAL)
  shipped, no oracle  : FAKE
  shipped, QF_BV on   : FAKE          <- short-circuit fires first
  direct oracle query : isConstant = false   (VARIES)
```

So the bug is present in **every shipped configuration**, including the QF_BV runs behind
`eval/qfbv-detector-results.csv` and the Layer-2 audit. `QfbvDetectorRun` cannot detect it:
it compares oracle-on vs oracle-off edge counts, and the fold short-circuits identically in
both, so it reports no divergence.

**This falsifies a documented property.** `LitmusParser`'s javadoc states
`sdep_true ⊆ sdep_impl` is preserved and that "no genuinely value-carrying dependency is
dropped". That is true of the *linear* pass it describes, but false of the detector as a
whole, because the folder runs first and is not proof-based.

**Direction and consequence.** The error removes edges from `sdep`. In the model that can
break a `jf ∪ sdep` cycle that ought to exist, turning a **FORBIDDEN outcome into
ALLOWED** — unsoundness in the permissive direction.

**Scale and exposure.**

| measurement | result |
|---|---|
| exhaustive enumeration (1–2 binary ops over `{r0,1,2,7}`, with `~`) | **218 / 8448 = 2.58 % unsound** |
| patterns implicated | `r&~r` (13), `r^r` (11), `r-r` (1) |
| occurrences in `eval/examples`, `eval/corpus`, `eval/mined/litmus` (29,202 files, 1,740 distinct value expressions, 165 checkable pairs) | **0** |

The bug is **real, reachable, and confirmed**, but currently **latent**: no shipped test
exercises it, so published numbers are very likely unaffected. The corpus scan is
regex-based extraction and therefore indicative, not exhaustive.

Realistic triggers exist. `r0 ^ r0 + 1` parses as `r0 ^ (r0+1)` and is misclassified; so is
`r0 & ~r0 + 1`, i.e. `r0 & (-r0)` — the standard Hacker's-Delight lowest-set-bit idiom,
which appears in `eval/mined/src/rwlock_bitops.c`.

**Exposure is bounded to the C dialect.** I tested whether the fold is reachable from the
assembly front end. It is not: `lexRegisterAsm` builds arith expressions as
`ops[1] + opSym(op) + ops[2]` — always a **flat two-operand string** — and `opSym` never
emits `~`. Any pattern match in such a string is therefore necessarily a genuine sub-term.
Verified:

```
EOR W2,W0,W0   ->  classify('W0^W0', W0) = FAKE [r^r]     correct
MVN W1,W0
SUB W2,W1,W0   ->  classify('W1-W0', W0) = REAL           correct (no spurious fold)
```

Since the bulk of the validation corpora (`herdtools7`, `Dat3M`) are assembly, this
materially narrows the blast radius.

### Incidental finding: `MVN` is modelled as addition

`opSym` has `default -> "+"`, and `ARM_LEX` places `mvn` in the **arith** row. A two-operand
`MVN Wd,Wm` therefore becomes the expression `Wd+Wm`:

```
MVN W1,W0   ->  classify('W1+W0', W0) = REAL
```

`MVN` is bitwise NOT, not addition. This is a **value-model** defect, not a dependency
soundness defect: the edge from `W0` is still recorded REAL, so `sdep` is not
under-approximated. But `tryEval`/`evalConst` will compute wrong constants, which feeds
rf-wiring. Lower severity, but it should be recorded. Same class of issue: `opSym`'s
silent `default` swallows every unmodelled opcode into `+`.

**Why previous sessions missed it.** The prior soundness check (0 violations in 2,758
checks) modelled the local classifier as the exact QF_BV query, which never exercises the
fold. It could not have found this.

---

## 10. Vacuity audit

**Attack 1 — "the classifier class excludes exactly the needed information."**
**HIGH for T1, LOW for T3.** T1 reduces to "a function of `X` cannot compute something that
is not a function of `X`"; the information was removed by fiat and the theorem observes its
absence. It also describes no system anyone built — the shipped detector is not in T1's
class. T3 escapes this: boolean dependence bits are a real, widely-used abstraction
(classical dependence analysis computes exactly that), so showing them insufficient is
informative.

**Attack 2 — "a slightly larger classifier class invalidates it."**
**HIGH for T1, MEDIUM for T3.** Hand T1's classifier the *substituted* expression and it is
exact immediately — T1 is entirely an artifact of ambiguity **A4**. For T3, replace the
boolean by any value-level summary and exactness returns for large fragments. T3 is best
read as a **lower bound on summary richness** (booleans are not enough), not as an
impossibility.

**Attack 3 — "exactness is circular."** **LOW.** `Dep` is defined from `⟦B⟧` (O3–O4),
independently of any classifier. There is a modelling choice (**A2**), but it applies
uniformly to every classifier, so the arguments are unaffected.

**Net:** T1 should be **demoted** from "theorem" to "observation" or restated as T3; T3
should be restated as a lower bound on abstraction richness.

---

## 11. Confidence assessment

Based only on evidence collected today.

| claim | confidence TRUE | note |
|---|---:|---|
| T1 as literally stated | **99 %** | one-line pigeonhole, robust to unlimited power |
| T1 is non-vacuous / useful | **20 %** | vacuity attacks 1–2 |
| T2 (shipped composition, any `f`) | **98 %** | UNSAT at 7 widths + hand derivation + O17 now tested 400/400 |
| T3 (with `w ≥ 2`) | **96 %** | witness SMT-verified at 6 widths; degenerates at `w=1` |
| Bounded-prefix subclaim as phrased | **3 %** — **BROKEN** | two independent refutations |
| Prior T1 ⟹ shipped-detector corollary | **0 %** — invalid inference | repaired by T2/T3 |
| **Soundness bug in `classify`'s fold is real** | **99 %** | shipped trace + repo's own oracle disagreeing + 218 enumerated witnesses |
| **Bug currently affects published results** | **5 %** | 0 occurrences in shipped corpora; **not reachable from the asm front end**, and the bulk of the corpora are asm |
| `MVN` modelled as `+` (incidental) | **99 %** | direct trace; value-model only, not a soundness issue |

**Strongest surviving attack (on the theorem).** Vacuity attack 2 against T1: the theorem
is an artifact of ambiguity A4. If `store(B)` were the substituted expression, T1 is
*false*. Its entire content rests on an unstated modelling choice — state it or withdraw T1.

**Strongest remaining uncertainty.** Whether the fold bug is reachable at **`addr` and
`ctrl` dependency sites** in the C dialect. I exercised data dependencies only.
`addAddrDeps` routes through `RegSrc.reads` rather than calling `classify` on an address
expression, and `BRANCH` feeds the guard text to the same machinery — so the composition
differs there and I have not modelled it. A second, smaller uncertainty: the corpus scan
extracted value expressions by regex and found only 165 checkable `(expr, reg)` pairs out
of 1,740 distinct expressions, so its coverage is thin.

**Single most valuable next experiment.** Not further theorem work. Two concrete fixes to
`classify`, each independently testable:
1. fold over a **parsed AST** rather than the raw string, so a pattern can only match a
   genuine sub-term;
2. move the `!mentionsReg` short-circuit to **after** the `CONSTANCY_ORACLE` consultation,
   so an installed exact oracle can override the fold rather than being bypassed.

Either alone removes the observed unsoundness; (2) is a two-line change and would have
made the bug detectable by the existing `QfbvDetectorRun` audit.

---

## 12. Required corrections to `findings-semantic-transformations.md`

1. **§8 corollary is a non sequitur.** T1 does not constrain the shipped detector. Replace
   the inference with T2 or T3.
2. **Executive summary over-states the bounded claim.** "No bounded amount of surrounding
   program information suffices" is **false**; only prefix truncation is true.
3. **T3-style statements need `w ≥ 2`.** No width hypothesis is stated anywhere.
4. **Ambiguity A4 must be stated**: T1 holds only because store expressions may contain
   opaque temporaries.
5. **The `SITE` model is mislabelled** — it uses the exact QF_BV oracle, not the shipped
   default fallback. Every claim about "the shipped detector" should say
   "shipped architecture + exact oracle", or be re-measured with the fallback.
6. **The soundness claim must be withdrawn.** "0 soundness violations in 2,758 checks" was
   measured against a model that never exercises the idiom folder; the folder is unsound.

### Artifacts

| path | contents |
|---|---|
| `eval/research/a1_audit_core.py` | reconstruction, ground truth, T1/T2/T3 |
| `eval/research/a2_attacks.py` | separator attacks, bounded-prefix refutation, abstraction attack |
| `eval/research/a3_widths.py` | width sweep; discovery of `w ≥ 2` |
| `eval/research/a4_differential.py` | O17 corpus generator (documents both lexer traps) |
| `eval/research/a5_shipped_model.py` | faithful reimplementation of the shipped fallback; O17 comparison |
| `eval/research/a6_gen_trace.py` | trace-script generator for suspect cases |
| `eval/research/a7_soundness.py` | exhaustive enumeration; 218/8448 unsound |
| `eval/research/a8_corpus_scan.py` | corpus exposure scan (0 occurrences) |
| scratchpad `audit1–4.jsh` | shipped-Java verdicts, traces, oracle cross-check |
