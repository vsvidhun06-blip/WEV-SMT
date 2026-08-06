# Workstream C3 — Layer-2 detector audit

**Fatal flaw addressed:** M8 — "12 Layer-2 sites contradict the all-QF_BV grammar claim."

## What Layer 2 is

The dependency detector classifies each value/address expression as a *real* (value-carrying)
or *fake* (identity/constant) dependency. Its primary decision procedure is the exact
**QF_BV constancy oracle** (`QfbvConstancyOracle`, "Layer 1"): it parses the expression as
32-bit bitvector arithmetic and asks Z3 whether it can vary in the register. When the
expression does **not** parse in that grammar, the oracle returns `null` and the detector
falls back to **Layer 2** — a conservative *always-semantic* classification (decision pattern
`CONSERVATIVE`). The paper's claim that all 2,998 supported-syntax tests are decided by Layer 1
is what the 12 Layer-2 routings contradicted.

## Method

`Layer2AuditRun` (new) re-parses the whole 2,998-test manifest with the oracle installed and
`LitmusParser.DEP_TRACE` capturing every classification, recording each `CONSERVATIVE` decision
as a site `(test, thread, instr, expression, reason)` in **`eval/layer2-audit.csv`**. The
oracle was instrumented (`QfbvConstancyOracle.undecidedReasons()`) to record *why* each distinct
expression left Layer 1. `LitmusParser.DepDecision` was extended with the WEV thread id and the
in-thread instruction index so each site is pinned exactly.

## Findings (before fix)

Baseline run: **12 distinct expressions**, **106 sites**, **101 tests** routed to Layer 2. They
split cleanly into two categories — **none are RMW value expressions**:

### Category A — relational control/data predicates (1 expression, 79 sites, 75 tests)

Every one is the C idiom **`r1 = (r0 != 0)`** in the Dat3M `LKMM/luc/RelAcq/C-*ctrl*` tests.
The BV grammar covers `+ - * ^ & | ~` but **no relational operators**, so `BvParser` throws at
`!` (`reason = "parse: expected )"`). This is *not* a bug or a parser artifact: the expression
is a genuine value-carrying dependency (`r1` really does vary with `r0`), and the conservative
"semantic" verdict is **sound**. It is a **grammar-coverage gap**.

**Outcome: FIXED.** The QF_BV grammar was extended with relational operators (`== != < <= > >=`),
evaluated as a C `int` 0/1 result (`bmgr.ifThenElse` over the bitvector comparison). The
relational level is the outermost (lowest) precedence and only activates on expressions that
previously failed to parse, so every pure-arithmetic expression parses byte-for-byte as before.

Verified by re-running the full detector (`QfbvDetectorRun`) over all 2,998:
- `undecided` 12 → **11** (the `(r0!=0)` expression now decided by Layer 1: `varies` ⇒ real),
- `conservative` tests 101 → **26**,
- **`CHANGED = 0/2998`** — the exactness/`sdep_impl = sdep_true` invariant is preserved; only
  the *provenance* of these 79 sites moves from `CONSERVATIVE` to `QFBV_VARIES`.

### Category B — unmodeled AArch64/ARM instructions (11 expressions, 27 sites, 26 tests)

Raw assembly operand text leaked into a value-expression slot because the asm lexer does not
model the instruction and did not reject it. Breakdown:

| category | tests | construct |
|---|---|---|
| `branch-label` | 16 | `CBNZ Wn, LC00` + a named label line `LC00:` |
| `cond-select`  | 6  | `CSEL` (conditional select) |
| `cond-set`     | 1  | `CSET` (conditional set) |
| `cond-mov`     | 1  | `MOVWEQ`/`MOVTEQ` (predicated move, ARM) |
| `pc-relative`  | 1  | PC-relative branch target `.+8` |
| `reloc-symbol` | 1  | `__DYNAMIC` relocation symbol |

These are **genuinely outside the supported instruction fragment**. The tests are not correctly
handled (their event structures are corrupted by the mis-lex), so counting them as
supported-syntax tests is wrong.

**Outcome: MOVED OUT.** Per the audit rule below, the 26 tests are excluded from the
supported-syntax corpus.
- Exclusion list with per-test construct: **`eval/layer2-excluded-tests.csv`**.
- Corrected manifest: **`eval/corpus-weakest-manifest-v2.csv`** = the v1 manifest minus the 26.

## The rule (for the paper)

> A litmus test is in the **supported-syntax fragment** only if (a) every instruction is in the
> modeled vocabulary — no conditional-select/set (`CSEL`/`CSET`), predicated moves
> (`MOVccEQ`), PC-relative branch targets (`.+N`), named branch labels, or relocation symbols —
> and (b) every value/address expression parses in the QF_BV arithmetic-plus-relational grammar.
> Control-flow *predicates* (`r != 0`) are in the fragment (Layer 1 after this fix); the
> unmodeled instructions above are not.

## Net effect on the count

| | count |
|---|---|
| Supported-syntax corpus (v1, claimed) | 2998 |
| Category-B tests moved out (unmodeled instructions) | −26 |
| **Supported-syntax corpus (v2, corrected)** | **2972** |
| Layer-2 sites remaining on v2 | **0** |

After the Category-A fix and the Category-B move-out, **Layer 2 is empty on the corrected
2,972-test fragment** — the "all decided by the QF_BV oracle (Layer 1)" claim is now literally
true, with `0/2972` verdict changes vs. the solver-free heuristic.

## Artifacts

- `eval/layer2-audit.csv` — every remaining Layer-2 site (now Category B only).
- `eval/layer2-excluded-tests.csv` — the 26 moved-out tests + offending construct.
- `eval/corpus-weakest-manifest-v2.csv` — corrected 2,972-test manifest.
- Code: `Layer2AuditRun` (new); `QfbvConstancyOracle` (relational grammar + reason diagnostics);
  `LitmusParser.DepDecision` (+thread/instr for the audit).

_No `.tex` files were modified; nothing was committed._
