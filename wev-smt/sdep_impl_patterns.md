# Fake-dependency patterns recognized by `sdep_impl` (WEV-SMT)

The detector is `LitmusParser.dependsReally(expr, reg)`. It folds self-cancelling
identity idioms to a constant, then checks whether `reg` still appears: if it does
the dependency is **semantic** (`isSemantic = true`); if it has vanished the
dependency is **fake** (`isSemantic = false`) and is excluded from `semanticEdges()`
— i.e. dropped from `sdep_impl`.

The fake patterns are exactly the entries of the `toZero` and `toOnes` arrays.
There are **10 regex entries** spanning **6 distinct idiom families** (the extra
entries are commutative variants). The folding loop runs to a fixpoint, so nested /
compound occurrences collapse as well.

| # | Name (code) | Expression | Dependency type | Why value-independent |
|---|-------------|-----------|-----------------|------------------------|
| 1 | `toZero[0]` `R^R` | `r ^ r` | data, addr | XOR of a value with itself is 0 for all `r`. |
| 2 | `toZero[1]` `R-R` | `r - r` | data, addr | A value minus itself is 0 for all `r`. |
| 3 | `toZero[2]` `R&~R` | `r & ~r` | data, addr | Bitwise AND of `r` with its complement is 0 (no bit set in both). |
| 4 | `toZero[3]` `~R&R` | `~r & r` | data, addr | Commuted form of #3; still 0 for all `r`. |
| 5 | `toZero[4]` `R*0` | `r * 0` | data, addr | Any value times 0 is 0. |
| 6 | `toZero[5]` `0*R` | `0 * r` | data, addr | Commuted form of #5; 0 for all `r`. |
| 7 | `toZero[6]` `R&0` | `r & 0` | data, addr | Bitwise AND with 0 clears every bit → 0. |
| 8 | `toZero[7]` `0&R` | `0 & r` | data, addr | Commuted form of #7; 0 for all `r`. |
| 9 | `toOnes[0]` `R|~R` | `r \| ~r` | data, addr | Bitwise OR of `r` with its complement sets every bit (all-ones, folded to the constant `1`). |
| 10 | `toOnes[1]` `~R|R` | `~r \| r` | data, addr | Commuted form of #9; all-ones for all `r`. |

**Folding semantics.** `toZero` patterns are rewritten to the literal `"0"`,
`toOnes` patterns to the literal `"1"` (a constant placeholder for "all-ones" in
this evaluator — the actual value is irrelevant, only that `reg` no longer appears).
The two loops iterate until no further rewrite applies, so e.g. `(r^r)+(r-r)` folds
to `0+0` and is classified fake. A read reaches a write expression as semantic only
if it is a semantic input to its register **and** that register survives folding
(`referencedReads` ANDs the two flags, line 722).

## Source location

`src/main/java/wev/smt/parse/LitmusParser.java`

- Pattern arrays: **lines 1124–1128** (`toZero` = lines 1124–1127, `toOnes` = line 1128), inside `dependsReally(String expr, String reg)` at **lines 1121–1142**.
- Doc comment naming the idioms: lines 1115–1119 (and the class-level note at lines 60–63).
- Bounded-match helper `bounded(...)`: lines 1144–1146 (each pattern is wrapped in `(?<![A-Za-z0-9_]) … (?![A-Za-z0-9_])` so it only matches whole register tokens).
- Semantic flag stored on the edge: `DependencyInfo.DepEdge.isSemantic`, consumed by `DependencyInfo.semanticEdges()` (`src/main/java/wev/smt/DependencyInfo.java`, lines 51, 117–127).

## Address fakes vs. data fakes — verified, same approximation (confirms §3.4)

Address dependencies are subject to the **same** canceller as data dependencies; there is no separate address code path.

- Data: `addDataDeps` (line 693) → `referencedReads(expr, env)` → `dependsReally`.
- Address: `addAddrDeps` (line 700) reads the per-read semantic flags stored on the index register's `RegSrc`. Those flags are computed by `deriveReg` (lines 729–733), which itself calls `referencedReads` → `dependsReally`. So a register holding `r ^ r` (etc.) is marked fake, and when that register is used as an address index the address dependency inherits the fake classification.

Net: all 10 patterns above apply identically to **data** and **address** dependencies.

## Control dependencies — exempt (conservatively retained), confirms §3.4

Control dependencies never pass through `dependsReally`. They are added with the
semantic flag hardcoded to `true`:

```
line 593:  for (ReadEvent cr : ctrlReads) deps.addCtrlDep(wr, cr, true);
```

So a control dependency is retained as semantic regardless of the branch
condition's expression shape — any pattern that *would* cancel a control dep is
irrelevant, exactly as §3.4 states.

## Gating

The detector itself is **not** flag-gated — `dependsReally` always runs during parsing.
Note, however, a separate runtime knob exists at the consistency layer:
`wev.smt.ablation.SdepConfig` (used by `AblationRun`) can override classification globally
for ablation studies — `NONE` strips all sdep edges, `ALL_DEPS_SEMANTIC` forces every
dependency semantic, `CURRENT` uses the per-edge `isSemantic` flags produced by the patterns
above. This is an evaluation override, not part of the pattern detector.
