# Findings — Task 0a: the PwT oracle's quantifier

**Verdict: UNIVERSAL-v. The paper's wording is substantively correct.** The comparison
value is not anchored at an observed or most-recent local value — it *cannot* be, because
the code path that produces the 79-pair sweep never sees an execution at all.

Three corrections are still needed, one of them material (§4).

---

## 1. The file the paper/task names does not exist

`src/main/java/wev/smt/core/PwTOracle.java` is not in the repository. There is no
`PwTOracle` class and no `wev.smt.core` PwT file. The PwT-side logic lives in two places:

| Role | Actual path | Package |
|---|---|---|
| The sweep that produced `eval/pwt-equivalence-sweep.csv` | `src/main/java/wev/smt/cli/PwtEquivalenceSweep.java` | `wev.smt.cli` |
| The exact QF_BV constancy oracle | `src/main/java/wev/smt/QfbvConstancyOracle.java` | `wev.smt` (**not** `wev.smt.core`) |

If the paper or artifact appendix cites a class name or path for this result, it must be
corrected to the two above.

## 2. The exact oracle: unambiguously universal in *both* points

`QfbvConstancyOracle.compute` (lines 103–116) builds two independent copies of the
expression and asks Z3 whether they can differ:

```java
Map<String, BitvectorFormula> shared = new HashMap<>();
for (String id : idents) {
    if (!id.equals(reg)) shared.put(id, bv.makeVariable(W, "v_" + id));
}
Map<String, BitvectorFormula> env1 = new HashMap<>(shared);
Map<String, BitvectorFormula> env2 = new HashMap<>(shared);
env1.put(reg, bv.makeVariable(W, "reg__1"));
env2.put(reg, bv.makeVariable(W, "reg__2"));
BooleanFormula differ =
        bmgr.not(bv.equal(eval(ast, env1), eval(ast, env2)));
try (ProverEnvironment p = ctx.newProverEnvironment()) {
    p.addConstraint(differ);
    return p.isUnsat() ? (byte) 1 : (byte) 0;   // UNSAT=const, SAT=varies
}
```

with the query stated in the class Javadoc (line 32) as

```
∃ (other regs), r1, r2 .  e[reg := r1] ≠ e[reg := r2]
```

`reg__1` and `reg__2` are *free* 32-bit variables — neither is pinned to `0` nor to any
observed value. `W = 32` (line 51). This is exactly non-constancy over B³². Note the
quantifier on the *other* registers is also existential and the two copies **share** them
(`shared` is copied into both `env1` and `env2`), so the criterion is:

> `e` depends on `reg` iff **there exists** a valuation of the other registers under which
> `e` is non-constant in `reg`.

That is "there exists a context", not "for all contexts" — worth stating explicitly in the
paper, since it is the weaker (dependency-admitting) of the two readings.

## 3. The sweep oracle: universal but *sampled*, anchored at 0 as reference point

`PwtEquivalenceSweep.nonConstantIn` (lines 145–172) is a separate, independent
implementation — and it is the one that actually produced the 79 rows:

```java
Integer first = null;
for (int v : PROBE) {
    env.put(reg, v);
    int val = new Eval(expr, env).parse();
    if (first == null) first = val;
    else if (val != first) return true;    // two assignments differ ⇒ real
}
```

`first` is assigned on the first iteration and never reassigned, and `PROBE[0] == 0`
(line 55). So this is literally the paper's `∃v. e[r:=v] ≠ e[r:=0]` form. Anchoring the
reference point at `0` loses nothing relative to a genuine two-point `∃v1,v2` search,
because `0 ∈ PROBE`: if every probe agrees with `e[r:=0]` then every pair of probes agrees.

**The caveat the paper should carry:** this is a *finite probe battery*, not a decision
procedure — 17 values for `reg` (line 54–58) crossed with up to `MAX_OTHER_ASSIGNMENTS =
20_000` sampled assignments of the other registers (lines 60–63, 151–170). It is sound in
the REAL direction (a witnessed difference is a real difference) and only heuristically
complete in the FAKE direction. For the 79 pairs actually swept this is immaterial — every
expression is decidable by inspection (see §4) — but the paper must not describe the
79/79 number as coming from an exhaustive or symbolic check. If an exhaustive claim is
wanted, re-run the sweep against `QfbvConstancyOracle` and cite that instead.

## 4. **Material caveat: none of the 79 pairs can distinguish the variant from exact R4b**

Since the code is universal-v, the divergence example is not needed to correct the paper —
but it is needed to calibrate how much the 79/79 number is worth.

Observed-value-anchored R4b and universal-v non-constancy diverge on masking. Take
`e = r & 2` with observed value `r = 1`:

- observed-anchored two-point: `e[r:=1] = 0` and `e[r:=0] = 0` — equal, so **no dependency**;
- universal-v non-constancy: `e[r:=2] = 2 ≠ 0 = e[r:=0]` — so **dependency**.

**How many of the 79 swept pairs exercise such an expression: zero.** The only numeric
literal anywhere in `eval/pwt-equivalence-sweep.csv` is `+1`, in the eight pairs
`r3+1 … r10+1`. There is no constant mask in the corpus. The complete set of operator
signatures across all 79 rows is `{none}, +, -, ^, &, |` — and every `&`/`|` occurrence is
register-on-register (`W6&W5`, `W10&W5`, `W10|W5`, `WZR|W4`, `XZR|X8`), never
register-on-immediate. Full expression inventory:

```
r            r+r          r+1          r^r          r1^r2
r1&r2        r1|r2        r1+r2        r1-r2
```

Consequently the 79/79 agreement result is **silent** on the variant-vs-exact-R4b
distinction: it neither supports nor refutes agreement with published R4b, because the
corpus contains no expression on which the two criteria could possibly differ. If the paper
currently leans on 79/79 as evidence that the variant is a faithful stand-in for R4b, that
inference is unsupported and should be dropped; the per-site lemma is doing all the work.

(Data check, for the record: `eval/pwt-equivalence-sweep.csv` has 79 data rows and
`agree == true` on all 79. That part of the claim is confirmed.)

## 5. The per-site lemma is not mechanized

`grep -rni "pwt\|transformer\|R4b" isabelle/` returns nothing. The Isabelle development
(`WEV_SMT_Defs.thy`, `Detector.thy`, `Soundness.thy`) treats constancy as an *uninterpreted*
predicate `expression_constant :: event ⇒ event ⇒ bool`, axiomatized only via
`oracle_soundness` and `oracle_completeness_branch_free`. There is no BV32 expression
syntax, no evaluation function, and no PwT transformer anywhere in the development. So the
"2-line lemma" proving per-site equivalence is a paper-level pen-and-paper lemma; the paper
must not imply it is machine-checked.

This is also the concrete starting gap for Task 1: the correspondence target has to be
built essentially from scratch (expression syntax + semantics + transformer), because the
existing abstraction deliberately hides exactly the structure the correspondence is about.

---

## Recommended paper wording

The claim "v ranges universally, a variant of R4b" is **correct** and needs no retraction.
Suggested tightening:

> WEV-SMT's criterion is site-wise non-constancy over 32-bit bitvectors: register `r`
> carries a semantic dependency into expression `e` iff there exist a valuation of the
> remaining registers and values `v₁, v₂ ∈ B³²` with `e[r:=v₁] ≠ e[r:=v₂]`. This is a
> variant of PwT's R4b in which the comparison value is universally quantified rather than
> anchored at the observed local value; the two coincide per-site on expressions that are
> either constant in `r` or injective in `r`, and can differ on masking expressions such as
> `r & 2` at observed value `1`. The corpus sweep contains no masking expression, so the
> 79/79 agreement figure speaks only to our detector-vs-non-constancy agreement, not to
> agreement with R4b as published.

Delete or correct any citation of `wev.smt.core.PwTOracle`.
