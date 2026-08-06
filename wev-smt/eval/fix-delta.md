# eval/fix-delta.md — published-result delta for the idiom-folding fix

Traceability record for the detector fix validated in this session.

| | |
|---|---|
| baseline snapshot | `eval/pre-fix-snapshot.json` (run 2026-07-28T20:07:54+0100) |
| post-fix snapshot | `eval/post-fix-snapshot.json` (run 2026-07-28T20:47:38+0100) |
| pipeline | `python eval/run_all.py` — identical invocation, 18 steps, both runs |
| comparison driver | `eval/research/delta.py` (mechanical; nothing compared by eye) |
| base commit | `dc6fb60` |

---

## Headline

> **Every deterministic published value is UNCHANGED. No paper conclusion changes.**

```
leaf values compared      : 368
UNCHANGED (deterministic) : 255
CHANGED   (deterministic) : 0
non-deterministic fields  : 113   (wall-clock timings / timestamps)
added / removed keys      : 0 / 0

eval/numbers.tex : 100 macros before, 100 after
                   24 differ — ALL 24 are wall-clock timings, 0 scientific
eval/tables.tex  : differs only in the generation timestamp and the four
                   millisecond columns of Table 4; every VERDICT is identical
```

---

## Per-result table

Every headline result the paper cites. `OLD` = pre-fix, `NEW` = post-fix.

| # | published result | OLD | NEW | delta |
|---|---|---|---|---|
| 1 | corpus files total | 12324 | 12324 | **UNCHANGED** |
| 2 | supported-syntax fragment | 2972 (24.1%) | 2972 (24.1%) | **UNCHANGED** |
| 3 | excluded unmodelled instructions | 26 | 26 | **UNCHANGED** |
| 4 | parse errors | 1865 | 1865 | **UNCHANGED** |
| 5 | Layer-2 detector audit — sites remaining | 0 | 0 | **UNCHANGED** |
| 6 | C1 symbolic validation ladder | 9/9 PASS | 9/9 PASS | **UNCHANGED** |
| 7 | symbolic corpus run — tests | 2972 | 2972 | **UNCHANGED** |
| 8 | symbolic corpus — verdict changes | 536 (413 A→F, 123 F→A) | 536 (413 A→F, 123 F→A) | **UNCHANGED** |
| 9 | symbolic corpus — errors / skipped | 0 / 0 | 0 / 0 | **UNCHANGED** |
| 10 | published-example agreement | **31/34** | **31/34** | **UNCHANGED** |
| 11 | — Weakestmo | **8/8** | **8/8** | **UNCHANGED** |
| 12 | — PwT / Leaky-Semicolon | **5/6** | **5/6** | **UNCHANGED** |
| 13 | — JMM | **18/20** | **18/20** | **UNCHANGED** |
| 14 | JMM suite (symbolic / wired) | 18/20 / 17/20 | 18/20 / 17/20 | **UNCHANGED** |
| 15 | JMM divergences | TC04, TC13 | TC04, TC13 | **UNCHANGED** |
| 16 | Dat3M comparison (symbolic / wired) | 35/41 / 31/41 | 35/41 / 31/41 | **UNCHANGED** |
| 17 | PwT equivalence sweep | 79/79 | 79/79 | **UNCHANGED** |
| 18 | PSO′ vs canonical SPARC PSO | 2/40 divergences | 2/40 divergences | **UNCHANGED** |
| 19 | **QF_BV oracle vs solver-free detector** | **0/2972 changes** | **0/2972 changes** | **UNCHANGED** (see §Soundness) |
| 20 | detector edge census | 318 edges (301 fake, 17 semantic, 5 verdict-relevant) | identical | **UNCHANGED** |
| 21 | RC11 direction check | 6 forbid/allow, 0 reverse, 27 tests | identical | **UNCHANGED** |
| 22 | RA formal atlas | 40/40, 0 subchain violations | identical | **UNCHANGED** |
| 23 | RA-allows/WEAKEST-forbids witnesses | 3 | 3 | **UNCHANGED** |
| 24 | n-thread grounded admission | 7/7 ALLOWED (n=2..8) | 7/7 | **UNCHANGED** |
| 25 | hierarchy violations (SC→weak) | 0 over 2972 | 0 over 2972 | **UNCHANGED** |
| 26 | herd7 ARM_SC | 138/360 (38.3%) | 138/360 (38.3%) | **UNCHANGED** |
| 27 | herd7 non-ARM_SC | 106/123 (86.2%) | 106/123 (86.2%) | **UNCHANGED** |
| 28 | herd7 non-ARM_TSO | 34/46 (73.9%) | 34/46 (73.9%) | **UNCHANGED** |
| 29 | scalability sweep — families / n / max events | 4 / 2..16 / 64 | 4 / 2..16 / 64 | **UNCHANGED** |
| 30 | scalability — all four family **verdicts** | FORBIDDEN, ALLOWED, ALLOWED, ALLOWED | identical | **UNCHANGED** |
| 31 | scalability — solve times (Table 4 ms columns) | see below | see below | **timing only** |

### The only differences: wall-clock measurements

24 macros in `numbers.tex` differ; all are timings and none is a count, ratio of counts,
verdict, or agreement figure.

| macro | OLD | NEW | note |
|---|---|---|---|
| `\MedianRuntimeMs` | 10 | 11 | symbolic solve median |
| `\PNinetyFiveRuntimeMs` | 23 | 24 | |
| `\MaxRuntimeMs` | 73 | 66 | |
| `\MeanRuntimeMs` | 13.0 | 13.3 | |
| `\WeakestMaxMs` | 94 | 91 | |
| `\PsoRaMaxMs` | 4944 | 4868 | |
| `\LbRealFakeRatio` | 0.98 | 0.97 | **derived from timings**: `LBChain_ms / LBFakeChain_ms` (92/94 → 88/91) |
| `\Scal{LBChain,LBFakeChain,SBNThread,IRIWFan}{SC,TSO,WEAKEST,PSORA}` (16 macros) | — | — | per-model solve times at n=16 |
| `\CorpusWallSeconds` | 84.3 | 86.1 | |

`\LbRealFakeRatio` deserves the explicit note because its *name* does not look like a
timing. `eval/run_all.py:598-601` computes it as `round(real_ms / fake_ms, 2)` over the
two WEAKEST solve times at n=16. It is wall-clock noise, not a semantic ratio.

`tables.tex` differs on exactly five lines: the generation timestamp, and the four
millisecond columns of the scalability table. **Every verdict column is byte-identical.**

---

## Root cause of the null delta

The fix changes the fake/real classification of expressions in which a cancellation
pattern occurs as a **substring but not as a sub-term** — e.g. `~r0 - r0`, `r0 ^ r0 + 1`,
`r0 & ~r0 + 1`.

No such expression occurs in any evaluated corpus:

- **C dialect.** The corpus scan (`eval/research/a8_corpus_scan.py`) found **0**
  occurrences across `eval/examples`, `eval/corpus` and `eval/mined/litmus`
  (29,202 `.litmus` files, 1,740 distinct value expressions).
- **Assembly dialects.** The fix is provably almost inert there: `lexRegisterAsm` builds
  arith expressions as `ops[1] + opSym(op) + ops[2]` — a **flat two-operand string** — and
  `opSym` never emits `~`. A pattern match in such a string is necessarily the whole
  expression, hence always a genuine sub-term. Exhaustive check over the asm expression
  shape (`eval/research/a10_interaction.py`, 588 `(expr, reg)` pairs): folding differs in
  **4** cases, all of the form `#<imm> OP <reg>` — an immediate in the *first* source
  operand, which is not valid AArch64/PPC/RISC-V syntax and cannot be produced from
  well-formed assembly.

Since the bulk of the validation corpora (`herdtools7`, `Dat3M`) are assembly, and the C
examples contain no precedence-spanning idiom, the fix cannot move any published number.
**The defect was latent, and the delta confirms it.**

---

## Affected paper tables

**None.** Table 4's millisecond columns move by ±3 ms — ordinary run-to-run variance on
the same binary, not attributable to the fix (the same variance appears between any two
runs). Every verdict, count, ratio-of-counts and agreement figure in Tables 1–4 is
identical.

## Does any conclusion change?

**No.** In particular:

- the §6 fake-dependency contribution is unaffected — the detector's classification of
  every corpus expression is identical (`detector edge census` unchanged at
  301 fake / 17 semantic / 5 verdict-relevant);
- `\PublishedExampleAgreement = 31/34` is unchanged, as are all three per-source splits;
- the QF_BV-vs-solver-free equivalence claim (`0/2972 classification changes`) still
  holds — see the caveat in the soundness section of the engineering summary.
