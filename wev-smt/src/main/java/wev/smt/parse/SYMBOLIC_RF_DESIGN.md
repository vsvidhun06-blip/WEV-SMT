# Symbolic rf + co enumeration — design (C1-1)

**Status: design only. No encoder is implemented yet.** This document specifies the design
and, critically, corrects an architectural assumption in the C1 brief that would make the
feature a no-op. Placed in `parse/` (not the non-existent `core/`) because — as established
below and in `eval/rf-enumeration-analysis.md` §1 — the enabling work is in the *parser*,
not `cli/`.

Fatal flaws targeted: **F2** (§3.6 claims co is a decision variable, §6.1 says the loader
fixes rf+co — contradiction), **M5** (single-wiring "definitive for this wiring" caveat
poisons every agreement %). C1 makes verdicts **program-level** (UNSAT over *all* rf/co) so
both dissolve.

## 1. Current flow (wired)

```
LitmusParser ──▶ LitmusCase ──▶ EventStructureEncoder ──▶ AxiomaticConsistency
  (parse)          (fixed         (rf/co as Boolean          (axioms over
                    rf + co         facts, one wiring)         those facts)
                    wiring)
```

- `LitmusParser.wireReadsFrom()` pins each read to **one** write (value-matched to the
  `exists` clause); `wireCoherence()` fixes **one** `co` order (init, then program writes in
  textual order).
- **`LitmusParser.evalConst` (LitmusParser.java:~1336) folds every write value to a
  constant at parse time**, substituting placeholder `1` for a data-dependent value. So
  `WRITE_ONCE(*y, r0+1)` becomes `WriteEvent(y, 1)` — the *function* `r0+1` is discarded.
- `LitmusCase` carries the `EventStructure`, a `DependencyInfo` sidecar (records *that* a
  write depends on a read — **not the function**), and `existsClause` as raw text.

## 2. Why the naive C1 architecture (encoder on a `LitmusCase`) cannot work

The C1 brief proposes a `SymbolicRfEncoder` that *takes a `LitmusCase`* and adds rf
decision variables plus `val_read[r] = val_write[w]` constraints. **That is insufficient
for the divergences it targets (TC06, TC18).** Those need `val_write` to be the *real
function* of the read values, e.g. `val_write[Wy] = val_read[Rx] + 1`. But `r0+1` was
erased by `evalConst` before the `LitmusCase` existed — `DependencyInfo` records only the
edge `Wy ← Rx`, not `+1`. An encoder over the parsed case would permute rf over writes whose
values are already constants and **change essentially no verdict** (analysis §1). TC06 is
the counterexample: locally `y=r0+1` *can* be `1` (when `r0=0`), so a per-write filter
admits `y=1` and wrongly returns ALLOWED; the real answer is FORBIDDEN for a **global**
reason — `exists` pins `1:r0=1`, forcing that same write to `y=2`.

**Correct enumeration is a joint fixpoint**, not a product-with-filter: an rf assignment
fixes every register value ⇒ every dependent write value ⇒ which must agree with the value
each read is claimed to observe *and* satisfy `exists`.

## 3. New flow (symbolic) — corrected architecture

```
LitmusParser ──▶ LitmusCase (+ ValueModel sidecar) ──▶ SymbolicRfEncoder ──▶ AxiomaticConsistency
  (parse,          events, po, dep, sdep, location      (rf/co/value           (axiom encoding
   ADDITIVE          map, AND a per-write value           decision vars +         UNCHANGED)
   sidecar)          expression + read bindings)          constraints)
```

Two parser-level additions (both **additive**; existing folding untouched so every current
verdict is bit-identical by construction):

1. **`ValueModel` sidecar on `LitmusCase`.** Per `WriteEvent`, retain the source value
   expression string and the `register → producing-ReadEvent` binding already present in
   `RegSrc` during parsing. This is the erased information, kept alongside (not replacing)
   the folded constant.
2. **Structured `exists`.** Parse `existsClause` into `(thread, reg, value)` triples so the
   encoder can pin register (hence read) values, instead of re-parsing raw text.

`AxiomaticConsistency`'s axiom encoding is **unchanged**; it consumes whatever rf/co facts
are asserted — fixed (wired) or symbolic.

## 4. New SMT variables

Over the existing JavaSMT context (bitvector width W=32, matching `QfbvConstancyOracle`):

| variable | sort | meaning |
|---|---|---|
| `rf_active[w][r]` | Bool | read `r` reads from write `w` (both same location) |
| `val_read[r]` | BV32 | value observed by read `r` |
| `val_write[w]` | BV32 | value stored by write `w` — **a function of `val_read` via the retained expression**, not a constant |
| `co[w1][w2]` | Bool | coherence order — **already a decision variable in `AxiomaticConsistency`** (`addCo`/`getCoVars`); no change |

`val_write[w]` is built by evaluating the `ValueModel` expression for `w` over `val_read` of
its producing reads, reusing the `QfbvConstancyOracle` BV AST/evaluator (`eval(Node, env)`)
with `env[reg] = val_read[producing-read]`. Constant stores collapse to a literal (identical
to today).

## 5. New constraints

For the active-event set `active[e]` (respecting the existing gated encoding):

1. **Exactly-one rf source.** For each active read `r`:
   `exactly-one { rf_active[w][r] : w a write, same_location(w,r), active[w] }`.
   Encoding: pairwise at-most-one (`¬(rf_active[w1][r] ∧ rf_active[w2][r])` for w1≠w2) **+**
   at-least-one (`⋁ rf_active[w][r]`). Small fan-in (≤ writes-per-location), so the O(k²)
   pairwise AM1 is fine and needs no auxiliary vars; documented choice.
2. **Value coupling.** `rf_active[w][r] → val_read[r] = val_write[w]` (Z3 `mkEq` on BV32).
3. **Value definition.** `val_write[w] = eval(expr_w, val_read∘producers)` for every write
   (init writes: `val_write = 0`, or the `{x=n}` initial value).
4. **Init writes.** Each location `x` has one always-active init write `W(x, init_x)`, always
   an eligible rf source.
5. **Activity gating.** `rf_active[w][r] → active[w] ∧ active[r]`.
6. **Exists.** For each `(thread,reg,value)` triple: the read backing `thread:reg` has
   `val_read = value` (register-to-read binding from the parser).

`same_location(w,r)`: syntactically equal constant address, or the same symbolic address
variable. For the branch-free data/address fragment addresses are ground after QF_BV
evaluation, so `QfbvConstancyOracle` resolves them to concrete locations.

## 6. Invariants preserved

- **sdep unchanged.** Semantic dependencies are static per-site (the fake/real detector,
  now Layer-1-exact after C3); they do not interact with symbolic rf. `jfCoherence` consumes
  the same `sdep` set.
- **jf = rf.** `jf_active[w][r] ≡ rf_active[w][r]` (the existing `AxiomaticConsistency`
  identity `jfVars ≡ rfVars`); the no-thin-air axiom `acyclic(sdep ∪ jf)` is unchanged.
- **co already symbolic.** No change to `AxiomaticConsistency` co handling (this is exactly
  the F2 point — the axiom layer already treats co as a decision variable; only the *loader*
  fixed it).

## 7. Verdict semantics v2 (program-level)

- **FORBIDDEN** iff `wellFormed ∧ consistency(M) ∧ (symbolic rf/co/value/exists constraints)`
  is **UNSAT** — no rf assignment, co order, or value fixpoint realises the `exists` outcome
  consistently.
- **ALLOWED** iff SAT.

Both are now **program-level**, not wiring-level: the M5 caveat ("FORBIDDEN definitive only
for this wiring") is deleted, and F2 is resolved (rf and co are both decision variables end
to end). The **zero-survivors** case is a first-class FORBIDDEN — exactly TC06/TC18
(value-unreachable `exists`).

## 8. Backward compatibility

`AxiomaticConsistency` gains a `boolean symbolicRf` field (default **false**). `--mode=wired`
(default) keeps the fixed-rf facts from the loader — byte-for-byte current behaviour, used
for the matched-pair exhibit and per-candidate timing. `--mode=symbolic` asserts §5 instead.
A `SymbolicRfRun` in `cli/` (or a `--mode` flag on `WevBatch`) selects it.

## 9. Projected results (hand-derived; must be replaced by a real run)

From `eval/rf-enumeration-analysis.md` §3, §8 (every figure hand-derived, **none executed**):

| figure | wired (now) | symbolic (projected) |
|---|---|---|
| Dat3M agreement | 31/41 | **32/41** (TC06 flips FORBIDDEN & agrees; TC18 already corrected; TC16 stays ALLOWED) |
| JMM agreement | 17/20 (corrected, see C2) | **19/20** (TC06+TC18 flip to FORBIDDEN & agree) |
| Fake/real separation (6 `semantic_vs_syntactic` tests) | ALLOWED | **ALLOWED (unchanged)** — value-reachability re-derives the fake/real split without the detector (analysis §5) |

The six `semantic_vs_syntactic_dep` divergences **must stay ALLOWED** — their dependent
stores are constant functions of the read (`r+1-r ≡ 1`, `r^r^1 ≡ 1`), so a grounded fixpoint
exists. Forcing them FORBIDDEN would be unsound and would destroy the paper's contribution.

## 10. Mandatory validation gate

Because step-1 touches the parser every committed result depends on, the implementation is
**not complete** until the 2,972-file corpus (C3-corrected manifest) re-runs with **zero
verdict movement** in `--mode=wired`, plus the ladder in the C1 brief (LB-real FORBIDDEN,
LB-fake/LBfd/DETOUR0236 ALLOWED, TC06/TC18 FORBIDDEN, TC16 ALLOWED, CoWW/CoWR FORBIDDEN,
40-test atlas, full corpus). Every verdict in §9 is a projection until then.

## 11. Effort estimate / status

Steps 1–2 (parser `ValueModel` sidecar + structured `exists`) are the substantive work and
are **not** implemented. Step 3 (`SymbolicRfEncoder`), the `--mode` wiring, and the
validation ladder follow. This is a multi-day implementation with a non-negotiable
full-corpus regression gate; it is scoped here but deliberately **not** stubbed, because a
`cli/`-only encoder over the current `LitmusCase` would produce near-zero verdict changes
and close no divergence (§2) — a misleading artifact worse than an honest "designed, not
built".
