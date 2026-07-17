# Test provenance — the honest count

This ledger records where every encoded litmus test actually comes from, and draws
the one distinction that matters for any validation claim: **independently authored**
(written by third parties, for their own purposes) vs **published examples used as
fixtures** (canonical examples lifted from papers — useful, but not independent of
this project's own selection).

## The real number

**36 encoded tests = 2 independently authored + 34 published examples.**

We do **not** have ~30 independent compiler-derived benchmarks. The independently
authored count is **2** — the two discriminating tests found in the herd7 / Dat3M
corpus. Everything else (34) is a published example, hand-authored in the memory-model
literature and selected by us as a fixture.

| provenance class | count | independent of this work? |
|---|:--:|:--:|
| Independently authored (herd7 / Dat3M corpus) | **2** | ✅ yes |
| Published examples (Weakestmo + PwT/Leaky-Semicolon + JMM) | **34** | ❌ no — canonical paper examples |
| **Total encoded** | **36** | |

> Separately, the parser/encoder is exercised over **2 998** independently-parsed
> real corpus tests for the hierarchy-soundness check (0 violations, see
> `docs/corpus-validation-findings.md`). That is a *parsing/soundness* sweep, not a
> set of encoded discriminating benchmarks — it is not part of the 36 and does not
> change the "2 independent" figure.

## Independently authored — 2 (verbatim from third-party corpora)

Both are fake-dependency-carrying PowerPC load-buffering tests where WEAKEST's
semantic-dependency verdict discriminates (`semanticEdges = 0` ⇒ ALLOWED, vs
FORBIDDEN if every syntactic dep were treated as semantic). See
`sdep-discriminating-tests.csv`.

| test | corpus path | origin |
|---|---|---|
| LB+datas | `eval/corpus/herdtools7/catalogue/herding-cats/ppc/tests/illustrative/LB+datas.litmus` | herdtools7 (Alglave, Maranget, Tautschnig, "Herding Cats", TOPLAS 2014) |
| DETOUR0236 | `eval/corpus/Dat3M/litmus/PPC/DETOUR0236.litmus` | Dat3M PowerPC litmus suite (diy-generated) |

These were authored by the herd7 / Dat3M maintainers for hardware/model testing, with
no connection to this project — the only genuinely independent evidence in the set.

## Published examples — 34 (paper examples used as fixtures)

Taken verbatim from the cited papers; no expressions invented. Reproduced here as a
name+citation index only (the test bodies live in the repo).

### Source 1 — PwT / "The Leaky Semicolon" — 6
`eval/examples/leaky-semicolon/`: **LB, LBd, LBcd, LBfc, LBfd, LBxor**
Jeffrey, Riely, Batty, Cooksey, Kang, Vafeiadis, "The Leaky Semicolon", POPL 2022.

### Source 2 — Weakestmo — 8
`eval/examples/weakestmo/`: **CoRR, CoRW, CoWR, LB, LB+ctrl, LBd, MP, OOTA**
Chakraborty & Vafeiadis, "Grounding Thin-Air Reads with Event Structures", POPL 2019.

### Source 3 — JMM causality test suite — 20
`eval/examples/jmm/`: **TC01 … TC20**
Java Memory Model causality test cases (Pugh et al.).

## Caveat on "published examples"

The Weakestmo and PwT examples are the motivating examples of the very models this
tool implements (Vafeiadis et al.), so agreeing with them is a **consistency** check,
not independent validation. The JMM suite is a community-standard causality benchmark
but is likewise hand-authored, not compiler/hardware-derived. Only the 2 corpus tests
above are independent in the strong sense.

_No `.tex` files were modified; no new tests were invented for this ledger._
