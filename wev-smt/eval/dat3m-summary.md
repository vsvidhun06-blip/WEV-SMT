# WEV-SMT (WEAKEST) vs Dat3M — comparison summary

Comparison of our SMT checker under the **WEAKEST** (Weakestmo) model against
**Dat3M / Dartagnan v4.4.1** (bounded model checker for weak memory) over the 41
litmus tests in `eval/examples/{paper,jmm,weakestmo,leaky-semicolon}`.

- Full per-test data: [`eval/dat3m-comparison.csv`](dat3m-comparison.csv)
- Every Dat3M invocation: [`eval/dat3m-command-lines.txt`](dat3m-command-lines.txt)

## Setup

| | |
|---|---|
| Dat3M | github.com/hernanponcedeleon/Dat3M, commit `d8de5e8e` (Release **4.4.1**) |
| Build | `mvn clean install -DskipTests -pl dartagnan -am` (Java 21, Maven 3.9.11) |
| Backend | JavaSMT + Z3 (JVM mode), `--property=program_spec` |
| WEV | `wev.smt.cli.WevBatch`, `consistencyWEAKEST` on the loader-wired candidate |

**Model choice — why LKMM.** The tests use Linux-kernel primitives
(`READ_ONCE`/`WRITE_ONCE`). IMM and RC11 (targets `imm` / `c11`) **reject** these
programs — Dat3M reports *"Found non-core events: LKMMStore"*, because those targets
lower only C11 atomics, not LKMM accesses. The closest native model that actually
accepts the programs is therefore **LKMM** (`linux-kernel.cat`, `--target=lkmm`).
The single AArch64 test (`MP+dmb.sy+addr`) runs under its native **AArch64** model
(`aarch64.cat`, `--target=arm8`). Both are dependency-aware models, the right point
of comparison for WEAKEST's thin-air/dependency behaviour.

**Verdict mapping (empirically calibrated).** For a litmus `exists`, Dartagnan
reports `PASS`/`FAIL`. Calibrating on a trivially-reachable and a trivially-unreachable
single-thread test gives: **`PASS` = exists reachable = `ALLOWED`**, **`FAIL` =
unreachable = `FORBIDDEN`**. (This is the *opposite* of the naive "FAIL = violation"
reading, so the calibration mattered.)

**Input normalization.** Dat3M's front-end is stricter than WEV's, so inputs were
normalized — **each step verified to preserve the WEV/WEAKEST verdict** by re-running
WEV on the transformed file (round-trip):

| step | affected | why |
|---|---|---|
| (a) strip non-ASCII comment chars (`—`→`-`) | all | Dat3M's ANTLR lexer is ASCII-only |
| (b) column format → `Pn(int*…){…}` function body | 12 C tests | Dat3M's C litmus parser needs function bodies |
| (c) `if(c) ; W;` → `if(c) { W; }` | 12 ctrl tests | Dat3M rejects empty-body `if`; same read→write ctrl dep, identical for the `exists` query |
| (d) reg-init→`MOV`, `W`→`X` regs, `[Xn,Wm,SXTW]`→`[Xn,Xm]` | 1 AArch64 test | Dat3M AArch64 init/exists accept only X-regs |

All 41 conversions reproduced the original WEV verdict exactly (e.g. the transformed
`TC19` still yields `ALLOWED`, not a blind `FORBIDDEN`), so the comparison is on
faithful equivalents of the original tests.

## Results

- **Total tests:** 41 (paper 7, jmm 20, weakestmo 8, leaky-semicolon 6)
- **Agreement: 31/41 = 75.6 %**; 10 disagreements, 0 Dat3M errors/timeouts.

| directory | agree |
|---|---|
| paper | 3/7 |
| jmm | 16/20 |
| weakestmo | 8/8 |
| leaky-semicolon | 4/6 |

### Runtime

Both figures are **analysis time excluding JVM startup** (WEV = encode+solve in a
shared JVM; Dat3M = its own reported verification `Time:`). Dat3M additionally pays a
~1–1.5 s JVM startup per invocation not counted here.

| | median | mean | min | max |
|---|---|---|---|---|
| WEV-SMT (ms) | 10 | 13 | 9 | 39 |
| Dat3M (ms) | 516 | 524 | 428 | 794 |

WEV is ~**50× faster** on these tiny tests. The gap is expected and not
apples-to-apples: WEV checks the consistency of **one loader-wired candidate**
(a fixed rf/co execution), whereas Dat3M does full **bounded model checking** —
it existentially searches over all rf/co and thread interleavings and compiles the
memory-model `.cat` axioms into the SMT query. WEV solves a much smaller problem.

## Disagreement analysis (10)

Every disagreement is attributable to a known, documented WEV modelling choice — not
a Dat3M bug. Three classes:

### 1. `semantic_vs_syntactic_dep` — 6 tests (WEV ALLOWS, Dat3M FORBIDS)

`paper/LB-fake-xor`, `paper/LB-fake-xor-cycle`, `paper/LBfd`, `paper/MP+dmb.sy+addr`,
`leaky-semicolon/LBfd`, `leaky-semicolon/LBxor`.

These carry a **fake dependency** — `r^r`, `r+1-r`, `r^r^1` (data), or `EOR r,r,r`
(address) — that is syntactically present but semantically constant. **This is exactly
what WEAKEST is built to detect:** its `jfCoherence` axiom uses *semantic* dependencies
(`isSemantic`), so it cancels the fake edge and **allows** the load-buffering / MP
outcome. LKMM and AArch64 use **syntactic** dependencies (any register mention orders
the access), so they keep the edge and **forbid**. This is the headline result: on the
fake-dependency corpus WEAKEST is strictly weaker than the syntactic-dependency native
models, and correctly so per the Weakestmo / Leaky-Semicolon papers. (`LB-real`, the
*genuine* dependency, agrees FORBIDDEN in both.)

### 2. `wev_value_abstraction` — 3 tests (WEV ALLOWS, Dat3M FORBIDS)

`jmm/TC06`, `jmm/TC16`, `jmm/TC18`.

WEV's parser evaluates data-dependent store values with loaded values substituted by 0
(`evalConst`), and wires a read of an unavailable value to the initial write. That makes
some **value-unreachable** outcomes look like consistent candidates. Dat3M computes
concrete values across the whole execution and correctly finds the contradiction:
- **TC06** — `y=1` would need P1 to read `x=0`, contradicting `exists 1:r0=1`
  (see [`eval/tc06-analysis.md`](tc06-analysis.md)).
- **TC16 / TC18** — the required value `2` / `1` cannot propagate through the
  dependency chain, so the outcome is unreachable.

Here Dat3M is closer to the true (JMM) intent; WEV's `ALLOWED` is a value-abstraction
artifact of single-candidate wiring.

### 3. `wev_fixed_co_order` — 1 test (WEV FORBIDS, Dat3M ALLOWS)

`jmm/TC13` — the only reverse disagreement. P0 reads `y` twice (`r0=2` then `r1=1`).
WEV wires **one** coherence order (`init, y=1, y=2`), under which the two reads are a
CoRR violation ⇒ `FORBIDDEN`. The outcome is consistent under the *other* order
(`init, y=2, y=1`); Dat3M treats `co` existentially and finds it ⇒ `ALLOWED`. This is
the single-candidate `co` limitation quantified in
[`eval/co-enumeration-results.csv`](co-enumeration-results.csv).

## Strengths & limitations

**WEV-SMT (WEAKEST)**
- **+** Semantic dependency analysis: the only checker here that distinguishes fake
  (`r^r`, `r+1-r`) from real dependencies — its reason for existing.
- **+** Very fast (single-candidate consistency, median 10 ms).
- **−** Checks only the **one** loader-wired candidate: a fixed `co` order (misses
  TC13) and a load-value abstraction (spurious ALLOWED on TC06/16/18). Not a full
  reachability search.
- **−** WEAKEST is not a native Dat3M model, so there is no exact oracle — agreement
  is expected to differ precisely on the dependency and multi-candidate cases above.

**Dat3M / Dartagnan**
- **+** Sound bounded model checking: existential search over rf/co/interleavings with
  concrete value semantics; robust on the value/co cases WEV abstracts.
- **+** Broad native model library (`imm`, `rc11`, `lkmm`, `tso`, `aarch64`, …) driven
  by editable `.cat` files.
- **−** Uses **syntactic** dependencies (LKMM/AArch64), so it cannot see fake-dep
  cancellation — the 6 `semantic_vs_syntactic_dep` cases.
- **−** No native Weakestmo model; stricter front-end (ASCII-only, function-body C,
  non-empty `if`) required input normalization.
- **−** ~50× slower here and ~1.5 s JVM startup per run (negligible at this scale, but
  it is a heavier tool).

## Bottom line

The two tools **agree on 31/41 (75.6 %)**, including all coherence tests, all genuine
dependency / synchronisation tests, and every `weakestmo/` test. The 10 disagreements
are systematic and explained: **6** show WEAKEST's intended advantage (semantic vs
syntactic dependencies), and **4** expose WEV's single-candidate simplifications
(value abstraction ×3, fixed `co` ×1) where Dat3M's full search is more accurate.
