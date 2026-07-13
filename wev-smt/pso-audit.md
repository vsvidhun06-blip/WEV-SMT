# PSO encoding audit

*Scope: `src/main/java/wev/smt/AxiomaticConsistency.java` `consistencyPSO(...)`. No code
was modified to produce this audit. Ground-truth verdicts below were obtained by running
the existing read-only `wev.smt.proof.HierarchyProbe` against the current encoding.*

## TL;DR — verdict

The implemented PSO **is strictly stronger than canonical SPARC PSO**. The strengthening
is a single extra conjunct — the RC11 release/acquire coherence axiom
`irreflexive(hb ; eco?)` (`irreflexiveHbEco`) — added to PSO but *not* to TSO.

**This strengthening is intentional and load-bearing, not a bug.** It is the entire basis
of the paper's **Proposition 4.4 (`prop:tsopso`): "TSO ⋢ PSO … the two are incomparable"**,
its witness `WR-relacq-cex`, and the "four mutually incomparable weak models" lattice
(`fig:lattice`). It is also frozen as ground truth in the test corpus
(`LitmusCorpus.MP-relacq → PSO=FORBIDDEN`).

**Therefore the repair branch (steps 5–9 of the request) is NOT triggered.** Reverting PSO
to canonical SPARC PSO would falsify Proposition 4.4, empty the crossed TSO–PSO edge in
`fig:lattice`, break the `MP-relacq` corpus expectation and the `WR-relacq-cex` witness,
and change PSO verdicts in the atlas/witness artifacts. See §5 for the precise blast radius
should a canonical-PSO variant ever be *deliberately* wanted (it should be a new model, not
a change to `PSO`).

---

## 1. Current encoding vs. canonical SPARC PSO

`consistencyPSO` (lines 121–136) conjoins:

| # | Conjunct (code) | Relation enforced acyclic / irreflexive |
|---|---|---|
| a | `addPo(cs, layer, /*skipWR=*/true, /*skipWW=*/true, active)` | `ppo = po \ (W→R ∪ W→W)` same-thread, **fence/RMW-transparent** |
| b | `addRf(cs, layer, /*externalOnly=*/true, false, active)` | `rfe` (cross-thread reads-from) |
| c | `addCo(cs, layer, active)` | `co` (coherence, all locations, one global layer) |
| d | `addFr(cs, layer, active)` | `fr = rf⁻¹ ; co` (all locations) |
| e | `coherencePerLocation(active)` | SC-per-location: `acyclic(po-loc ∪ rf ∪ co ∪ fr)` per L |
| f | `rmwAtomicity(active)` | `irreflexive(rf ; co)` restricted to RMW |
| **g** | **`irreflexiveHbEco(active)`** | **`irreflexive(hb ; eco?)`, hb = (po ∪ sw)⁺, sw = release-W —rf→ acquire-R** |

Conjuncts **a–f are exactly canonical SPARC PSO** (SPARC v9 §D; Alglave–Maranget–Tautschnig,
*Herding Cats*, TOPLAS 2014, §4 & §6.1):

- **ppo (a).** Canonical PSO preserves `R→R, R→W` and drops `W→R, W→W`. `skipWR=true`,
  `skipWW=true` drops exactly those two. Fence restoration (`MEMBAR #StoreStore` etc.)
  and RMW-as-fence are handled by `restoresOrder`/`fenceOrders`; with no fences/RMWs this
  reduces to the adjacent po-edge encoding. ✔ canonical.
- **ghb = ppo ∪ rfe ∪ co ∪ fr (a–d).** Internal `rf` (store-forwarding) is excluded
  (`externalOnly=true`), which is correct for a store-buffered TSO/PSO family. ✔ canonical.
- **uniproc / SC-per-location (e)** and **RMW atomicity (f)** are the standard side
  conditions every hardware model carries. ✔ canonical.

Conjunct **g has no analogue in SPARC PSO.** `sw` (synchronises-with from a *release* store
to an *acquire* load) and `hb` are C11/RC11 constructs (Lahav–Vafeiadis–Kang–Hur–Dreyer,
*Repairing SC*, PLDI 2017, §3). SPARC PSO has no release/acquire access modes; ordering
between two stores is obtained **only** by an explicit `MEMBAR #StoreStore`, never by the
consumer's access mode. So conjunct **g is a strict addition** on top of canonical PSO.

### What canonical PSO would decide that the current code does not

The separating shape is **message-passing with a release store read by an acquire load,
and no store-store fence in the writer** (`MP-relacq`):

```
T0:  Wx = 1 (rlx)          T1:  r1 = Ry (acq)
     Wy = 1 (rel)               r2 = Rx (rlx)
     exists (r1=1 ∧ r2=0)
```

- **Canonical SPARC PSO:** `Wx→Wy` is a `W→W` pair with no `#StoreStore` MEMBAR ⇒ **not**
  in `ppo` ⇒ the stores may commit out of order ⇒ **ALLOWED**.
- **Current code:** the `rel`→`acq` handshake makes `sw(Wy,Ry)`, so
  `hb(Wx,Ry)`; with `fr(Rx,Wx)` that is `hb ; eco` (one `eco` step) ⇒ conjunct **g** fires
  ⇒ **FORBIDDEN**.

Confirmed live (HierarchyProbe): `MP-rel` PSO=allow, `MP-acq` PSO=allow, **`MP-relacq`
PSO=FORBID**. Only the release+acquire combination trips it — precisely the `hb;eco`
signature.

---

## 2. Every ordering edge retained by the current PSO implementation

For a straight-line litmus thread (the corpus shape), PSO retains, into its acyclicity
check, exactly the edge set below. "Retained" = an implication
`active(a,b) ⇒ layer(a) < layer(b)` (or an irreflexivity clause) is emitted.

**Global-layer edges (`freshLayer("pso")`), acyclic together:**

1. `ppo`: every same-thread ordered access pair `(a,b)`, `a` po-before `b`, **except**
   `W→R` and `W→W` pairs — unless restored by (i) a crossed fence whose kind orders the
   `type(a)→type(b)` direction (`fenceOrders`: FULL any; ACQ if `a` readish; REL if `b`
   writish; ACQ_REL = R→* ∪ *→W), or (ii) a `RMWEvent.isFullFence()` / FULL fence at either
   endpoint (`actsAsFullFence`). Retained `W→W` restored by `#StoreStore`/FULL/REL fence or
   RMW; retained `W→R` restored by FULL fence or RMW.
2. `rfe`: every cross-thread `rf(w,r)`.
3. `co`: every `co(a,b)`.
4. `fr`: every `fr(r,w')` from `rf(w,r) ∧ co(w,w')`, `w'≠r`.

**Per-location-layer edges (`coherencePerLocation`, one layer family per location L):**

5. `po-loc(L)`: same-location, same-thread po pairs (**including** `W→R` and `W→W` that
   ppo dropped — this is what keeps coherence per location intact under PSO).
6. `rf(L)`, `co(L)`, `fr(L)`: the communication edges restricted to L.

**RMW atomicity (`rmwAtomicity`):** for each RMW `u` reading `w`, forbids `rf(w,u) ∧
co(w,w') ∧ co(w',u)` — no store interposed in coherence between the read-from write and the
RMW.

**Release/acquire coherence (`irreflexiveHbEco`) — the extra layer (§3):**

7. `hb` base: `po⁺` (intra-thread reachability) ∪ `sw` where `sw = rf(w,r)` with
   `isReleaseLike(w) ∧ isAcquireLike(r)`; closed transitively.
8. `eco` base: `rf ∪ co ∪ (fr = rf⁻¹;co)`; closed transitively.
9. Irreflexivity: `¬hb(x,x)` **and** `¬∃z. hb(x,z) ∧ eco(z,x)` — i.e. `irreflexive(hb;eco?)`.

`isReleaseLike` = RELEASE/SC store, or a store with a REL/ACQ_REL/FULL fence po-before it.
`isAcquireLike` = ACQUIRE/SC load, or a load with an ACQ/ACQ_REL/FULL fence po-after it.

---

## 3. Precisely which retained edges are stronger than canonical PSO

**Edges 1–6 (ppo, rfe, co, fr, per-location coherence) and the RMW atomicity clause are
canonical.** They neither add nor remove orderings relative to SPARC PSO.

**The strengthening is entirely edges 7–9 — the `irreflexive(hb ; eco?)` conjunct.**
Concretely the *extra* forbidding power over canonical PSO comes from the **`sw` base edge
(edge 7)**: a `release`-store read by an `acquire`-load contributes `hb(w,r)` that canonical
PSO does not have (canonical PSO would need a `#StoreStore` MEMBAR to order the writer's
stores). Every execution the current PSO forbids-but-canonical-PSO-allows is one whose
forbidding cycle *requires* at least one `sw` edge in its `hb` segment.

Notes bounding the strengthening:

- **No fence-free, mode-free (all-relaxed) test is affected.** Without a release/acquire
  pair there is no `sw`, `hb` reduces to `po⁺`, and `hb;eco?` is already implied by
  `ppo ∪ com` + per-location coherence. So classic SPARC tests (`2+2W`, `3.2W`, `MP`, `SB`,
  `R`, `S`, `LB`, `IRIW` in their relaxed forms) are decided identically. Verified:
  `2+2W`/`3.2W`/`MP-rel` all PSO=allow, matching canonical.
- **The `hb;eco?` shape forbids at most one `eco` segment.** Multi-`eco` cycles (`SB`, `R`,
  `IRIW` under non-multicopy-atomic RA) stay allowed, so the strengthening does **not**
  collapse PSO toward SC. Verified: `3.SB`/`6.SB` PSO=allow.
- The strengthening bites exactly on **synchronised store-buffering / message-passing**:
  `MP-relacq` and the synthetic `WR-relacq-cex`. Both are PSO=FORBID, TSO=allow.

---

## 4. Everything that depends on the current (strengthened) PSO behavior

### 4.1 Hierarchy proof / probe infrastructure

- **`src/main/java/wev/smt/proof/HierarchyProbe.java`.** `buildWRrelacqCex()` is a
  hand-built execution (`T1: Wx=1; Ra; Wrel b=1`, `T2: Racq b; Wx=2`, co ending x=1). Its
  docstring states the intent verbatim: *"the release/acquire b-handshake puts hb(Wx,Wx2);
  with co(Wx2,Wx) that is an hb;eco coherence cycle PSO/RA forbid. TSO drops the same-thread
  W→R … TSO should ALLOW it."* Live result: **`WR-relacq-cex` — TSO=allow, PSO=FORBID →
  "VIOLATION of TSO ⊒ PSO"`**. This probe exists *specifically to exhibit* the strengthening.

### 4.2 Witnesses

- **`src/main/java/wev/smt/MinimalWitnessExtractor.java`** drives its sub-execution search
  through `gatedConsistency(m)` → the gated `consistencyPSO`, so every PSO minimal witness
  inherits conjunct **g**. PSO witnesses over release/acquire executions (e.g. the
  `forbiddenBy=PSO` rows in the atlas) depend on it.
- **`eval/atlas-separations.csv`** records PSO as a separator: `MP,PSO,TSO` (PSO allows, TSO
  forbids — canonical direction) *and* the family where PSO forbids while RA allows
  (`LB,RA,PSO`, `IRIW,RA,PSO`, `WRC,RA,PSO`). The paper's `WR-relacq-cex` supplies the other
  direction (PSO forbids, TSO allows).

### 4.3 Proposition (paper §4.7 "Hierarchy preservation", Option C — LOCKED)

- **`prop:tsopso` (Proposition 4.4):** *"TSO ⋢ PSO (witness `WR-relacq-cex`); the two are
  incomparable."* — **directly and solely enabled by conjunct g.**
- **`thm:scdom` (Theorem 4.3):** SC ⊒ {TSO, PSO, RA, WEAKEST} by construction — unaffected
  (SC still dominates the strengthened PSO; adding conjuncts to PSO only helps SC-domination).
- **`fig:lattice`:** SC over "four *mutually incomparable* weak models", with the TSO–PSO
  edge drawn dotted-and-crossed and labelled `Prop. 4.4`. Removing conjunct g would remove
  the cross (TSO would dominate PSO), contradicting the figure and the caption "There are no
  arrows between weak models."
- *(Sibling `prop:rawk` / Proposition 4.5, RA ⋢ WEAKEST, witness `LBdep-real`, is the
  analogous refuted edge on the RA side — confirmed live: 3 RA⊒WEAKEST violations in
  classics. Independent of PSO, listed for lattice context.)*
- Note on numbering: the request's "Proposition 2" corresponds to this repo's **Proposition
  4.4** — the only proposition about a TSO/PSO relationship. There is no separately-numbered
  "Prop 2" in `paper/wev-smt.tex`.

### 4.4 Evaluation tables / expected artifacts

- **`LitmusCorpus.java` (test ground truth):** `MP-relacq → exp(F,F,F,F,U)` locks
  **PSO=FORBIDDEN**; `MP-rel → exp(F,F,A,A,A)` and `2+2W`/`3.2W → exp(F,F,A,A,A)` lock
  **PSO=ALLOWED**. This pair of expectations *is* the incomparability, frozen into the
  corpus. `2+2W+sync → exp(F,F,F,A,A)` locks the canonical fence-restores-W→W behavior.
- **`src/test/java/wev/smt/AxiomaticConsistencyTest.java`** exercises `consistencyPSO`
  across the grid (asserts SC hard; prints the rest).
- **`eval/consistency-validation.csv`**, **`eval/atlas-separations.csv`**,
  **`eval/scalability-consistency.csv`**, **`eval/scalability-fences*.csv`**,
  **`eval/corpus-validation-*.csv`** carry per-file/per-family PSO verdicts.
- **`artifact/expected-outputs/atlas-expected.txt`**, **`…/hierarchy-soundness-expected.txt`**,
  **`…/scalability-consistency-expected.csv`**, **`…/scalability-fences-expected.csv`** are
  frozen snapshots including PSO columns.
- **`artifact/reproduce.sh`** (lines 135–155) recomputes hierarchy-soundness from the fresh
  CSV, flagging `if (tso>pso) ok=0` — i.e. it *checks for exactly the TSO-allows/PSO-forbids
  violation* and asserts the count is **0 across 2,998 files**. This is the empirical
  counterpart to Prop 4.4: the models are incomparable *in principle* (synthetic
  `WR-relacq-cex`), yet on the real herd7+Dat3M corpus the separating pattern never occurs,
  so the practical chain `SC ⊆ TSO ⊆ PSO` holds with 0 violations (paper §6.2).

### 4.5 Documentation / in-code rationale

- `AxiomaticConsistency` class Javadoc (lines 51–58) documents conjunct g as **"Pass 2c"**,
  explaining it forbids CO-MP/MP-relacq while keeping ordinary MP/S/2+2W allowed.
- Git history: `7d75184` "day 6: pass 2a/2b-min/2c done"; `dc8f4a4` "…RA⊆WEAKEST *non-theorem*
  also held empirically…" — the team explicitly frames these intermediate containments as
  non-theorems that hold empirically. `docs/pass-3-plan.md` references the pass structure.

---

## 5. Conclusion and recommendation

**The audit does NOT confirm an unintentional strengthening.** The single non-canonical
conjunct (`irreflexive(hb;eco?)`) is deliberate, documented ("Pass 2c"), verified live, and
depended upon by:

- Proposition 4.4 and its `WR-relacq-cex` witness,
- the "four mutually incomparable weak models" lattice figure,
- the `MP-relacq` PSO=FORBIDDEN ground-truth expectation in `LitmusCorpus`,
- the `HierarchyProbe` violation demonstration,
- the PSO witness rows in `atlas-separations.csv`.

Per the request's guard — *"Only if the audit confirms the implementation is unintentionally
stronger than canonical PSO"* — **steps 5–9 (repair PSO, re-run hierarchy/eval, re-find a
TSO⊋PSO witness, report changes) are intentionally NOT performed.** No code has been or
should be changed on the strength of this audit.

### If a canonical-SPARC-PSO model is ever wanted (do this as a NEW model, not a PSO edit)

Should the project decide it *also* wants a textbook SPARC PSO (e.g. to state a genuine
`TSO ⊒ PSO_canonical` theorem), the minimal, non-destructive change is to add a sibling
`consistencyPSO_SPARC(...)` identical to `consistencyPSO` **minus** the
`cs.add(irreflexiveHbEco(active))` line, and register it as a distinct `MemoryModel`. The
expected deltas for that new model (from the live probe) would be:

- **Verdict changes:** `MP-relacq` and `WR-relacq-cex` flip PSO_canonical **FORBID → allow**;
  all all-relaxed/fenced classics unchanged (`2+2W`, `3.2W`, `MP-rel`, `MP-acq`, `SB`, `LB`,
  `IRIW`, `2+2W+sync`, … identical).
- **Hierarchy change:** `TSO ⊒ PSO_canonical` would then hold (no `WR-relacq-cex` violation),
  making PSO_canonical a genuine weakening of TSO — i.e. it would sit *below* TSO in the
  lattice rather than beside it.
- **Proposition/paper change:** Prop 4.4 stays true of `PSO` (the RC11-flavoured model);
  a *new* by-construction edge `TSO ⊒ PSO_canonical` could be added. `fig:lattice`,
  §4.7 prose, and §6.2 would need a sentence distinguishing the two PSO variants. Corpus
  expectations for `PSO_canonical` (`MP-relacq → A`) would be new rows, leaving the existing
  `PSO` expectations intact.

Because that is an *additive feature*, not a correction, it must not be applied silently to
the existing `PSO`, which the paper and corpus have deliberately defined as the
release/acquire-aware, TSO-incomparable model.
