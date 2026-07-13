# Source-paper named-example validation

WEV-SMT (the `WEAKEST` axiomatic model) run against the named litmus examples of the
two dependency-tracking relaxed-memory papers, under the three `SdepConfig` sdep
ablation configurations (`none` = `sdep = ∅`, `all-deps-semantic` = `sdep = dep`,
`current` = the shipped 10-pattern fake/real detector). Every verdict uses the frozen
encoder + `AxiomaticConsistency`; the only knob is the `isSemantic` re-flagging of the
`DependencyInfo` sidecar.

- **Papers.** Chakraborty & Vafeiadis, *Grounding Thin-Air Reads with Event Structures*,
  POPL 2019 (Weakestmo). Jeffrey, Riely, Batty, Cooksey, Kang, Vafeiadis,
  *The Leaky Semicolon: Compositional Semantic Dependencies for Relaxed-Memory
  Concurrency*, POPL 2022 (PwT / pomsets-with-predicate-transformers).
- **Files.** `eval/examples/weakestmo/*.litmus` (8), `eval/examples/leaky-semicolon/*.litmus` (6).
- **Driver.** `wev.smt.ablation.PaperExamplesRun` (evaluation-only; reuses `SdepConfig`
  transforms + the frozen solve machinery, no parser/detector/semantic edits).
  Run: `java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)"
  wev.smt.ablation.PaperExamplesRun dir=eval/examples/<dir> out=<csv>`.
- **Data.** Per-example source-vs-wev verdicts in `eval/source-paper-results.csv`;
  full per-config raw verdicts + dependency-edge counts in
  `eval/source-paper-ablation-raw.csv`.

## Totals

| metric | value |
|---|---|
| total examples | 14 |
| Weakestmo examples | 8 |
| Leaky-Semicolon examples | 6 |
| agree (`current` verdict = source verdict) | 13 |
| disagree | 1 |
| **agreement rate** | **13 / 14 = 92.9 %** |
| ERROR / TIMEOUT | 0 / 0 |

## Per-example verdicts (`current` config)

| test | source | dep_type | source | wev (`current`) | match |
|---|---|---|---|---|---|
| LB | weakestmo | none | ALLOWED | ALLOWED | ✓ |
| LBd | weakestmo | DATA | FORBIDDEN | FORBIDDEN | ✓ |
| OOTA | weakestmo | DATA | FORBIDDEN | FORBIDDEN | ✓ |
| LB+ctrl | weakestmo | CTRL | FORBIDDEN | FORBIDDEN | ✓ |
| MP | weakestmo | none | ALLOWED | ALLOWED | ✓ |
| CoRR | weakestmo | none | FORBIDDEN | FORBIDDEN | ✓ |
| CoRW | weakestmo | none | FORBIDDEN | FORBIDDEN | ✓ |
| CoWR | weakestmo | none | FORBIDDEN | FORBIDDEN | ✓ |
| LB | leaky-semicolon | none | ALLOWED | ALLOWED | ✓ |
| LBd | leaky-semicolon | DATA | FORBIDDEN | FORBIDDEN | ✓ |
| LBfd | leaky-semicolon | DATA (fake) | ALLOWED | ALLOWED | ✓ |
| LBxor | leaky-semicolon | DATA (fake) | ALLOWED | ALLOWED | ✓ |
| LBcd | leaky-semicolon | CTRL | FORBIDDEN | FORBIDDEN | ✓ |
| **LBfc** | leaky-semicolon | CTRL (fake) | ALLOWED | **FORBIDDEN** | ✗ |

## Which config first produces the correct verdict

Reading the three configs left-to-right (`none`, `all-deps-semantic`, `current`), the
leftmost config whose verdict equals the source verdict:

| deciding shape | examples | first correct config |
|---|---|---|
| no dependency, ALLOWED | LB, MP | `none` |
| no dependency, FORBIDDEN (coherence) | CoRR, CoRW, CoWR | `none` |
| real DATA/CTRL dep, FORBIDDEN | LBd, OOTA, LB+ctrl, LBcd | `all-deps-semantic` |
| fake DATA dep, ALLOWED | LBfd, LBxor | `none` |
| fake CTRL dep, ALLOWED (parser can't see it) | LBfc | `none` (coincidental — see below) |

**The load-bearing observation.** No single-flag config is correct across the whole
real-vs-fake set at once:

- `none` (strip every sdep edge) correctly *allows* the fake-dep LBs (LBfd, LBxor) and
  the base cases, but **wrongly allows** the real thin-air cycles (LBd, OOTA, LB+ctrl,
  LBcd) — it misses exactly the thin-air behaviour both papers exist to forbid.
- `all-deps-semantic` (treat every syntactic dep as real) correctly *forbids* the real
  cycles, but **wrongly forbids** the fake-dep LBs (LBfd, LBxor) — the Leaky-Semicolon
  point that syntactic mention ≠ semantic dependence.
- `current` (the fake/real detector) is the **only** configuration that is simultaneously
  correct on both: it forbids LBd/OOTA/LB+ctrl/LBcd *and* allows LBfd/LBxor. That
  discrimination is the paper's §6 contribution, and this named-example suite reproduces
  it on the two source papers' own examples.

`none`'s "first correct" on LBfc is coincidental: it yields ALLOWED only because it
strips *all* dependencies, the same reason it wrongly allows LBd and LBcd. It is not a
correct dependency analysis; it just happens to agree on the one example where the
detector is over-conservative.

## Disagreements grouped by cause

**1 disagreement / 14 examples.**

### Control-dependency limitation — 1 example (`LBfc`)

`LBfc` (Leaky-Semicolon load-buffering with a *false* control dependency: both arms of
the guard write the same value `1`, so the store happens unconditionally and carries no
semantic control dependency — PwT, and any compiler that removes the redundant branch,
**allow** the outcome).

The WEV-SMT C-dialect parser has no brace/`else` handling: it flattens the
both-arms-write shape to the *same* event structure as the genuine `LBcd` — a
branch-on-read followed by an always-taken store — and records the `read -> store`
control edge with `isSemantic = true` **unconditionally**. The detector therefore cannot
tell that both arms write the same value, keeps the fake ctrl edge in `sdep`, and
`current` over-approximates to **FORBIDDEN**. This is a sound over-approximation (it never
admits a real thin-air outcome) but a precision loss on redundant control dependencies.
It is the same limitation documented for the `LB+ctrldata+ctrl-double` fixture and in
`docs/litmus-parser-coverage.md` (known limitation §1: "control dependencies are
approximate ... without modelling ... branch-taken/not-taken paths").

### Unsupported syntax — 0 examples

Every named example encoded here parses within the supported C dialect and solves with
no `ERROR`/`TIMEOUT`. (Address-dependency LB variants are *not* included in this C-dialect
suite: a real address dependency is expressible to the detector only through the
register-indexed asm-dialect load form — see `eval/examples/paper/MP+dmb.sy+addr.litmus`
and the `addr-dep-coverage-gap` note — not through C pointer arithmetic, which the addr
heuristic does not track. No address example was silently mis-encoded.)

### Model design — 0 examples

No disagreement is attributable to a genuine difference between the WEV `WEAKEST` model
and the source semantics on a faithfully-encoded example. On every example the parser
represents faithfully, `current` matches the paper. The one miss is a *front-end*
(parser) precision gap on redundant control flow, not a model-design divergence.

## Notes on faithful wiring

The fake-dependency LBs use the constant-producing spellings the WEV parser can wire
faithfully: `r+1-r` (LBfd) and `r^r^1` (LBxor) both `evalConst` to the literal `1`, so
the `r0=r1=1` load-buffering cycle is genuinely wired and the `current`=ALLOWED verdict
is a real jfCoherence decision (the fake edge excluded from `sdep`), not a trivially-SAT
all-zeros candidate. The real cases (`LBd`, `OOTA`) use `r+1`, whose data edge survives
folding as `isSemantic = true`. See `eval/examples/paper/` for the original worked-example
triplet this suite generalises.
