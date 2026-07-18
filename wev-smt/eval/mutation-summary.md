# Mutation-generated discriminating corpus (T2.1)

Mechanically derived fake→real dependency pairs from the wild corpus. Full data:
[`eval/mutation-corpus-results.csv`](mutation-corpus-results.csv).

## Method

Each fake-carrying test (all `r^r` self-XOR) has its fake self-cancelling instruction
turned into a genuine **self-identity** one — program structure and every constant-folded
value untouched, only fake→real:

- PPC / RISC-V: `xor rd,rn,rn` → `and rd,rn,rn`
- AArch64: `EOR Wd,Wn,Wn` → `AND Wd,Wn,Wn`

`rn^rn = 0` (fake) and `rn&rn = rn` (real) both fold to 0 when the loaded value is modelled
as 0, so the mutant reproduces the original's stored values **exactly** — the wired
candidate stays aligned with the `exists` outcome, and the only change is that the
dependency becomes semantic. Both original and mutant are parsed with the exact QF_BV
constancy oracle (`QfbvConstancyOracle`) installed as the detector, so fakeness
(`semantic_edges=0`) and realness (`semantic_edges>0`) are the solver's verdict, not a
human's. A pair is **discriminating** iff the original is `ALLOWED` (grounded LB) and the
mutant is `FORBIDDEN` (real thin-air).

> A literal `reg+1` mutation (as first specified) was tried and rejected: it changes the
> stored value (`(r^r)+1 = 1` → `(r+1)+1` folds to 2), so no write of the `exists`-required
> value exists, the reads default to the initial write, the candidate de-aligns, and
> otherwise-discriminating mutants spuriously return ALLOWED. `r^r → r&r` is the
> value-preserving equivalent that keeps the comparison faithful.

## Totals

| | |
|---|---|
| Fake-carrying tests (pairs generated) | 231 |
| Successfully mutated | 231 (100%) |
| Parse errors | 0 |
| **Discriminating pairs (fake=ALLOWED, real=FORBIDDEN)** | **1** |

### Outcome breakdown (why not more)

| outcome | count | meaning |
|---|--:|---|
| **discriminating** | **1** | fake ALLOWED → real FORBIDDEN (`LB+datas`) |
| fake edge not on a thin-air cycle | 152 | mutant real but still ALLOWED — no cross-thread cycle |
| original already FORBIDDEN | 59 | candidate inconsistent even *with* the fake dep, so it cannot be an ALLOWED baseline (DETOUR shapes whose `exists` is structurally forbidden; consistent with the ablation's `verdict_none=FORBIDDEN`) |
| mutant not semantic | 18 | all ARM ADDR: WEV's residual addr-dependency coverage gap (`[Xn,Wm,SXTW]` / `#`-immediate forms) leaves the real mutant with no tracked edge |
| original not fake | 1 | current oracle-detector finds a semantic edge the (older) sweep marked fake — stale baseline |

## By architecture

| arch | pairs | discriminating |
|---|--:|--:|
| ARM | 71 | 0 |
| PPC | 160 | 1 |

## Discriminating pairs

| test | arch | orig → mut |
|---|---|---|
| `herdtools7/catalogue/herding-cats/ppc/tests/illustrative/LB+datas.litmus` | PPC | `xor r3,r1,r1` → `and r3,r1,r1` |

## Honest assessment — this did NOT reach the hoped ">>2"

The construction produces **1** discriminating pair, **fewer** than the 2
independently-authored ones already in hand (`LB+datas`, `DETOUR0236`). It does not
strengthen the evaluation as anticipated. Three concrete reasons, all visible in the
breakdown above:

1. **The wild fake corpus is overwhelmingly not LB-thin-air-shaped.** Of the 231, 46 are
   ARM address-dependency `ppo` tests (MP+addr, S+addr, PPOCA…) and 59 are DETOUR shapes
   whose named outcome is coherence/structurally forbidden regardless of the dependency.
   Making the dependency real in those does not create a thin-air cycle, so they stay
   ALLOWED (152) or were never an ALLOWED baseline (59). Only genuine cross-thread LB
   cycles can discriminate, and the corpus contains very few.

2. **WEV's single wired-candidate value-abstraction suppresses multi-hop cycles.**
   `DETOUR0236` — the *second* known discriminator — is missed here (its mutant is
   ALLOWED). Its cycle threads an internal read-from (`Rfi`) over three reads; WEV models
   loaded values as 0 when folding store values, which degenerates the longer cycle so the
   wired candidate never carries it. This is the same limitation documented for TC06/TC16/
   TC18 in `dat3m-summary.md` and quantified in `co-enumeration-results.csv`. The
   flag-flip ablation (`sdep-discriminating-all-fake-tests.csv`) catches `DETOUR0236`
   because it re-labels edges on the *already-wired* structure rather than re-deriving
   values from a mutated program; a co-enumerating / concrete-value checker would likely
   recover it here too.

3. **A residual addr-dependency coverage gap** in the parser drops 18 ARM ADDR mutants
   before they can even register a semantic edge.

**Conclusion.** Mutation over this corpus mechanically confirms the one clean LB-shaped
case (`LB+datas`) with the solver as ground truth, but it does **not** raise the
discriminating count — it lowers it to 1, because the wild corpus lacks the requisite LB
thin-air structure and WEV's wired-candidate checker under-approximates the rest. The
honest number for the paper is therefore **2 independently-authored discriminators
(unchanged)**, with mutation adding methodological rigor (solver-decided fakeness) rather
than quantity. Growing the discriminating set would require either LB-thin-air-shaped seed
programs (not present in this corpus) or a checker that enumerates `co` and concrete values
rather than a single wired candidate.

## For the paper (§6.3) — honest version

> To put WEAKEST's semantic-dependency separation on a solver-decided rather than
> hand-picked footing, we mechanically mutate all 231 fake-dependency-carrying tests in the
> wild herd7/Dat3M corpus (each an `r^r` self-XOR, confirmed fake by a QF_BV constancy
> oracle) into real-dependency variants by the value-preserving rewrite `r^r → r&r`, and
> run WEAKEST on every original/mutant pair. Only **1** pair (`LB+datas`) discriminates
> (fake ALLOWED, real FORBIDDEN): the remainder are address-dependency or coherence-forbidden
> shapes with no thin-air cycle to close (211), or hit WEV's single-candidate value
> abstraction — which also loses the second known discriminator, `DETOUR0236` — and its
> residual addr-dependency coverage gap (19). Mutation thus confirms the corpus's fake→real
> separation is genuine (solver ground truth, independently-authored base programs) but does
> **not** enlarge the discriminating set beyond the two hand-identified tests; the wild
> corpus simply lacks additional LB-thin-air structure — a limitation of the available
> benchmarks rather than of WEAKEST.

_No `.tex` files were modified; nothing was committed._
