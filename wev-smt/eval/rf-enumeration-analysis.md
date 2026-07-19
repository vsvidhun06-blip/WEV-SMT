# rf enumeration — feasibility analysis and projected outcome

**Status: analysis only. No code was written and nothing was executed.** Every verdict
below is a hand-derived value-reachability projection, not a measurement. Named
`-analysis.md` rather than `-summary.md` for that reason: there is no run to summarise.
`eval/rf-enumeration-results.csv` deliberately does not exist yet.

## Bottom line

1. **rf enumeration cannot be built in `cli/` alone.** The value abstraction it is meant
   to defeat is applied *at parse time*, and the information needed to undo it is
   discarded before any `LitmusCase` exists. See §1.
2. **The projected outcome is 33/41, not 34/41.** TC06 and TC18 flip to FORBIDDEN and
   start agreeing with Dat3M; **TC16 correctly stays ALLOWED** and keeps disagreeing.
   See §3.
3. **TC18's flip is not a win — it exposes an encoding bug.** TC18 is JMM=ALLOWED and
   currently matches; correct enumeration would break that match. Its `exists` is
   value-unreachable as encoded. See §4.
4. **The paper's central claim survives enumeration, and is strengthened by it.** All six
   `semantic_vs_syntactic_dep` divergences stay ALLOWED under full value propagation,
   for a principled reason. See §5.

## 1. Why this cannot live in `cli/`

`LitmusParser.evalConst` (`src/main/java/wev/smt/parse/LitmusParser.java:1336`) folds
every write value to a constant at parse time, substituting a placeholder when the
expression is data-dependent:

```java
private int evalConst(String expr, Map<String, RegSrc> env) {
    Integer v = tryEval(expr, env);
    return v == null ? 1 : v;     // data-dependent writes: a deterministic placeholder
}
```

So TC06's `WRITE_ONCE(*y, r0 + 1)` becomes `WriteEvent(y, 1)` — a constant — before
anything downstream sees it. `LitmusCase` carries the wired `EventStructure`, a
`DependencyInfo` sidecar, and `existsClause` as *raw text*. `DependencyInfo.DepEdge`
records **that** a write's value depends on a read; it does not record the **function**
(`r0 + 1`). That function is never stored anywhere.

An enumerator operating on a parsed `LitmusCase` would therefore permute rf edges over
writes whose values are already constants. It could not compute that `y = 2` when
`r0 = 1`, because `y`'s dependence on `r0` has been erased. **It would produce a CSV of
near-zero verdict changes and would not close a single Dat3M divergence.**

## 2. What correct rf enumeration actually requires

The task described a Cartesian product over per-read choices, filtered by a *local*
validity test ("program writes valid if their expression can evaluate to the read
value"). That local test is not sufficient, and TC06 is the counterexample.

TC06 asks whether `y = 1` is available. Locally, `y = r0 + 1` *can* evaluate to 1 — when
`r0 = 0`. A per-write filter therefore admits `y = 1` and returns ALLOWED, reproducing
today's wrong answer. The reason the real answer is FORBIDDEN is **global**: the `exists`
clause pins `1:r0 = 1`, so in any candidate satisfying `exists`, that same write is
`y = 2`, not `y = 1`.

Correct enumeration is a **joint fixpoint**, not a product-with-filter:

> Choose an rf assignment (each read → some write of its location). The assignment
> determines every register value; register values determine every dependent write's
> value; those write values must then agree with the values the assignment claims each
> read observed, *and* satisfy the `exists` clause.

For acyclic value-dependency this is one evaluation pass. For cyclic (load-buffering)
shapes it is a genuine fixpoint question — which is precisely the thin-air problem, so
it must be posed carefully rather than iterated to convergence and hoped for.

Note also the monotonicity trap: "does **any** consistent candidate exist" can only turn
FORBIDDEN into ALLOWED relative to a single candidate drawn from the same set. TC06 flips
the *other* way only because the enumerated set is not a superset of today's candidate —
today's wired candidate is **invalid** (it has a read observing a value no write produces
under the pinned `exists`) and correct enumeration excludes it. Enumeration here is as
much a *validity filter* on the current candidate as a widening of the search.

## 3. Projected effect on the 41-test Dat3M comparison

Current: **31/41 agree**, 10 disagreements (`eval/dat3m-comparison.csv`).

| # | test | WEV now | Dat3M | class | projected under rf enum | agrees after? |
|---|---|---|---|---|---|---|
| 1 | `paper/LB-fake-xor-cycle` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 2 | `paper/LB-fake-xor` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 3 | `paper/LBfd` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 4 | `paper/MP+dmb.sy+addr` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 5 | `leaky-semicolon/LBfd` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 6 | `leaky-semicolon/LBxor` | ALLOWED | FORBIDDEN | semantic_vs_syntactic | **ALLOWED** (unchanged) | no — by design (§5) |
| 7 | `jmm/TC06` | ALLOWED | FORBIDDEN | value_abstraction | **FORBIDDEN** | **yes (+1)** |
| 8 | `jmm/TC16` | ALLOWED | FORBIDDEN | value_abstraction | **ALLOWED** (unchanged) | no — §6 |
| 9 | `jmm/TC18` | ALLOWED | FORBIDDEN | value_abstraction | **FORBIDDEN** | **yes (+1)**, but §4 |
| 10 | `jmm/TC13` | FORBIDDEN | ALLOWED | fixed_co_order | **FORBIDDEN** (unchanged) | no — §7 |

**Projected: 33/41 (80.5 %)**, up from 31/41 (75.6 %). Not 34/41.

### Regression check on currently-agreeing tests

Stricter value-reachability could in principle break tests that agree today. Hand-checked
every currently-agreeing `WEV=ALLOWED` jmm test; all survive:

| test | why it survives |
|---|---|
| TC01 | both stores are unconditional constants (`x=1`, `y=1`) |
| TC11 | init is `{x=1; y=1}` — the fixpoint is grounded by the initial writes, not circular |
| TC12 | P1's second store `WRITE_ONCE(*y, 1)` is unconditional and supplies `y=1` |
| TC15 | P0's `x=1` is unconditional and the chain propagates it |
| TC19 | control-dependent but both stores are constant `1` |

The general principle: **value-reachability only bites when the required value is
producible *solely* by a data-dependent write whose own input the `exists` clause pins to
a contradicting value.** TC06 and TC18 are exactly that shape. Everything else escapes it
via an unconditional write or a non-zero initial state.

## 4. TC18 is an encoding bug, not a result

TC18 is **JMM=ALLOWED** and WEV currently **matches** (`eval/jmm-results.csv`, `match=yes`).
Correct enumeration would make it FORBIDDEN — agreeing with Dat3M while *breaking* the
JMM match, which is the stronger ground truth for this set.

Tracing the encoding:

```
P0: r0 = READ_ONCE(*x); WRITE_ONCE(*y, r0 + 1);
P1: r0 = READ_ONCE(*y); WRITE_ONCE(*x, 1); WRITE_ONCE(*z, r0 + 1);
P2: r0 = READ_ONCE(*z); WRITE_ONCE(*x, r0 + 1);
exists (0:r0=1 /\ 1:r0=1 /\ 2:r0=1)
```

`1:r0 = 1` requires reading `y = 1`. The only writer of `y` is P0's `y = r0 + 1`, and
`0:r0 = 1` is also pinned by the `exists`, forcing `y = 2`. With init `y = 0`, the reachable
set is `y ∈ {0, 2}` — **`y = 1` is unreachable, so the `exists` clause is unsatisfiable as
written.**

The file's own header says: *"JMM DECISION: ALLOWED. Encoded with the r+1 idiom on the
dependent stores and the explicit x=1."* The `r+1` idiom is what introduces the
unreachability. Pugh's original TC18 turns on P1's unconditional `x=1` supplying a
non-dependent justification — a structure the `+1` value skew defeats.

**Recommendation: treat `eval/examples/jmm/TC18.litmus` as an unfaithful encoding and
re-encode it before running enumeration.** Otherwise TC18 will silently convert a real
JMM agreement into an artifact of the encoding, and the +1 it contributes to the Dat3M
count is not a genuine improvement. If TC18 is re-encoded faithfully, the projected Dat3M
figure is **32/41**, with TC06 the only honest gain.

## 5. The paper's claim survives — and gets stronger

All six `semantic_vs_syntactic_dep` divergences stay ALLOWED, and not by luck. Their
"dependent" stores are **constant functions of the read value**:

```
LBfd    : WRITE_ONCE(*y, r0 + 1 - r0)     ≡ 1  for every r0
LBxor   : WRITE_ONCE(*y, r0 ^ r0 ^ 1)     ≡ 1  for every r0
```

Because the write value does not vary with the read, the fixpoint of §2 has a solution in
which **no write's value depends on any read** — the load-buffering cycle is grounded, and
a consistent candidate exists. Contrast `LB-real` (`y = r0 + 1`, a genuine function): there
`0:r0 = 1` requires `x = 1`, i.e. `r1 = 0`, contradicting the pinned `1:r1 = 1`, so no
consistent candidate exists and it is FORBIDDEN — as WEV already reports.

This is worth stating in the paper: **rf enumeration does not weaken the fake/real
separation, it re-derives it from value reachability alone**, without appealing to the
dependency detector. That is a strictly stronger argument than the current one, and it is
the most valuable thing enumeration would buy — more than the +1 or +2 on the Dat3M count.
Dat3M/LKMM continues to forbid these because LKMM uses *syntactic* dependencies; that
divergence is the contribution, not a defect, and it must not be "fixed".

## 6. TC16 correction

The task predicted TC16 → FORBIDDEN on the grounds that "x is only written 0 or 1". That
holds for TC06 but **not** for TC16, whose P2 writes `x = 2` unconditionally:

```
P2(int *x, int *z) { r0 = READ_ONCE(*z); WRITE_ONCE(*x, r0); WRITE_ONCE(*x, 2); }
exists (0:r0=2 /\ 1:r0=2 /\ 2:r0=2)
```

A fully value-consistent witness exists: P0 reads `x = 2` from P2's unconditional store →
writes `y = 2`; P1 reads `y = 2` → writes `z = 2`; P2 reads `z = 2` → writes `x = 2`. Every
read observes a value some write genuinely produces, and the `exists` is satisfied.

TC16 is **JMM=ALLOWED** and WEV matches today. Correct enumeration keeps it ALLOWED and it
keeps disagreeing with Dat3M. Forcing it to FORBIDDEN to reach 34/41 would require
deliberately unsound semantics and would break a correct JMM agreement.

## 7. TC13 needs co enumeration, not rf

TC13 (`WEV=FORBIDDEN`, `Dat3M=ALLOWED`) is the one divergence in the opposite direction,
and rf enumeration does not touch it:

```
P0: r0 = READ_ONCE(*y); r1 = READ_ONCE(*y); WRITE_ONCE(*x, r0 + 1);
P1: WRITE_ONCE(*y, 1);   P2: WRITE_ONCE(*y, 2);
exists (0:r0=2 /\ 0:r1=1)
```

Both values are written, so a valid **rf** assignment exists. Reading 2 then 1 on one
thread requires the coherence order `y=2 →co y=1`, which is legal — but WEV fixes a single
`co` at load time and the one it picks makes the candidate inconsistent. Fixing TC13 needs
**co** enumeration, a separate axis already begun in `wev.smt.cli.CoEnumeration` /
`eval/co-enumeration-results.csv` (currently 4 tests, none of them TC13).

## 8. Effect on the paper's other numbers

| figure | now | after rf enum (TC18 as-is) | after rf enum (TC18 re-encoded) |
|---|---|---|---|
| Dat3M agreement | 31/41 | 33/41 | 32/41 |
| JMM agreement (`jmm-results.csv`) | 18/20 | 18/20 — *different members* (TC06 +1, TC18 −1) | 19/20 |
| Published-example total (§C2) | 31/34 | 31/34 — composition shifts | 32/34 |
| 2 discriminating corpus tests | 2 | 2 (unaffected) | 2 (unaffected) |

The JMM row is the one to watch: leaving TC18 as-is holds the headline at 18/20 while
silently swapping a genuine agreement for an encoding artifact. Re-encoding TC18 is the
only path where every number moves in the right direction for the right reason.

## 9. If we proceed: implementation sketch

Not built. Recorded so the scope is visible before anyone commits to it.

1. **Parser (additive).** Retain, per `WriteEvent`, the source expression string and the
   register→producing-read binding already available in `RegSrc` during parsing. Add as a
   new sidecar on `LitmusCase`; **do not** change existing folding, so all current verdicts
   are bit-identical by construction.
2. **Exists clause (structured).** Parse `existsClause` into `(thread, reg, value)` triples
   instead of keeping raw text, so enumeration can pin register values.
3. **Enumerator.** Per read, candidate writers = same-location writes (init always
   included). Cartesian product, bounded by the stated scope (≤12 events, ≤3 writes per
   location per thread). For each assignment: propagate values through the expression
   sidecar, reject if any read's observed value ≠ its writer's computed value or the
   `exists` triples are violated, then run the existing WEAKEST check on survivors.
   ALLOWED iff any survivor is consistent; FORBIDDEN if none — **including the
   zero-survivors case**, which is exactly TC06.
4. **Validation gate.** Re-run the 2998-file corpus (§C1 of `README-artifact.md`) and
   confirm zero verdict movement, since step 1 touches the parser that every existing
   result depends on.

Steps 1–2 are the real work and both sit in `parse/`, not `cli/`. Step 4 is
non-negotiable given how much committed data depends on the parser.

## 10. Open items

- [ ] Decide whether to re-encode `TC18.litmus` (§4) — needs Pugh's original definition.
- [ ] Confirm the projections in §3 empirically once an enumerator exists; every verdict
      in this document is hand-derived and none has been executed.
- [ ] TC13 (§7) needs co enumeration; extending `CoEnumeration` past its current 4 tests
      is a separate task.
- [ ] `eval/co-enumeration-results.csv` holds only 4 rows and no `.md` summary; if co
      enumeration is picked up, it needs the same claim-to-command treatment as everything
      else in `README-artifact.md`.
