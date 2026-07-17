# QF_BV constancy oracle — detector re-run

Re-ran the whole corpus through `LitmusParser` twice: the solver-free detector (default) vs. the exact `QfbvConstancyOracle` installed as the primary decision procedure (`LitmusParser.CONSTANCY_ORACLE`). Full data: `qfbv-detector-results.csv`.

## New detector architecture (`LitmusParser.classify`)

1. **Idiom fast-path (O(1)).** The 8 self-cancelling patterns (`r^r`, `r-r`, `r&~r`, `~r&r`, `r*0`, `0*r`, `r&0`, `0&r`, plus `r|~r`/`~r|r`); if folding removes `reg`, return **fake** immediately.
2. **QF_BV constancy oracle (primary).** Otherwise, if `expr` parses as 32-bit bitvector arithmetic, ask Z3 `∃ r1,r2. e[reg:=r1] ≠ e[reg:=r2]` (other regs shared/free): **SAT ⇒ real**, **UNSAT ⇒ fake**.
3. **Conservative.** If `expr` is outside that grammar, classify as **semantic** (unchanged from before).

The oracle subsumes the old linear-cancellation heuristic (it also proves non-linear invariance), so on the branch-free data/address fragment the implementation is now **exact**: `sdep_impl = sdep_true`. With no oracle installed the parser keeps its solver-free fallback, so every other tool's classification is byte-for-byte unchanged.

## Corpus

| | |
|---|---|
| Manifest | `eval\corpus-weakest-manifest.csv` |
| Corpus root | `eval\corpus` |
| Files parsed (rows) | 2998 |
| Files skipped / parse-error | 0 / 0 |

## Result: how many verdicts changed?

| | |
|---|---|
| **Tests whose classification changed** | **0 / 2998** |
| Total semantic edges, old detector | 20 |
| Total semantic edges, new detector | 20 |
| Net edges demoted semantic→fake by the oracle | 0 |

**0 changes.** The exact oracle demotes no edge the solver-free detector kept semantic, and promotes none it dropped — the two agree on every test. What was *empirically* validated (the heuristic missed no fake) is now **provably exact** on the modelled fragment: the oracle is the ground truth and it confirms `sdep_impl = sdep_true`.

## Which detection path was used

| method | tests | meaning |
|---|---|---|
| `pattern` | 68 | all decisions resolved by the O(1) idiom fast-path |
| `qfbv` | 785 | the Z3 oracle decided ≥1 (real or fake) |
| `conservative` | 101 | ≥1 expression outside the BV grammar, left semantic |
| `none` | 2044 | no dependency decisions |

## Performance impact

| | |
|---|---|
| Oracle queries (`isConstant` calls) | 2865 |
| Distinct `(expr,reg)` (memoized) | 86 |
| **Actual Z3 solves** (cache misses that parsed) | **74** |
| Undecided (unparseable ⇒ conservative) | 12 |
| Wall-clock, both passes over the corpus | 4.2 s |

Memoization collapses the 2865 oracle calls to 74 real Z3 queries (one per distinct expression), so making the detector exact adds negligible cost to a full-corpus parse — the fast-path handles the common idioms and the solver is consulted only for the handful of distinct value expressions the corpus actually contains.

_No `.tex` files were modified; nothing was committed._
