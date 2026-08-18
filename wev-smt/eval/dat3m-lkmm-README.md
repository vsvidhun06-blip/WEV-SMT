# Dat3M/LKMM external dependency baseline — hard-expression rings

Evidence for the external-baseline result reported in §6.2 and App. A.4 of the paper:

> On the 1,350 instances Dat3M's LKMM front end can represent, Dat3M v4.4.1 reproduces
> that row's verdicts exactly (1,350/1,350) and forbids all 390 grounded rings WEAKEST
> admits.

## 1. What the experiment validates

It establishes that the **all-dependencies row of Table 2 is not an authored strawman**.
That row is definitional inside the benchmark — the inclusion filter admits an instance
only when the all-dependencies baseline forbids it — so on its own it records the
population's thin-air/grounded split rather than a measured accuracy. Running a
*deployed, third-party* syntactic-dependency model over the same instances shows the row
corresponds to real tool behaviour.

It is a **model comparison, not a defect report**. LKMM orders by syntactic dependency by
design; forbidding a grounded ring is correct LKMM behaviour, not a Dat3M bug. The
experiment instantiates the all-dependencies row only. No deployed tool performs the
constant-folding baseline's normalisation, which remains internally defined.

## 2. How the 3,656 instances are partitioned

`eval/generated-hard-pairs.csv` holds 3,656 rows: 1,828 candidate expressions × 2 ring
sizes (n ∈ {2,3}).

| partition | count | basis |
|---|---:|---|
| scored (Dat3M-representable) | **1,350** | 675 expressions × 2 ring sizes |
| excluded (LKMM parser rejects) | **2,306** | 1,153 expressions × 2 ring sizes |
| **total** | **3,656** | |

## 3. Why 2,306 are excluded

Dartagnan's LKMM grammar has **no bitwise-NOT token**. Any expression containing `~` is
rejected before analysis:

```
Result: ERROR
Reason: ParsingException
Details: Line 8:30 extraneous input '~' expecting {..., Identifier, DigitSequence, ..., '!', '(', '*', '-', '_'}
```

These instances are **excluded rather than scored** — they are not counted as agreements
or disagreements. The exclusion is *evidenced, not asserted*: all 2,306 are actually run,
and each row of `dat3m-lkmm-excluded.csv` carries Dartagnan's own rejection text. The
partition is therefore a property of the tool, reproducible by anyone, not a label applied
by hand.

## 4. How the 1,350 subset is scored

Each instance is re-rendered as the same ring `HardExprBenchRun` builds — thread *i* reads
`x_i` and writes `x_(i+1) mod n` with the candidate expression over its own register, under
`exists (⋀_i i:r_i = 1)` — but emitted in Dat3M's **function-body C litmus syntax**
`Pn(int *x0, …) { … }` instead of the column format the Java driver uses internally. This
is normalization (b) of `eval/dat3m-command-lines.txt`, applied to generated rather than
published tests. Dat3M is then run once per instance under `linux-kernel.cat`.

Instance identifiers are `HARD-n{n}-{hex(Java String.hashCode)}` — the same names
`HardExprBenchRun` uses — and `EXPR-{same hex}` for the expression. Both are pure functions
of the expression text and ring size, so they are stable across runs and machines, and every
row joins back to `eval/generated-hard-pairs.csv` on `(expr, threads)`.

## 5. How expected verdicts are obtained

`expected_verdict` is the **all-dependencies baseline**, column `syntactic` of
`eval/generated-hard-pairs.csv` — produced by `HardExprBenchRun`, independently of this
experiment. Because that baseline is the benchmark's inclusion filter, it is `FORBIDDEN`
for every retained instance.

Two further columns are carried so the grounded subset can be selected without consulting
this script or Dat3M:

- `ground_truth_required` — the QF_BV oracle's verdict, queried directly on the expression;
- `weakest_verdict` — WEAKEST's own verdict.

Dat3M contributes to none of them.

## 6. How the 390 grounded rings are selected

A **grounded** ring is one whose expression is constant in the read register, so no semantic
dependency closes the cycle and the outcome is genuinely admissible. Selection is
`ground_truth_required == ALLOWED` within the scored set — i.e. the oracle's own label,
applied before Dat3M runs.

That yields **195 expressions × 2 ring sizes = 390**, each storing the constant 1. These are
exactly the instances WEAKEST admits and the all-dependencies baseline forbids: the
separation the paper is about. Dat3M forbids **390/390**.

## 7. How to reproduce

```
cd eval/corpus/Dat3M && mvn clean install -DskipTests -pl dartagnan -am   # once
python eval/dat3m_lkmm_hardbench.py                                       # ~10 min at --jobs 12
```

Run from the repository root. The driver renders the inputs, runs the five controls, runs
all 3,656 instances, verifies every count below, and only then writes its outputs. It
**aborts and writes nothing** if a control disagrees with its expected verdict or if any
count fails to reproduce — it will not emit partial or adjusted evidence.

`--controls-only` runs just the calibration controls; `--jobs N` sets parallelism.

## 8. Expected final counts

| quantity | expected |
|---|---:|
| total instances | 3,656 |
| scored (representable) | 1,350 |
| excluded (parser) | 2,306 |
| agreement with all-dependencies | 1,350 / 1,350 |
| mismatches | 0 |
| timeouts / tool failures / parse failures *in the scored set* | 0 / 0 / 0 |
| grounded rings | 390 |
| distinct grounded expressions | 195 |
| grounded rings Dat3M forbids | 390 / 390 |
| grounded rings Dat3M allows | 0 |

## 9. Dat3M version and configuration

| | |
|---|---|
| Dat3M / Dartagnan | v4.4.1 |
| commit | `d8de5e8ef6aa4a697ff2bbe88fa8416dfcd4ac7c` ("Release version 4.4.1 (#1032)") |
| model | `eval/corpus/Dat3M/cat/linux-kernel.cat` |
| target | `--target=lkmm` |
| property | `--property=program_spec` |
| Java | Temurin 21.0.10+7 |

LKMM is used because it is the deployed model whose ordering is syntactic-dependency based;
IMM and RC11 reject `READ_ONCE`/`WRITE_ONCE` programs outright. Same model and invocation
shape as the 41-test cross-check in `eval/dat3m-command-lines.txt`.

Exact command line and a SHA-256 manifest of every output: `eval/dat3m-lkmm-environment.txt`.

## 10. PASS/FAIL interpretation

```
Dat3M PASS = the exists clause is reachable   = ALLOWED
Dat3M FAIL = the exists clause is unreachable = FORBIDDEN
```

**This must not be read off Dartagnan's wording.** On `FAIL` it prints
`Reason: Program specification violation found`, which reads as though the outcome had been
observed. It has not: Dartagnan negates the litmus `exists` clause into a safety assertion,
so "violation found" means the assertion could not be upheld — the outcome is *unreachable*.
Taking that wording at face value inverts every verdict in the experiment.

The mapping is therefore established empirically by five controls, run before any instance
is scored (`eval/dat3m-lkmm-controls.txt`):

| control | expects | why it is decisive |
|---|---|---|
| `CTRL-reach-yes` | ALLOWED | one thread writes `x0=1`, condition asks `x0=1` — reachable under every model |
| `CTRL-reach-no` | FORBIDDEN | same write, condition asks `x0=2` — unreachable under every model; **this is the control that overrides the wording** |
| `CTRL-LB-nodep-n2` | ALLOWED | dependency-free LB ring at n=2 — LKMM orders nothing |
| `CTRL-LB-nodep-n3` | ALLOWED | same at n=3, the other ring size used here |
| `CTRL-LB-realdep-n2` | FORBIDDEN | same ring with a real data dependency — LKMM orders it |

Controls 1–2 fix the token mapping model-independently; 3–5 confirm it on the benchmark's
own ring shape and show Dat3M is responding to dependency content there, which is what makes
the comparison meaningful.

## Files

| file | contents |
|---|---|
| `dat3m-lkmm-hardbench.csv` | 1,350 scored instances, one row each |
| `dat3m-lkmm-grounded-rings.csv` | the 390 grounded rings, one row each (a subset of the above) |
| `dat3m-lkmm-excluded.csv` | 2,306 excluded instances with Dartagnan's rejection text |
| `dat3m-lkmm-controls.txt` | the five controls: rationale, litmus source, observed result |
| `dat3m-lkmm-environment.txt` | tool/model/Java versions, exact command, SHA-256 manifest |
| `dat3m_lkmm_hardbench.py` | the deterministic driver that produces all of the above |
| `generated-dat3m/*.litmus` | the 1,350 rendered Dat3M inputs |

Timestamps in the generated files are provenance only and carry no scientific weight.
