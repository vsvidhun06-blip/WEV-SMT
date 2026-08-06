# findings-semantic-transformations.md

Investigation of the mathematics behind semantic dependency: what is the strongest
decidable, SMT-encodable criterion strictly stronger than the shipped `sdep`?

Research only. No production code, encoder, detector or Isabelle theory was modified.
All probes live in `eval/research/` and are re-runnable.

---

## 1. Executive summary

**The gap is not caused by SSA copies.** Copy propagation explains only one sub-case of
one of the two independent mechanisms at work. The two mechanisms are:

| # | mechanism | why the shipped criterion misses it | example |
|---|---|---|---|
| (a) | **correlation** — two site registers are functionally dependent through a shared input | the site query varies one register at a time; the program varies them together | `t := x+1; store t-x` |
| (b) | **range restriction** — a site register cannot take all 2³² values | the site query quantifies it over all of BV32 | `t := x&1; store t&2` |

Both are subsumed by one transformation, and it is **not a normalization**: transitive
substitution of every temporary by its defining term, i.e. *computing the block's
transfer function*. Recast properly, the criterion is not a rewrite at all — it is the
standard **2-safety / non-interference** question, decided by **self-composition**.

**The three headline results.**

1. **Impossibility (proved).** No criterion that is a function of the store expression
   and register alone can be exact. The shipped detector is not merely imprecise, it is
   *information-starved*; no improvement to its decision procedure closes the gap.
   Strengthened: no criterion inspecting a *bounded prefix* of the environment works
   either.
2. **Exactness fragment (proved, with maximality).** The shipped criterion is exact on
   environments that are *input-separated and range-full* (fragment **C2** below), and
   every environment violating C2 with a proper joint image admits a separator built from
   `==` and `&` alone — one uniform construction covering both mechanisms, needing no
   inverse expressions. One case remains open (surjective-yet-correlated, §2). Crucially,
   **C2 is a condition on the environment, never on the expression's syntax** — every
   binary operator in the grammar admits a cancelling environment (7/7), so no syntactic
   fragment exists.
3. **Cost (measured).** The candidate criterion stays QF_BV in the same complexity class,
   and needs **no preprocessing pass at all** if encoded as a two-copy product query
   (linear). Implementing it as "normalize, then re-run the oracle" is **exponential** in
   the worst case — measured 524,283 tokens vs 35 assertions at block depth 16, on
   exactly the repeated-temporary shape register allocation produces.

**Honest assessment of novelty.** Results 1–3 are solid characterization work and settle
the design question. But the core move — semantic dependency *is* non-interference,
so decide it by self-composition — is a well-known technique (Barthe, D'Argenio & Rezk,
*Secure Information Flow by Self-Composition*, CSFW 2004), not a new contribution. What
is genuinely ours is the exactness fragment C2 with its maximality construction, the two
impossibility theorems, and the hierarchy with its 49 separators. I would not claim a new
theory here; I would claim a precise characterization of a gap and its cheapest correct
closure. See §9.

---

## 2. Primary conclusion

The answer is a **hybrid of (A) and (B)**.

### (A) There is a maximal-so-far fragment where the shipped criterion is exact

**Definitions.** A loop-free block is `B = (X, D, e)`: inputs `X = {x₁..x_m}` (load
results), ordered definitions `D = [t₁ := ρ₁, …, t_n := ρ_n]`, store expression `e`.
Write `⟦·⟧` for the BV_w semantics and `ρ⃗*` for the transitive unfolding of `D`.

- `SUBST(x_j) ⟺ ∃σ ∈ BV_w^{m-1}, ∃a,b ∈ BV_w. ⟦e⟧(ρ⃗*(σ,a)) ≠ ⟦e⟧(ρ⃗*(σ,b))`
  (other inputs `σ` shared between the two copies)
- `nonconst(e,t) ⟺ ∃τ over FV(e)\{t}, ∃u,v. ⟦e⟧(τ,u) ≠ ⟦e⟧(τ,v)`  — the shipped oracle
- `SITE(x_j, e) ⟺ ⋁_{t ∈ FV(e)} [ nonconst(e,t) ∧ SITE(x_j, ρ_t) ]`, base
  `SITE(x_j,x_j)=⊤`, `SITE(x_j,x_k)=⊥` for `k≠j` — the shipped detector *including* its
  chaining through `referencedReads`

> **Theorem C2 (exactness).** Suppose there is an **injective** `σ : FV(e) → X` with
> (i) every `t ∈ FV(e)` defined by `ρ_t` depending only on the single input `x_{σ(t)}`, and
> (ii) every `⟦ρ_t⟧ : BV_w → BV_w` a **bijection**.
> Then `SUBST(x_j) ⟺ SITE(x_j, e)` for every input `x_j`.

**Proof.** If `x_j ∉ σ(FV(e))`, no `t ∈ FV(e)` has `x_j` in its support, so `ρ⃗*` restricted
to `FV(e)` is independent of `x_j` and `SUBST(x_j)` is false; likewise `SITE(x_j, ρ_t)` is
false for every `t ∈ FV(e)`, so `SITE(x_j,e)` is false.

Otherwise `x_j = σ(t_i)` for a unique `t_i` (σ injective). By (i) the map
`Φ : x⃗ ↦ (⟦ρ_t⟧(x_{σ(t)}))_{t ∈ FV(e)}` is a *product* map, and by (ii) each component is a
bijection. Hence fixing the inputs other than `x_j` fixes exactly the values of the `t ≠ t_i`,
and lets `t_i` range over all of `BV_w`; and as those other inputs range freely, the fixed
values of the other `t`s range over all of `BV_w^{FV(e)\{t_i}}`. Therefore
`SUBST(x_j) ⟺ ∃τ, ∃u,v. ⟦e⟧(τ,u) ≠ ⟦e⟧(τ,v) ⟺ nonconst(e, t_i)`.
On the other side, `SITE(x_j, ρ_t)` holds only for `t = t_i` (σ injective plus (i)), and
there equals `nonconst(ρ_{t_i}, x_j)`, true because `⟦ρ_{t_i}⟧` is a bijection hence
non-constant. So `SITE(x_j,e) = nonconst(e,t_i)` as well. ∎

**Tightness (SMT-verified).** Dropping any single conjunct of C2 yields a separator —
5/5, `eval/research/p3_fragment.py`:

| relaxation | witness | SITE | SUBST |
|---|---|---|---|
| drop (ii) bijectivity | `t₁ := x₁&1; store t₁&2` | REAL | FAKE |
| drop (ii) bijectivity | `t₁ := 4·x₁; store t₁&3` | REAL | FAKE |
| drop σ injectivity | `t₁ := x₁; t₂ := x₁+1; store t₂-t₁` | REAL | FAKE |
| drop σ injectivity | `t₁ := x₁^3; t₂ := x₁^5; store t₁^t₂` | REAL | FAKE |
| drop (i) single-input | `t₁ := x₁; t₂ := x₁+x₂; store t₂-t₁` | REAL | FAKE |

**Randomized support, and a deliberate attempt to falsify it.** Two independent searches:

| search | blocks | (block,input) checks | counterexamples |
|---|---:|---:|---:|
| `p4` — random bijections, depth ≤ 3 | 250 | 491 | **0** |
| `p9` — adversarial: *composed* bijections, odd multipliers, carry/borrow-boundary constants (`0x7fffffff`, `0x80000000`, …), relational operators, up to 4 registers | 1200 | 2991 | **0** |

A single counterexample would refute the proof above; none was found.

**A refuted intermediate conjecture, recorded.** I first conjectured that *surjectivity of
the environment map* suffices. It does not: `t₁ := x₁; t₂ := x₁+x₂` is surjective onto
BV32² yet `store t₂-t₁ = x₂` separates. Surjectivity controls each register's *range* but
not their *correlation*. C2's injectivity/single-input conditions are what rule correlation
out.

### (B) No fragment characterized by the expression's syntax exists

For **every** binary operator in the grammar there is an environment making a store
expression of that shape a separator (7/7, `p3_fragment.py`):

| operator | environment | store | true value |
|---|---|---|---|
| `-` | `t₁:=x, t₂:=x` | `t₁-t₂` | 0 |
| `^` | `t₁:=x, t₂:=x` | `t₁^t₂` | 0 |
| `&` | `t₁:=x, t₂:=~x` | `t₁&t₂` | 0 |
| `\|` | `t₁:=x, t₂:=~x` | `t₁\|t₂` | −1 |
| `+` | `t₁:=x, t₂:=−x` | `t₁+t₂` | 0 |
| `*` | `t₁:=x, t₂:=x&0` | `t₁*t₂` | 0 |
| `==` | `t₁:=x, t₂:=x` | `t₁==t₂` | 1 |

So the safe fragment can **never** be identified by restricting which operators appear in
`e`. This is the precise sense in which the problem is not about expression syntax.

### Maximality of C2 — proved wherever the joint image is proper

Let `G ⊆ BV_w^k` be the image of the environment map on `FV(e)`.

> **Theorem (uniform separator).** If `G ⊊ BV_w^k` and at least one `ρ_t` is non-constant
> in `x_j`, then C2's conclusion fails, witnessed by a separator expressible with only
> `==` and `&`:
> ```
> pick c⃗ ∉ G ;   e := (t₁ == c₁) & (t₂ == c₂) & … & (t_k == c_k)
> ```

**Proof.** `e ≡ 0` on `G`, so the stored value is constant as the inputs range and
`SUBST = FAKE`. But `e(c⃗) = 1`, so `e` is non-constant on `BV_w^k`; moreover
`nonconst(e, t_i)` holds for each `i` (fix the others at `c`, vary `t_i`). Hence
`SITE = REAL` as soon as some `ρ_{t_i}` is non-constant in `x_j`. ∎

This single construction covers **both** ways C2's hypotheses can fail while leaving `G`
proper, and needs **no inverse expression anywhere**:

- some `⟦ρ_t⟧` non-surjective ⇒ the `t`-projection of `G` misses a value ⇒ `G ⊊ BV_w^k`;
- `σ` not injective (two site registers over one input) ⇒ `|G| ≤ 2^w < 2^{wk}` ⇒ `G ⊊ BV_w^k`.

Verified 6/6 (`p10_maximality.py`), including a case where `ρ₁ = x·x` has no inverse at
all. This **upgrades the earlier conjecture to a theorem** for every proper-image
violation; the inverse-based separators (`t₂ == ρ₂(ρ₁⁻¹(t₁))`, verified 3/3 including
`3⁻¹ = 2863311531 mod 2³²`) are now merely a convenience, not the argument.

*Non-degeneracy is genuinely required.* `t := (x==x)+4` has image `{5}` yet is constant in
`x`, so the shipped criterion already answers FAKE and there is nothing to separate. The
probe caught this; it is a real side condition, not an artifact.

**The one remaining open case: `G = BV_w^k` with correlation.** The point-indicator
construction needs a point outside the image, so it says nothing about environments that
are surjective yet correlated — e.g. `t₁ := x₁, t₂ := x₁+x₂`, which is onto `BV_w²`. These
*are* separable (`t₂ − t₁ = x₂`, verified 3/3), but by an expression that is constant on
each **fibre** (the curve traced by varying `x₁` at fixed `x₂`) rather than off the image.
In every instance tried, the environment was an invertible change of coordinates and the
separator was the complementary coordinate.

> **Conjecture (all that remains of maximality).** Whenever the environment map is a
> grammar-expressible bijection of `BV_w^m`, some complementary coordinate is also
> grammar-expressible, so a fibre-constant separator exists.

Unproved. It is the only gap between "C2 is a tight sufficient condition" and "C2 is
*the* maximal fragment".

---

## 3. The discovered transformation

It is **not** SSA normalization, and it is **not** a normalization at all.

> **Definition (transfer-function extraction).**
> **Input language.** Loop-free basic block over BV_w: inputs `X`, ordered definitions
> `t_i := ρ_i` with `ρ_i` over `X ∪ {t_1..t_{i-1}}`, one store expression `e`. Term grammar
> `| ^ & + - * ~` unary-minus and relational operators yielding 0/1 (the shipped oracle's
> grammar).
> **Output language.** The same term grammar over `X` **only** — the block's transfer
> function for the stored value. Equivalently (and preferably) the *unchanged* definition
> system together with a designated output variable.
> **Transformation performed.** The least fixed point of one-step unfolding: replace every
> temporary by its defining term, repeat to convergence. Nothing else.
> **Deliberately excluded.** Algebraic simplification, reassociation, strength reduction,
> constant folding, canonical ordering — *all rewriting*. These are unnecessary: the QF_BV
> oracle is already complete on this fragment, so simplification is a proof-search
> heuristic, not a semantic requirement.

**What it subsumes.** Copy propagation, constant propagation, value numbering and CSE are
all special cases or no-ops: once definitions are unfolded, syntactically equal
subexpressions are *identical*, so value numbering and CSE have nothing left to
contribute, and copy/constant propagation are the depth-1 restrictions of unfolding.

**Preferred realization — no transformation at all.** Because the criterion is
2-safety, it can be posed directly as a **self-composition** query over the block's
*existing* definitions (§6): two copies agreeing on every input but `x`, asking whether the
stored values can differ. This computes the same predicate without constructing the
substituted term.

---

## 4. Separator catalogue

**49 separators**, all SMT-decided, consolidated in `eval/research/separators.csv` by
`p8_catalog.py`. Every one has `SITE = REAL`, `SUBST = FAKE`.

| category | n | representative |
|---|---:|---|
| established witnesses | 3 | `t:=r; store r−t` · `t:=x+1; store t−x` · `t:=x&1; store t&2` |
| sharing, bijective defs | 6 | `t:=~x; store t+x` · `t:=3x; store t−3x` · `t₁:=x^3,t₂:=x^5; store t₁^t₂^6` |
| range restriction | 6 | `t:=2x; store t&1` · `t:=x·x; store t&2` · `t:=x·2³¹; store t&0x7fffffff` |
| multi-temporary / mixed | 7 | `a:=x&y,b:=x\|y; store a+b−x−y` · `a:=x^y,b:=x&y; store a+2b−x−y` |
| operator coverage | 7 | one cancelling environment per binary operator |
| **point-indicator** (uniform maximality construction) | 6 | `t₁:=x,t₂:=x+1; store (t₁==5)&(t₂==9)` |
| range restriction, `k=1` | — | `t:=x&1; store t==2` (same construction, degenerate `k`) |
| functional dependence (inverse-based) | 3 | `t₁:=3x,t₂:=x; store t₂ == 2863311531·t₁` |
| **fibre-constant** (surjective yet correlated) | 3 | `t₁:=x₁,t₂:=x₁+x₂; store t₂−t₁` |
| correlated but surjective (refutes conjecture C1) | 2 | `t₁:=x₁,t₂:=x₁^x₂; store t₂^t₁` |
| chain depth | 2 | `chain-3`, `chain-5` |
| shifts (outside grammar) | 3 | `t:=x<<1; store t&1` |
| memory must-alias | 1 | `m:=x; t:=m; store t−x` |

**Depth staircase** (`p2b_depth.py`) — for `chain-n`, depth-`k` substitution answers FAKE
only at `k = n`:

```
block      SUBST   k=0   k=1   k=2   k=3   k=4   k=5   k=6
chain-1    FAKE    REAL  FAKE  FAKE  FAKE  FAKE  FAKE  FAKE
chain-3    FAKE    REAL  REAL  REAL  FAKE  FAKE  FAKE  FAKE
chain-6    FAKE    REAL  REAL  REAL  REAL  REAL  REAL  FAKE
```

**Transformation classes refuted** (`p2_minimal.py`, 8 test blocks):

| class | blocks missed |
|---|---:|
| no transformation (shipped) | 8 |
| constant propagation only | 8 |
| copy propagation only | 7 |
| copy + constant propagation | 7 |
| affine substitution (`+ −` only) | 4 |
| **full transitive substitution** | **0** |

---

## 5. Detector hierarchy

Criteria compared as **sets of edges classified REAL**; a stronger detector finds more
fakes, hence is a **subset**.

```
SDEP_true  ⊆  SDEP_subst  ⊆  SDEP_site  ⊆  SDEP_patterns
```

| inclusion | status | evidence |
|---|---|---|
| `SDEP_site ⊆ SDEP_patterns` | **strict, SMT-verified** | 7 separators, e.g. `r&1&2`, `r*2−r−r`, `(r+1)(r−1)−r²+1` — QF_BV proves constant, idiom matcher says REAL (`p7_hierarchy.py`) |
| `SDEP_subst ⊆ SDEP_site` | **strict, SMT-verified** | 49 separators (§4), two independent causes |
| `SDEP_true ⊆ SDEP_subst` | **equality on loop-free, alias-resolved, guard-exposed blocks — by construction**; strict otherwise | the transfer function *is* the block semantics; strictness comes from loops, may-alias, and hidden guards (§7) |
| `SDEP_true ⊆ SDEP_site` (soundness) | **empirically observed, not proved** | two searches, **0** cases of `SITE=FAKE ∧ SUBST=REAL`: `p7` 400 blocks / 565 checks, and adversarial `p9` 1200 blocks / 2193 checks. Consistent with the codebase's documented `sdep_true ⊆ sdep_impl` |

**Strongest criterion that is decidable, QF_BV-encodable and practical: `SDEP_subst`** —
per basic block, loop-free, two-copy product query.

---

## 6. Complexity and SMT encodability

| | shipped | candidate |
|---|---|---|
| logic | QF_BV | QF_BV |
| copies | 1 site expression | 2 copies of one basic block |
| complexity | NP-complete | NP-complete (**same class**) |
| quantifiers | none | none |
| preprocessing | none | **none required** |

**The encoding choice is the whole practical story.** Measured on `dag-n`
(`t₁:=x+x; t_i:=t_{i-1}+t_{i-1}; store t_n−t_n`, each temporary used twice):

| n | textual substitution (tokens) | self-composition (assertions) |
|---:|---:|---:|
| 4 | 123 | 11 |
| 8 | 2,043 | 19 |
| 12 | 32,763 | 27 |
| 16 | **524,283** | **35** |

Textual unfolding is `Θ(2ⁿ)`; self-composition is `2n+3`. Both decide the identical
predicate — verified to agree on 60/60 random blocks (`p6_smt_cost.py`).

> **Design consequence.** A literal "add a normalization pass, then re-run the existing
> oracle" implementation is exponential in the worst case, and the worst case is exactly
> the repeated-temporary DAG that register allocation produces. The correct
> implementation asserts the block's definitional equalities twice and never substitutes.
> If an explicit substituted term is wanted anyway, it must be emitted as a `let`-bound
> DAG, which is `O(block size)`.

Compatibility with the existing architecture: the query is posed to the same
`SolverContext`, uses the same `buildTerm` evaluator, and is memoizable per
(block, input) just as the current oracle memoizes per (expr, reg).

---

## 7. Boundaries — what the criterion still cannot do

| feature | status | evidence |
|---|---|---|
| **multiple temporaries** | handled exactly | 7 separators recovered incl. inclusion–exclusion `(x&y)+(x\|y) = x+y` |
| **bitwise / masking** | handled exactly | disjoint-mask, mask-narrowing separators |
| **multiplication / overflow** | handled exactly; **fixed width is the *source* of case (b)** | over ℤ, `2x` is injective and `t&1` would be a real dependency; mod 2³² it is 2-to-1 and `t&1 ≡ 0` |
| **shifts** | **outside the shipped grammar** — oracle returns `null`, detector falls back to conservative REAL | 3 separators the candidate sees and the shipped tool cannot (`t:=x<<1; store t&1`) |
| **branching** | exact **iff the guard is exposed as a term** | with `ite`, LBfc (`if(x==1) y=1 else y=1`) is correctly FAKE and LBcd correctly REAL, 0/3 separators. The current C front end *flattens* if/else before any criterion sees it — a front-end gap, not a criterion gap |
| **loops** | **outside any quantifier-free criterion** | unrolling to depth `k` decides the `k`-th iterate, not the loop: with trip count 3, unrolling `k=1,2,4` says REAL and only `k=3` says FAKE. Symbolic trip counts admit no finite `k`; constancy of a loop transfer function is undecidable in general (standard reduction — *asserted, not probed*) |
| **aliasing** | sound but incomplete without an alias oracle | must-alias `m:=x; t:=m; store t−x` → FAKE (recovered); may-alias → REAL (conservative, correct) |

---

## 8. Impossibility results

> **Theorem (site-locality is insufficient).** There is no function `D(e, r)` of the store
> expression and register alone that equals program-level dependency.

**Proof.** Take `e = t − x`, `r = x`.
- Environment A: `t := x+1`. Stored value ≡ 1, constant. True answer **FAKE**.
- Environment B: `t := 2x`. Stored value = `x`. True answer **REAL**.

`D(t−x, x)` would have to be both. ∎  (SMT-verified, `p4_maximal_impossible.py`.)

**Corollary.** The shipped detector's shortfall is *not* a precision problem. Replacing its
decision procedure with a stronger one — including a complete QF_BV oracle, which it
already has — cannot close the gap. Confirmed directly against the repo's own
`QfbvConstancyOracle`, which correctly answers "VARIES" for `W0-W1`: over two free
variables it genuinely does vary. Environment information is **necessary**.

> **Theorem (no bounded prefix suffices).** For every `d` there are two blocks agreeing on
> the store expression *and* on the first `d` definitions, whose true answers differ.

**Construction.** Shared prefix `a₁ := x+1, …, a_d := a_{d−1}+1`; then `h := a_d + 1`
(cancels) versus `h := a_d + a_d` (does not); store `h − x − (d+1)` in both. Verified for
`d = 1,2,3`; the construction is uniform in `d`. Hence any criterion inspecting a bounded
amount of the environment is also insufficient — the **transitive closure** is required,
which is exactly the information content of full substitution. This is the minimality
argument of Task 2: substitution is minimal because anything weaker inspects either no
environment (impossibility 1) or a bounded prefix of it (impossibility 2).

---

## 9. Epistemic status of every claim

**Mathematically proved (pen-and-paper, in this document)**
- Theorem C2 (exactness of the shipped criterion on input-separated, range-full environments), §2.
- Theorem (uniform separator): every environment with proper joint image `G ⊊ BV_w^k` and a
  non-degenerate `ρ` admits a separator built from `==` and `&` alone, §2. This proves
  maximality of C2 for **both** the range and the injectivity conditions.
- Impossibility of any site-local criterion, §8.
- No-bounded-prefix impossibility (construction uniform in `d`), §8.
- `SDEP_subst = SDEP_true` on loop-free, alias-resolved, guard-exposed blocks (by construction).

**SMT-verified (each an individual Z3 SAT/UNSAT result)**
- All 49 separators in `eval/research/separators.csv`.
- The 7 pattern-vs-QF_BV separators establishing strictness of the lowest inclusion.
- The depth staircase for `chain-1..6` (FAKE first at `k = n`, for `n = 1..6`).
- Refutation of the surjectivity conjecture C1.
- The uniform point-indicator construction, 6/6, including non-invertible `ρ₁ = x·x`.
- Agreement of textual substitution and self-composition on 60/60 random blocks.

**Experimentally observed (randomized, not exhaustive — could be falsified by a wider search)**
- C2 survived 250 + 1200 random conforming blocks (491 + 2991 checks), 0 counterexamples;
  the second search was built specifically to break it.
- Soundness (`SITE = FAKE ∧ SUBST = REAL` never occurs): 400 + 1200 blocks, 565 + 2193
  checks, 0 violations.

**Conjectured (one item, precisely delimited)**
- The *surjective-yet-correlated* case: whenever the environment map is a
  grammar-expressible bijection of `BV_w^m`, a complementary coordinate is also
  grammar-expressible, hence a fibre-constant separator exists. Verified 3/3 by hand for
  invertible linear changes of coordinates. This is now the **only** gap between "C2 is a
  tight sufficient condition" and "C2 is the maximal fragment".

**Asserted from standard results, not established here**
- Undecidability of loop-transfer-function constancy (reduction to reachability).
- Self-composition as the standard decision method for 2-safety (Barthe, D'Argenio & Rezk,
  CSFW 2004) — this is the reason I do **not** claim the core technique as novel.

**Mechanized (Isabelle)** — nothing new. The existing program-level PwT non-correspondence
result was taken as a premise and no theory file was touched. Note that the two published
refuting witnesses (`t := x+1; y := t−x` and `t := x&1; y := t&2`) are exactly one instance
of mechanism (a) and one of mechanism (b), which is corroborating rather than coincidental.

---

## 10. Recommendation

If the goal is a **publishable theoretical contribution**: the honest verdict is that the
core criterion is a known technique applied correctly, and I would not build a paper claim
on it. The defensible contributions are the *characterization* results — fragment C2, the
two impossibility theorems, the demonstration that no syntactic fragment exists, and the
exponential-vs-linear encoding result. That is a good §-sized contribution, not a paper.

If the goal is to **decide whether to implement**: the investigation says yes, and says
precisely how — as a two-copy product query over existing definitional equalities, per
loop-free basic block, with shifts added to the grammar and guards exposed by the front
end. It says explicitly *not* to implement it as a normalization pass.

### Artifacts

| path | contents |
|---|---|
| `eval/research/depprobe.py` | harness: expression grammar, block model, both criteria |
| `eval/research/p1_characterize.py` | Task 1 — two independent causes isolated |
| `eval/research/p2_minimal.py` | Task 2 — transformation classes refuted |
| `eval/research/p2b_depth.py` | Task 2 — depth staircase (corrected) |
| `eval/research/p3_fragment.py` | Task 4 — C1 refuted, C2 found and tightened |
| `eval/research/p4_maximal_impossible.py` | Tasks 4/5 — randomized C2, maximality, impossibility |
| `eval/research/p5_boundaries.py` | Task 4 — branching, loops, aliasing, shifts, overflow |
| `eval/research/p6_smt_cost.py` | Task 7 — exponential vs linear encoding |
| `eval/research/p7_hierarchy.py` | Task 6 — hierarchy + soundness direction |
| `eval/research/p8_catalog.py` | Task 8 — consolidated catalogue → `separators.csv` |
| `eval/research/p9_falsify.py` | adversarial attempt to break Theorem C2 and soundness |
| `eval/research/p10_maximality.py` | the uniform point-indicator construction; residual open case |
| `eval/research/separators.csv` | all 49 separators, machine-readable |

Requires `z3-solver` (installed into the user Python environment during this session).
