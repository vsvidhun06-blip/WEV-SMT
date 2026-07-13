# JMM causality test cases (TC1–TC20) vs. WEAKEST — divergence taxonomy

The 20 Java Memory Model causality test cases (Pugh 2004; decisions cross-checked
against the Promising / PwT literature) encoded as `.litmus` files and run under the
WEV-SMT `WEAKEST` model in all three `SdepConfig` ablation configurations
(`none` = `sdep = ∅`, `all-deps-semantic` = `sdep = dep`, `current` = the shipped
fake/real dependency detector).

- **Files.** `eval/examples/jmm/TC01.litmus … TC20.litmus`.
- **Driver.** `wev.smt.ablation.PaperExamplesRun` (evaluation-only; frozen encoder +
  `AxiomaticConsistency`, no parser/detector/semantic edits). Every one of the 20 solves
  with **no ERROR and no TIMEOUT**.
- **Data.** Per-test verdicts + reasons: `eval/jmm-results.csv`. Raw per-config verdicts
  and dependency-edge counts: `eval/jmm-ablation-raw.csv`.

This is a **taxonomy, not a match rate**. The headline number (18/20 agree) is the least
interesting thing here; what matters is *by what mechanism* WEAKEST reaches each verdict,
and *why* the two divergences occur.

---

## 1. How to read the WEAKEST verdict: three mechanisms, not one

WEV-SMT wires a single candidate execution from each test's `exists` clause and asks
whether that candidate is consistent under `WEAKEST`. A test can come back FORBIDDEN for
two structurally different reasons, and ALLOWED for one — the ablation columns separate
them cleanly:

| mechanism | signature in the ablation | tests |
|---|---|---|
| **(A) Dependency-driven forbidden** — the thin-air mechanism | `none = ALLOWED`, `current = FORBIDDEN` (stripping sdep flips the verdict) | TC02, TC03, TC04, TC07, TC09, TC10, TC17, TC20 |
| **(B) Coherence-driven forbidden** — SC-per-location / candidate inconsistency | `none = FORBIDDEN` (forbidden even with `sdep = ∅`) | TC05, TC08, TC13, TC14 |
| **(C) Allowed** — no closed `sdep ∪ jf` cycle | `current = ALLOWED` (usually all three configs) | TC01, TC06, TC11, TC12, TC15, TC16, TC18, TC19 |

Only mechanism (A) exercises the dependency detector that is the paper's contribution.
The (A) tests are where WEAKEST reproduces the JMM thin-air decisions *for the right
reason*: treating the syntactic dependency as semantic closes the self-justifying cycle,
and the `none` column proves the verdict is dependency-driven (it flips to ALLOWED when
sdep is emptied). This is the core positive result:

> **On every clean load-buffering-shaped causality test with a genuine data or control
> dependency (TC02, TC03, TC07, TC09, TC10, TC17, TC20), WEAKEST's dependency mechanism
> forbids exactly what the JMM forbids, and the ablation confirms the dependency is what
> does it.**

Mechanism (B) tests agree with the JMM verdict too, but the taxonomy flags them honestly:
for TC05/TC08/TC14 the FORBIDDEN comes from the wired candidate being coherence-
inconsistent (a redundant extra read produces a contradictory `rf`/`co` wiring that is
rejected before dependencies are even considered), and only TC13 is a *bona fide*
coherence test where (B) is the intended mechanism. WEAKEST gets the right answer, but not
via the dependency machinery.

---

## 2. Agreement summary

| verdict pair | count | tests |
|---|---|---|
| JMM FORBIDDEN, WEAKEST FORBIDDEN | 11 | TC02, TC03, TC05, TC07, TC08, TC09, TC10, TC13, TC14, TC17, TC20 |
| JMM ALLOWED, WEAKEST ALLOWED | 7 | TC01, TC11, TC12, TC15, TC16, TC18, TC19 |
| **JMM ALLOWED, WEAKEST FORBIDDEN** | 1 | **TC04** |
| **JMM FORBIDDEN, WEAKEST ALLOWED** | 1 | **TC06** |

(11 + 7 = 18 agree, 2 diverge, out of 20.)

Both directions of divergence occur — WEAKEST is neither uniformly stricter nor uniformly
weaker than the JMM. The two divergences have completely different causes.

---

## 3. Divergences grouped by cause

### 3.1 Control-dependency limitation (expected — Batty et al. Theorem 2) — **1 test: TC04**

`TC04` = `TC03` with **both arms of P0's guard writing `x = 1`**. Semantically the control
dependency is *redundant*: the store happens with the same value whether or not the branch
is taken, so a compiler may delete the branch and the JMM **ALLOWS** the outcome. WEAKEST
returns **FORBIDDEN**.

- **Root cause.** The WEV C-dialect parser has no brace/`else` handling. It flattens
  `if (r0==1) x=1 else x=1` to the *same* event structure as the genuine single-arm
  `TC03` — a branch-on-read followed by an always-present store — and records the
  `read → store` control edge with `isSemantic = true` **unconditionally**. TC03 and TC04
  are therefore bit-for-bit identical as parsed (`data=1, ctrl=1, sem 1/0/1`, verdicts
  `none=ALLOWED / all=FORBIDDEN / current=FORBIDDEN`); only the *intended* semantics
  differ.
- **Why it is expected.** This is exactly Batty et al.'s Theorem 2 result that a purely
  syntactic dependency relation cannot be preserved under semantics-respecting compiler
  transformations: redundant control dependencies are removable, so any detector that
  keeps *all* syntactic control edges must over-forbid on the redundant ones. WEAKEST's
  `current` detector is such a detector for control edges (it stratifies data edges into
  fake/real, but keeps every control edge semantic).
- **Direction.** Sound over-approximation: WEAKEST forbids a behavior the JMM allows. It
  never *admits* a real thin-air behavior on this test; it loses precision, not soundness.
- **Same family elsewhere.** The identical mechanism underlies `TC08` (guard
  `if(r0==r1)`, always true — the parser cannot see the redundancy) and the
  `eval/examples/paper/LB+ctrldata+ctrl-double` fixture and `leaky-semicolon/LBfc`. TC08
  only fails to *appear* as a divergence because its wired candidate is independently
  coherence-inconsistent (mechanism B), so it lands FORBIDDEN — which happens to match the
  JMM verdict for that test.

### 3.2 Behavior-in-question not expressible to a single-candidate checker (model/encoding design) — **1 test: TC06**

`TC06` = LB data-dep cycle (P0 stores `x=1` unconditionally, P1 has data dep `y←x`) plus
an independent third writer `P2: y=2`. The JMM-forbidden outcome is **`x` reading the
value 2** — which is *unreachable*: `x` is only ever written 0 (initial) or 1 (P0). The
JMM "FORBIDDEN" here is the degenerate forbidding of an unsatisfiable behavior. WEAKEST
returns **ALLOWED**.

- **Root cause (two compounding factors).**
  1. **Single-candidate encoding.** WEV wires one candidate from the `exists` clause and
     checks *its* consistency; it does not existentially search all executions. The JMM's
     unreachable target (`x = 2`) cannot be wired — there is no write of 2 to `x` — so it
     cannot be posed to the checker at all. The reachable candidate WEAKEST evaluates is
     the ordinary LB outcome, which it (correctly) allows.
  2. **One-sided dependency.** Faithful to the source program, P0's store `x=1` is
     *constant* (no dependency), so only P1 carries a data edge. A single dependency edge
     closes **no cycle**, so even `all-deps-semantic` returns ALLOWED
     (`none=all=current=ALLOWED`). There is simply no `sdep ∪ jf` cycle for WEAKEST to
     forbid.
- **Why it is not a soundness bug.** WEAKEST's ALLOWED is the correct verdict for the
  *reachable* LB behavior of this program (one-sided-dependency load buffering is allowed
  by every dependency-tracking model). The divergence is entirely about the JMM verdict
  referring to a different, unreachable outcome that the single-candidate encoding cannot
  represent. It is a limitation of the *evaluation methodology* (fixed-candidate `.litmus`
  ingestion), classified here as **model/encoding design**, not a disagreement between the
  WEAKEST model and the JMM on a shared behavior.

### 3.3 Unsupported syntax — **0 tests**

Every one of TC1–TC20 is expressible in the supported C dialect and solves with no
ERROR/TIMEOUT. Constructs that were needed and are supported: C initial-value blocks
(`{ x=1; y=1; }`, TC11), two-register comparison guards (`if(r0==r1)`, TC08/TC17),
two-register store arithmetic (`r0-r1+1`, TC14), multi-statement thread bodies
(TC12/TC18), and up to four threads (TC15). Where the source used constructs the parser
cannot represent (an explicit `else` arm in TC04, and cross-thread register names in the
compressed TC05/TC07/TC09 source), the file preserves the dependency **structure** and the
adaptation is documented in the file header; none of these produced a parse failure.

---

## 4. Notable positive: the fake/real detector does the right thing on the traps

Two causality tests are precisely designed to catch a naive dependency analysis, and the
`current` detector handles both correctly:

- **TC14** (`r0 - r1 + 1`, "algebraic cancellation"). The expression *looks* like it folds
  to the constant 1, but `r0` and `r1` are two independent reads of a racy location and
  need not agree, so the dependency is real and the JMM forbids the outcome. The detector's
  fake-dep folder only cancels **single-register** idioms (`r^r`, `r-r`, `r+1-r`); it
  correctly leaves the **two-register** `r0 - r1` unfolded (`sem 3/0/0`), matching the JMM.
- **TC11 / TC12** (dependency present but broken by an initial write / an independent
  store). WEAKEST allows both, because the reader can justify its value from a
  non-dependent write and no `sdep ∪ jf` cycle closes — the detector's semantic edges are
  present but not *on a cycle*, so they do not force FORBIDDEN.

These are the mirror image of the TC04 control-dependency miss: on **data** dependencies
the detector distinguishes genuine from fake and matches the JMM in both directions; on
**control** dependencies it cannot (it keeps every control edge), which is the single
systematic source of over-forbidding in this suite.

---

## 5. One-line taxonomy

| cause | tests | direction | nature |
|---|---|---|---|
| control-dependency limitation (Batty Thm 2) | TC04 | WEAKEST stricter | sound over-approximation; expected |
| behavior-in-question unreachable / one-sided dep (single-candidate encoding) | TC06 | WEAKEST weaker | evaluation-methodology limit; not a model-soundness issue |
| — (agreement, dependency-driven) | TC02, TC03, TC07, TC09, TC10, TC17, TC20 | = | thin-air mechanism, ablation-confirmed |
| — (agreement, coherence-driven) | TC05, TC08, TC13, TC14 | = | SC-per-location / candidate consistency |
| — (agreement, no cycle) | TC01, TC11, TC12, TC15, TC16, TC18, TC19 | = | dependency absent or off-cycle |
