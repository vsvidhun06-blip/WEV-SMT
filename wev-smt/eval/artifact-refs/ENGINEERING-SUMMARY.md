# Engineering validation summary — idiom-folding detector fix

Session objective: determine whether the detector defect reported in
`findings-attack-impossibility.md` affects any published experimental result.

---

## 1. Detector issue — **CONFIRMED**

The reported defect is real and was reproduced independently of the report.

`LitmusParser.Job.classify` folded cancellation idioms by rewriting the expression
**string** (`e.replaceAll(bounded(pattern), "0")`). The `bounded()` guard only checks that
a match is not adjacent to an identifier character; it does **not** check that the matched
`r OP r` is a sub-term. Consequently:

```
~r0 - r0     parses (~r0) - r0 = -2*r0 - 1     VARIES  ->  ground truth REAL
             contains substring "r0-r0" -> folded to "~0" -> register gone -> FAKE
```

Direction: a genuinely value-carrying dependency is dropped from `sdep`. That is
**unsound** — removing an edge can break a `jf ∪ sdep` cycle and turn a FORBIDDEN outcome
into ALLOWED. It falsifies the `sdep_true ⊆ sdep_impl` property documented in the source.

Independently corroborated three ways: shipped-Java `DEP_TRACE`, the repository's own
`QfbvConstancyOracle` (`isConstant("~r0 - r0","r0") = false`), and Z3 ground truth.

Pre-fix exhaustive measurement: **218 / 8448 (2.58 %)** of 1–2-operator expressions
misclassified in the unsound direction.

## 2. Implementation change

| file | change | lines |
|---|---|---|
| `src/main/java/wev/smt/parse/LitmusParser.java` | `classify` routes folds through a new sub-term guard; new `precOf` / `foldSubterms` / `isSubterm` | **+88 / −6** |
| `src/test/java/wev/smt/parse/LitmusParserTest.java` | 3 regression tests + 1 helper | +70 / −0 |

A fold now fires only when the match is a genuine sub-term, decided from the precedence of
the idiom's top-level operator and its two neighbouring characters. The guard is
conservative: an unrecognised neighbour blocks the fold, which costs precision (sound) but
never soundness. No refactoring; encoder, oracle, Isabelle theories and paper source
untouched. Full detail: `eval/artifact-refs/FIX.patch.md`.

**Deliberately not done** (would exceed "minimal"): moving the `!mentionsReg`
short-circuit to after the `CONSTANCY_ORACLE` consultation. See §5.

## 3. Regression status — **PASS**

| check | result |
|---|---|
| new regression tests (6 witnesses, 7 genuine cancellations, 4 real deps) | pass |
| `LitmusParserTest` | **22/22** (19 pre-existing + 3 new) |
| full suite `mvn -o test` | **57/57**, BUILD SUCCESS |
| exhaustive enumeration, identical 8448 expressions | **218 → 0 unsound** |
| adversarial sweep (depth ≤ 4, parentheses, 2 registers, boundary constants) | **0 / 5076 unsound** |
| 400-program differential vs shipped Java | 400/400 agree; under-approximations **7 → 0** |
| precision cost | over-approximations 369 → 418 on the enumeration (+49, all sound) |

`LBxor` (`r0^r0^1`) and `LBfd` (`r0+1-r0`) still fold — no precision regression on the
published examples.

## 4. Published results — **UNCHANGED**

Full pipeline (`python eval/run_all.py`, 18 steps) run before and after, compared
mechanically by `eval/research/delta.py`:

```
leaf values compared      : 368
CHANGED   (deterministic) : 0
non-deterministic fields  : 113   (wall-clock timings / timestamps)
numbers.tex : 100 macros, 24 differ — all 24 are timings, 0 scientific
tables.tex  : differs only in the timestamp and Table 4's millisecond columns;
              every verdict identical
```

`31/34` published-example agreement, `8/8` Weakestmo, `5/6` PwT, `18/20` JMM, `35/41`
Dat3M, herd7 figures, `79/79` PwT equivalence, the 318-edge detector census, the 2972-test
corpus run — all identical. Root cause of the null delta: **0** occurrences of a
precedence-spanning idiom in 29,202 corpus files, and the fix is inert on assembly-built
expressions (which are flat two-operand strings). Detail: `eval/fix-delta.md`.

## 5. Soundness audit — measured, not claimed

| question | measured answer |
|---|---|
| does the reported issue still exist? | **No** on everything measured: 0/8448 exhaustive, 0/5076 adversarial, 0/400 differential |
| does the detector now satisfy `sdep_true ⊆ sdep_impl`? | **Not established.** No counterexample was found in ~13,900 checks, but that is absence of evidence over a bounded search space, not a proof. The guard is argued sound from precedence, not verified. |
| would the audit suite catch this class of defect in future? | **No.** |

The last row is the important one. `QfbvDetectorRun` compares oracle-on against
oracle-off. Because `classify` returns FAKE on `!mentionsReg` *before* consulting the
oracle, both configurations share the fold and therefore agree — measured directly:

```
pre-fix   oracle absent = FAKE,  oracle installed = FAKE   -> "0/2972 changes"
post-fix  oracle absent = REAL,  oracle installed = REAL   -> "0/2972 changes"
```

The audit reports `0` in both worlds. It is structurally blind to this defect class, and
this fix does not change that. The `layer2` (0 sites) and `detector_audit` (318 edges)
figures are likewise identical pre and post.

**Recommended follow-up (not done here):** move the `!mentionsReg` short-circuit to after
the oracle consultation. That is a ~3-line change which would (a) let an installed exact
oracle override any future folding mistake, and (b) make such a mistake appear as a
divergence in `QfbvDetectorRun`, restoring the audit's ability to detect this class.

## 6. Interaction with the `stripImm` / `lexRegisterAsm` defect

**Independent in mechanism; overlapping in effect on unreachable input.** My a-priori
analysis said "strictly independent" and measurement refuted it, so the measured result
stands:

- The two defects live in different phases — `stripImm` corrupts the expression *string*
  in the lexer; the fold consumed that string in the classifier. The fix changes only
  which matches are folded, never how the string is built, so it neither repairs nor
  aggravates the `#`-immediate defect.
- Exhaustive check over the asm expression shape (588 `(expr, reg)` pairs): folding
  differs in **4**, all of form `#<imm> OP <reg>`. `precOf('#') = −1`, so the new guard
  blocks a fold that previously fired. Direction FAKE → REAL: sound, a precision loss only.
- Reachability: that shape needs the immediate as the *first* source operand
  (`AND Wd,#0,Wm`), which is not valid AArch64/PPC/RISC-V. Real assembly yields `W0&#0`,
  where the pattern matches under neither rule. Unreachable from well-formed input.
- The C dialect cannot contain `#` at all, so the interaction cannot arise there.

## 7. Remaining known issues (none introduced by this fix)

1. **The audit suite cannot detect folding defects** — §5. Highest-value follow-up.
2. **`stripImm` not applied on the asm arith path** — `ADD W1,W0,#1` yields the expression
   `W0+#1`; the `#` trips comment handling and the row is truncated, silently erasing every
   dependency edge on that thread. Pre-existing, unfixed.
3. **`WRITE_ONCE` value truncation** — the C dialect truncates a value at the first `)`,
   and `|` collides with the thread-column separator. Both limit which expressions are
   testable; documented in the new regression test.
4. **`MVN` modelled as addition** — `opSym` has `default -> "+"` and `mvn` sits in
   `ARM_LEX`'s arith row, so `MVN Wd,Wm` becomes `Wd+Wm`. Value-model defect; the dep edge
   stays REAL so it is not an under-approximation.
5. **Soundness is untested at `addr` and `ctrl` dependency sites** — all measurement here
   covers data dependencies. `addAddrDeps` routes through `RegSrc.reads` rather than
   calling `classify` on an address expression, so the composition differs and was not
   modelled.
6. **No git tag was created.** The working tree carried unrelated uncommitted changes at
   session start; tagging would have folded them into a supposedly minimal artifact.
   Equivalent references are provided instead: base commit `dc6fb60`, archived copies of
   both changed files, sha256 prefixes in `MANIFEST.json`, and the exact hunks in
   `FIX.patch.md`. Creating `artifact-v1` / `artifact-v2` tags is a one-command step once
   the pre-existing changes are triaged — that is a call for the repository owner.
