# Independently generated matched-pair benchmark

Artifact: `eval/generated-pairs.csv` (1,600 rows). Programs: `eval/generated/*.litmus`.
Harness: `src/main/java/wev/smt/cli/GeneratedPairsRun.java`.

## Construction

`n`-thread load-buffering rings emitted as `.litmus` **source text** and pushed through
the ordinary front end (`LitmusParser.parse`). Nothing sets `isSemantic` by construction.

- thread `i`: `ri = READ_ONCE(*xi)` then `WRITE_ONCE(*x(i+1 mod n), E_i(ri))`
- `exists (0:r0=1 /\ ... )` — every read observes 1
- the first `k` threads carry a *fake* expression (constant in `ri`), the rest a *real* one
- all store expressions evaluate to 1 at `ri = 0`, so every variant wires the **same**
  candidate: identical `po`, `rf`, `co`, identical observed values

Sweep: `n in 2..6`, `k in 0..n`, 8 fake x 8 real expression shapes = **1,600 cases**.

## Ground truth (independent of the code under test)

Constancy is decided by querying `QfbvConstancyOracle.isConstant(expr, reg)` **directly**,
bypassing the front end entirely — in particular bypassing the classifier's cancellation
fold, which can decide a site before the oracle is reached. Required verdicts then follow
from the model's own theorems:

- every dependency on the ring fake  => no `sdep` edge => `sdep u jf = jf` bipartite =>
  acyclic => **required ALLOWED** (grounded-LB admission)
- at least one fake  => the `sdep u jf` cycle is broken at that edge => **required ALLOWED**
- every dependency semantic => cycle closes => **required FORBIDDEN** (thin-air)

## Validity gate

A case counts only if (a) the front end recorded exactly `n` data-dependency edges, and
(b) the syntactic baseline returns FORBIDDEN. (b) certifies the ring is genuinely wired as
a cycle, and is independent of the criterion under test. 160 cases are excluded because one
real shape (`r << 1 ^ 1`) lies outside the oracle's grammar, so no independent label exists.

## Results

|                          |                 |
|--------------------------|-----------------|
| generated                | 1,600           |
| valid                    | 1,440           |
| excluded (oracle-undecided) | 160          |
| **discriminating**       | **1,015**       |

| criterion            | accuracy (valid) | accuracy (discriminating) |
|----------------------|------------------|---------------------------|
| WEAKEST (`sdep`)     | **1,440/1,440 (100%)** | **1,015/1,015 (100%)** |
| syntactic baseline   | 425/1,440 (29.5%)      | **0/1,015 (0%)**       |

WEAKEST confusion over the 1,440 valid cases: TP 425, TN 1,015, **FP 0, FN 0**.

Wilson 95% CI on discriminating cases: WEAKEST [0.9962, 1.0000]; syntactic [0.0000, 0.0038].
Non-overlapping.

### By thread count

| n | valid | discriminating | WEAKEST correct |
|---|------:|---------------:|----------------:|
| 2 | 176 | 105 | 176 |
| 3 | 232 | 154 | 232 |
| 4 | 288 | 203 | 288 |
| 5 | 344 | 252 | 344 |
| 6 | 400 | 301 | 400 |

### By number of fake sites k

k=0 is the all-real ring (required FORBIDDEN, never discriminating); k>=1 is discriminating.

| k | valid | discriminating | WEAKEST correct |
|---|------:|---------------:|----------------:|
| 0 | 280 |   0 | 280 |
| 1 | 280 | 245 | 280 |
| 2 | 288 | 252 | 288 |
| 3 | 232 | 203 | 232 |
| 4 | 176 | 154 | 176 |
| 5 | 120 | 105 | 120 |
| 6 |  64 |  56 |  64 |

### Expression shapes exercised

fake: `r ^ r ^ 1`, `r - r + 1`, `r * 0 + 1`, `r & 0 ^ 1`, `r * 0 ^ 1`, `r - r ^ 1`,
`r * 0 - 0 + 1`, `r ^ r - 0 + 1`
real: `r + 1`, `r ^ 1`, `1 - r`, `r * 2 + 1`, `r * 3 + 1`, `r & 2 ^ 1`, `r + 1 ^ 0`,
`r << 1 ^ 1` (excluded, outside oracle grammar)

## Incidental fidelity datum

On all 1,440 valid cases the flags recorded by the front end agree with the direct oracle
query: **1,440/1,440**. This extends the 12,106-pair expression-level audit into whole-program
context, where the fold's short-circuit is reachable.
