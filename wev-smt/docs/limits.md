# Resource limits and graceful degradation (paper §6.4)

This note documents the bounds the WEV-SMT back end places on a single
minimum-witness search, why they exist, and what happens when an input exceeds
them. It is the material behind the paper's §6.4 ("Robustness and limits") and
the companion to the Day-14 edge-case suite (`wev.smt.edgecase.EdgeCaseTest`)
and robustness sweep (`wev.smt.cli.RobustnessSweep`).

## Design principle

> Graceful errors or correct results — never a crash, a hang, or a silent wrong
> answer.

An SMT back end has two failure modes a reviewer will probe for:

1. **Silent corruption.** A structurally broken execution (a program-order
   cycle, a read with no write to read from, two contradictory initial writes)
   reaches Z3, well-formedness comes back UNSAT, and that is reported as a
   genuine "this outcome is forbidden" verdict. No crash flags the mistake.
2. **Runaway cost.** A large or pathological structure makes Z3 run for minutes
   or exhaust the heap, and the tool hangs or dies with an `OutOfMemoryError`
   mid-batch.

The first is closed by `wev.smt.validate.InputValidator` (see below); the
second by the resource ceilings in `MinimalWitnessExtractor`.

## Input validation (closes silent corruption)

`InputValidator.validate(EventStructure, DependencyInfo)` runs before any
encoding — in `LitmusParser` just before it emits a `LitmusCase`, and in the
`MinimalWitnessExtractor` constructor before it builds activation literals. It
classifies each finding:

| Check | Severity | Rationale |
|-------|----------|-----------|
| `po` acyclic | **error** | a cycle makes the position order vacuously UNSAT |
| `po` total per-thread | warning | thread 0's init writes are *intentionally* incomparable, so this can never be a hard error |
| every read has a write (or rf candidate) | **error** | otherwise "no valid execution exists" |
| initial writes consistent | **error** | the initial state must fix one value per location |
| `po`/`rf`/`co`/dep reference present events | **error** | a dangling id is a removed-event leak |

Any error throws `InvalidEventStructureException`, carrying the full
`ValidationReport`. By construction the checks never fire on a well-formed
litmus test (the parser and the parametric builders always wire one initial
write per location, straight-line per-thread `po`, and an `rf` target for every
read), so the corpus, the atlas, and the scalability sweeps validate cleanly.

## Resource ceilings (closes runaway cost)

`MinimalWitnessExtractor` pre-flights every search (`findMinimalConsistent`,
`findMinimalSeparating`) against two configurable ceilings, **before** building
or solving any formula. A breach logs the reason at `INFO` and returns
`Optional.empty()` — the same "no witness" signal the caller already handles, so
witness extraction stays *sound under pressure*: an oversized problem yields no
(false) witness rather than a crash.

| Property | Default | Meaning |
|----------|---------|---------|
| `wev.smt.maxEvents` | `256` | maximum events in the structure |
| `wev.smt.maxVars` | `100000` | maximum estimated decision-variable count |

Both are read per call, so a reviewer can tighten or loosen them on the JVM
command line (`-Dwev.smt.maxEvents=512`) and a test can set them temporarily.

`estimatedVars()` is a deliberately conservative screening heuristic: two
integer vars per event (position + ew-group) plus one boolean per
`rf`/`jf`/`co` edge and per syntactic dependency edge. The per-model layer and
reachability variables minted inside each consistency call are *not* counted, so
the true var count is somewhat higher — the ceiling is a guard rail, not an
accountant.

### Why 256 events?

The minimum-witness search runs Z3's **optimisation** backend (minimising the
active-event cardinality) over an encoding whose well-formedness alone is
`O(n²)` in distinct-position constraints, on top of the per-model consistency
layer. Decision time grows polynomially in `n` across all five models (Day-9
sweep, `scalability-consistency.csv`), but the constant is large enough that
multi-hundred-event structures move from "seconds" toward "minutes".

256 events is roughly 4× the largest structure the sweeps ever build
(`IRIWFan(16)` is `4n` = 64 events) and an order of magnitude past the litmus
corpus (the largest classic is well under 20 events). It is set as the ceiling so
that:

* every real input — corpus, atlas, both sweeps — is comfortably inside it and
  is solved normally (the guard never fires for them); and
* a deliberately huge probe is refused instantly with a logged reason instead of
  being attempted and risking a multi-minute hang.

The Day-14 robustness sweep (`RobustnessSweep`) exercises the boundary directly
with the load-buffering chain, which is `3n` events:

* `LBChain(64)` = **192 events** — *under* the 256 ceiling, so it is attempted
  under a per-case wall-clock cap. A solve that exceeds the cap is recorded as a
  handled timeout, not a crash — documented evidence that a large-but-admissible
  problem can exceed practical solve time and is correctly surfaced as "no
  witness within budget".
* `LBChain(128)` = **384 events** — *over* the 256 ceiling, so the resource guard
  refuses it instantly and returns empty with a logged reason, never attempting a
  solve that could hang or exhaust the heap.

This pairing is why the default sits at 256: 192 is admitted (and stress-tests
the solver), 384 is refused (and stress-tests the guard).

### Memory observability

`MinimalWitnessExtractor` logs a heap snapshot (`total` / `used` MB) immediately
before and after each `opt.check()` at `FINE` level (off by default; enable with
a `java.util.logging` config). This is the hook for the §6.4 memory discussion
and lets a reviewer confirm that a refused or timed-out solve does not leak heap
across a batch run.

## What a reviewer sees at each boundary

| Input | Outcome | Mechanism |
|-------|---------|-----------|
| empty structure | `Optional.empty()` | early return |
| `po` cycle / read-without-write / conflicting init | `InvalidEventStructureException` with report | `InputValidator` |
| structure over `maxEvents` / `maxVars` | `Optional.empty()`, reason logged | resource ceiling |
| structure within limits but slow | solved, or timed out and reported (no crash) | caller's wall-clock cap |
