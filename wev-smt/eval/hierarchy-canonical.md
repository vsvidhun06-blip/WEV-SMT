# Canonical model hierarchy — empirical analysis

Does the chain **SC ⊇ TSO ⊇ PSO_CANONICAL ⊇ RA ⊇ WEAKEST** hold?
Determined empirically (not assumed) over the 40-test `LitmusCorpus.classics()`
atlas, run under six models.

- Verdict matrix: [`atlas-canonical.csv`](atlas-canonical.csv)
- All 30 ordered-pair inclusions: [`model-inclusion-matrix.csv`](model-inclusion-matrix.csv)
- Per-witness detail: [`hierarchy-witnesses.md`](hierarchy-witnesses.md)

**Models.** `SC`, `TSO`, `PSO′` (our PSO — `ppo ∪ rfe ∪ co ∪ fr` acyclic **plus**
the RC11 release/acquire term `irreflexive(hb ; eco?)`), `PSO_CANONICAL` (standard
SPARC PSO = PSO′ **without** that term, `AxiomaticConsistency.consistencyPSOCanonical`),
`RA`, `WEAKEST`.

**Convention.** `A ⊇ B` ≙ *A at least as strong as B* ≙ `forbidden(A) ⊇ forbidden(B)`
≙ `allowed(A) ⊆ allowed(B)`. A link **fails** at a test **A allows but B forbids**.

## Bottom line

**The chain does NOT hold.** Two of its four links fail:

| link | result | witnesses |
|---|:--:|---|
| SC ⊇ TSO | ✅ holds | — |
| TSO ⊇ PSO_CANONICAL | ✅ holds | — |
| **PSO_CANONICAL ⊇ RA** | ❌ **fails** | CO-MP, MP-relacq |
| **RA ⊇ WEAKEST** | ❌ **fails** | LBdep-real, LBdep-addr, 3.LBdep-real |

Both failures are **incomparabilities** (the reverse inclusion also fails): the only
two unordered pairs in the whole lattice are `PSO_CANONICAL ⋈ RA` and `RA ⋈ WEAKEST`.
RA — a synchronization (release/acquire) model — does not lie on a total order
between the `ppo`-based hardware models and the dependency-based WEAKEST.

## Confirmed inclusions

Verified true over all 40 tests (from `model-inclusion-matrix.csv`):

- **SC ⊇ everything.** SC forbids all 40 candidates (they are all SC-inconsistent by
  construction), so `allowed(SC)=∅ ⊆ allowed(M)` for every M — trivially the strongest.
- **TSO ⊇ PSO_CANONICAL** (strict: PSO_CANONICAL adds MP, ISA2, S, 2+2W, CO-MP, … by
  relaxing `W→R`/`W→W`).
- **TSO ⊇ PSO′ ⊇ RA**, **TSO ⊇ WEAKEST**, **PSO′ ⊇ WEAKEST**, **PSO_CANONICAL ⊇
  WEAKEST**. (Every hardware model here is stronger than WEAKEST on this atlas.)
- The chain **SC ⊇ TSO ⊇ PSO′ ⊇ RA** holds — see the PSO′/PSO_CANONICAL comparison.

The partial order is therefore a **lattice, not a chain**: `SC` on top, `WEAKEST`
near the bottom, with `RA` off to the side, incomparable to both `PSO_CANONICAL` and
`WEAKEST`.

## Failed inclusions — explanation of all violations

### PSO_CANONICAL ⊇ RA — fails on CO-MP, MP-relacq

Both are release-store / acquire-load message passing with the stale-data outcome
`flag=1 ∧ data=0`.

- **RA forbids** them through `irreflexive(hb ; eco?)`: the release write read by the
  acquire load is a `sw` edge, so `hb` reaches the stale read, and the stale read's
  `fr` back to the writer's data store closes an `hb ; eco` cycle.
- **PSO_CANONICAL allows** them: it has **no** release/acquire axiom (that is precisely
  the conjunct dropped from PSO′), and its `ppo` relaxes same-thread `W→W`, so the
  writer's `data→flag` order is not preserved.

Canonical SPARC PSO models no synchronization, so it cannot forbid what RA forbids
*via* synchronization. (Conversely RA allows LB/IRIW that PSO_CANONICAL's `ppo`
forbids ⇒ incomparable.)

### RA ⊇ WEAKEST — fails on LBdep-real, LBdep-addr, 3.LBdep-real

All are load buffering closed by a **genuine (semantic) dependency**.

- **WEAKEST forbids** them through its no-thin-air axiom `acyclic(sdep ∪ jf)`: the
  real data/address dependencies contribute `sdep` edges producer→consumer which,
  together with `jf ≡ rf`, close a cycle.
- **RA allows** them: RA has **no** thin-air/dependency axiom — dependencies are not
  in its `hb`, so the `po ∪ rf` LB cycle is not an `hb ; eco` cycle.

WEAKEST is the only model here that reasons about dependencies. (Conversely WEAKEST
allows CO-MP, CO-WRC, MP+lwsync, LB+ctrlfence — which it treats by `jfCoherence`
alone, ignoring fences and release/acquire — while RA forbids them ⇒ incomparable.)

Full event structures, `rf`/`co`/`po` and the exact cyclic edge sets for every
witness are in [`hierarchy-witnesses.md`](hierarchy-witnesses.md).

## PSO′ vs PSO_CANONICAL

The two differ on **exactly two** of the 40 tests — **CO-MP** and **MP-relacq**:

| test | PSO′ | PSO_CANONICAL |
|---|:--:|:--:|
| CO-MP | FORBIDDEN | ALLOWED |
| MP-relacq | FORBIDDEN | ALLOWED |
| (all other 38) | identical | identical |

- `PSO′ ⊇ PSO_CANONICAL` **holds**; `PSO_CANONICAL ⊇ PSO′` **fails** (CO-MP, MP-relacq).
  So **PSO′ is strictly stronger** than canonical SPARC PSO — it forbids the two
  release/acquire message-passing tests that canonical PSO allows. The sole cause is
  PSO′'s extra `irreflexive(hb ; eco?)` conjunct (added for release/acquire coherence,
  Pass 2c); canonical SPARC PSO has no synchronization axiom.

- **Consequence for the hierarchy.** With **PSO′** in the chain the inclusion
  `PSO′ ⊇ RA` **holds** (PSO′'s release/acquire term matches RA's, so everything PSO′
  allows RA also allows), and the chain `SC ⊇ TSO ⊇ PSO′ ⊇ RA ⊇ WEAKEST` fails at
  **only one** link (`RA ⊇ WEAKEST`). Substituting the *canonical* PSO breaks a
  **second** link (`PSO_CANONICAL ⊇ RA`), because canonical PSO — lacking the
  release/acquire axiom — allows CO-MP and MP-relacq that RA forbids. In other words,
  it is precisely PSO′'s non-canonical release/acquire strengthening that lets PSO′
  sit on the chain above RA, whereas true SPARC PSO is incomparable with RA.

## How this was produced

`wev.smt.cli.AtlasCanonical` runs `consistencySC/TSO/PSO/PSOCanonical/RA/WEAKEST`
on each classics candidate and writes `atlas-canonical.csv`; a short script derives
the 30-pair `model-inclusion-matrix.csv` (`inclusion_holds` = no test where
`model_a` ALLOWS but `model_b` FORBIDS). No `.tex` files were modified.
