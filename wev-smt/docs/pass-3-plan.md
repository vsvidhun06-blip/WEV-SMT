# Pass 3 plan — dependency-aware WEAKEST consistency (corrected)

**Status:** plan only, no code. Supersedes the original "Stage 2: jf-coherence in
`consistencyWEAKEST` only" prompt, which is **infeasible as written** (proven
below). Builds on Stage 1 (commit `754de74`): the `DependencyInfo` sidecar, the
inert `dep_*` encoder vars, and the three `LBdep-*` corpus cases.

## 0. Why the original Stage-2 plan cannot work

The original prompt was "add a `jfCoherence` axiom to `consistencyWEAKEST()` only;
do not touch well-formedness." A probe (run 2026-05-22, then deleted) checked
`encodeWellFormedness()` **alone**, with no consistency axiom:

```
case         | WF-alone   | WF & WEAKEST
LB           | UNSAT      | UNSAT
3.LB         | UNSAT      | UNSAT
LB-fake-dep  | UNSAT      | UNSAT
OOTA-cycle   | UNSAT      | UNSAT
LBdep-fake   | UNSAT      | UNSAT   ← headline case
LBdep-real   | UNSAT      | UNSAT
LBdep-addr   | UNSAT      | UNSAT
```

The whole LB family is **UNSAT at well-formedness**, before any model axiom runs.
Cause: `lbSkeleton()` wires the cyclic reads (`r1←wx@1`, `r2←wy@1`), so the global
`pos` order must satisfy

```
r1 < wy   (po, R→W)
wy < r2   (rf-forward: pos(w) < pos(r), w=wy, r=r2)
r2 < wx   (po, R→W)
wx < r1   (rf-forward: w=wx, r=r1)
```

i.e. `r1 < wy < r2 < wx < r1` — a cycle, so no total order `pos` exists. This
matches `encoding-rootcause-analysis.md` §2 (rows 2,3,13,14,16) and §4: the LB
family is **well-formedness-locked by the rf-forward edge**, and the WEAKEST LB
cells are *double-locked* (WF + WEAKEST `acyclic(po∪rf)`).

A `jfCoherence` axiom only **adds** constraints; it cannot lift the WF lock. So
`LBdep-fake` would stay FORBIDDEN — tripping the prompt's own stop condition. The
fix therefore *must* relax well-formedness, and (because the no-dep "OOTA twins"
share `LB`'s exact structure) *must* also migrate the corpus expectations.

---

## 1. Well-formedness relaxation

### 1.1 What to drop, what to keep

Drop exactly **one** clause — the **global rf-forward edge** `rf(w,r) ⇒
pos(w) < pos(r)` — and nothing else.

**Keep** (unchanged):
- position domain (`0 ≤ pos < n`) and all-distinct positions;
- the relaxed `po` order (`relaxedPoConstraints` already drops cross-location
  `W→W`/`W→R` per Pass 2b-min; keep `R→W`, `R→R`, same-location `W→W`/`W→R`);
- the rf **value-match** clause `rf(w,r) ⇒ value(w)=value(r)`;
- the rf **exactly-one** clause (`or` of choices + pairwise mutual exclusion);
- the `co` biconditional `co(a,b) ⇔ pos(a)<pos(b)` and the pinned co-chain
  (`coherenceOrder`) consecutive constraints.

### 1.2 Why dropping the rf-forward edge is *principled*, not a hack

The genuinely-required well-formedness invariant is "each read observes a
same-location write of matching value, ordered consistently **per location**."
Per-location read-after-write is already enforced for **every** model by
`coherencePerLocation` (`addRfLoc`: `rf(w,r) ⇒ layer_loc(w) < layer_loc(r)`,
Pass 1). The *global* rf ordering is a **model choice**, not a well-formedness
fact: SC/TSO/PSO impose it inside their own acyclic layers (`addRf`), whereas the
bare release/acquire (RA) and Weakestmo (WEAKEST) models deliberately do **not**
(they use `irreflexive(hb;eco?)` and jf-coherence respectively). Putting global
rf ordering in well-formedness wrongly forced an SC-strength backstop on **all**
models — exactly the over-approximation `encoding-rootcause-analysis.md` §1(b)
identifies (family b, "acyclic(po∪rf∪co) for all models").

**Coherence stays safe** because `coherencePerLocation` re-imposes
`acyclic(po-loc ∪ rf-loc ∪ co ∪ fr)`. Worked example — `CoRW` (same-thread read of a
po-later write): WF previously forbade it via `pos(w2)<pos(ra)` (rf-fwd) vs
`pos(ra)<pos(w2)` (po). After the drop, `coherencePerLocation` on `x` gives
`layer(ra)<layer(w2)` (po-loc) and `layer(w2)<layer(ra)` (rf-loc) → cycle → still
FORBIDDEN. (CoRR/CoWR/CoWW analogous; verified in §6.)

### 1.3 Files (identical change in both — they must relax in lockstep)

The two well-formedness encoders must stay byte-for-byte equivalent or the
validation atlas and the sub-execution search disagree about what is well-formed.

- **`EventStructureEncoder.encodeWellFormedness()`** — remove the implication
  ```java
  cs.add(bmgr.implication(rf,
          imgr.lessThan(eventVars.get(w), eventVars.get(r))));   // ← delete
  ```
  inside the per-read loop (currently `AxiomaticConsistency`-adjacent, the first
  of the two `implication(rf, …)` clauses). Keep the value-match clause that
  follows it.
- **`MinimalWitnessExtractor.gatedWellFormedness()`** — remove the matching
  ```java
  cs.add(bmgr.implication(
          bmgr.and(rfVar, bothActive(w, r)),
          imgr.lessThan(enc.getPosVar(w), enc.getPosVar(r))));    // ← delete
  ```
  Keep the value-match clause and the active-choice/exclusivity clauses.

No other edits to either method.

---

## 2. The `jfCoherence` axiom

### 2.1 Definition (Weakestmo no-thin-air)

Chakraborty & Vafeiadis, *Grounding Thin-Air Reads with Event Structures*, POPL
2019 (Proc. ACM PL 3, POPL, Art. 70), §3. The event-structure model carries a
**justified-from** relation `jf` (write → read) and syntactic **dependency**
relations (`data`/`addr`/`ctrl`). Consistency requires reads be *well-justified*:
a read's value must be justifiable through writes whose existence does **not**
transitively depend on that read. Operationally this is the acyclicity of the
combined relation

> **`acyclic( sdep ∪ jf )`**

where `sdep` is the union of **semantic** dependency edges (`data ∪ addr ∪ ctrl`,
restricted to `isSemantic = true`; §3.x of the paper — *confirm the exact
definition/equation number against the published PDF before camera-ready; cite as
the "well-justified / jf-acyclicity" condition of §3*). A cycle through
`sdep ∪ jf` is precisely a read justified by a write that (through dependencies)
needs that read — the thin-air pattern. Fake dependencies (e.g. `r ^ r`, the
identity) carry **no** semantic content, so they are excluded from `sdep` and
cannot close the cycle — which is why `LBdep-fake` is allowed and `LBdep-real`
is not.

### 2.2 Edge directions and polarity (verified)

We encode acyclicity with the integer-layer trick (the same "edge ⇒ strict layer
order" pattern as `coherencePerLocation`; a strict-`<` integer order cannot have a
cycle, so this is `irreflexive((sdep ∪ jf)⁺)` for free, no reachability matrix
needed). A fresh layer family `layer_jfco_e<id>` is minted per call.

- **jf edge** (write `w` justifies read `r`): `jf_w_r ⇒ layer(w) < layer(r)`
  — value **producer (w) before consumer (r)**.
- **semantic dep edge** stored consumer→producer in `DependencyInfo` (e.g.
  `addDataDep(wy, r1)` = "`wy`'s value depends on `r1`"): the *temporal/causal*
  edge runs **producer → consumer**, so `layer(producer) < layer(consumer)`,
  i.e. `layer(r1) < layer(wy)` — value **producer (read r1) before consumer
  (write wy)**.

Both edges read "value-source before value-sink," which is the consistent,
cycle-closing polarity.

> **The original prompt's stated `jf_w_r → layer(r) < layer(w)` is BACKWARDS** and
> does not close the LB cycle (it leaves `r1<wy, r2<wy, r1<wx, r2<wx`, satisfiable
> → real-dep wrongly ALLOWED). Use `layer(w) < layer(r)`.

**Cycle trace — `LBdep-real` (semantic data deps), must be UNSAT:**

```
jf  wx→r1 :  layer(wx) < layer(r1)
dep r1→wy :  layer(r1) < layer(wy)     (producer r1, consumer wy)
jf  wy→r2 :  layer(wy) < layer(r2)
dep r2→wx :  layer(r2) < layer(wx)     (producer r2, consumer wx)
⇒ layer(wx) < layer(r1) < layer(wy) < layer(r2) < layer(wx)   ✗ contradiction → FORBIDDEN ✓
```

**`LBdep-fake` (deps present but `isSemantic=false`), must be SAT:** `sdep = ∅`,
so only `layer(wx)<layer(r1)`, `layer(wy)<layer(r2)` remain — no cycle → ALLOWED ✓.

### 2.3 jf ↔ rf coupling (mandatory)

The encoder mints `jf` vars but nothing constrains them in the consistency path
(`jfImplications()` is only used by `encodeRelation("jf")`, which validation never
calls). Left free, the solver sets `jf=false` to escape every cycle and the axiom
is vacuous. In this single-execution encoding, *justified-from coincides with
reads-from* for the committed execution, so couple them:

```
jf_w_r  ⇔  rf_w_r        (per candidate (w,r), gated by active endpoints)
```

(Equivalently — and more simply — skip `jf` vars and use `rf_w_r` directly in the
layer edge; the two are identical here. Using `jf` keeps the witness extractor's
`jfMap` populated and matches the paper's vocabulary. Recommend the coupling.)
Document the `jf = rf` identification as a modeling limitation: the full
event-structure semantics permits `jf` to differ from `rf` (justification from a
sibling branch), which this single-execution SMT encoding does not represent.

### 2.4 Method shape (gated form is the source of truth; unconditional = `e→true`)

```
BooleanFormula jfCoherence(Function<Event,BooleanFormula> active):
    layer = freshLayer("jfco")
    cs = []
    # semantic dependency edges  (producer → consumer)
    for (consumer, producer) in deps.semanticEdges():        # isSemantic=true only
        cs += implication(both(active, producer, consumer),
                          lessThan(layer[producer], layer[consumer]))
    # jf = rf, and jf ⇒ producer(w) before consumer(r)
    for (w,r), rfVar in enc.getRfVars():
        jfVar = enc.getJfVars()[(w,r)]
        cs += equivalence(jfVar, rfVar)                       # coupling
        cs += implication(and(jfVar, both(active, w, r)),
                          lessThan(layer[w], layer[r]))
    return and(cs)
```

Add a `DependencyInfo.semanticEdges()` accessor (consumer/producer pairs across
`data∪addr∪ctrl` with `isSemantic=true`) — see Stage-2 Task 1 (`isSemantic` flag),
still to be added.

### 2.5 Wiring into `consistencyWEAKEST` (only)

`consistencyWEAKEST(active)` becomes:
- **remove** `addPo(...)` + `addRf(...)` (the `acyclic(po∪rf)` over-approximation);
- **keep** the existing non-relaxed-read jf-loop (`isJustifiableGiven` mirror) and
  `coherencePerLocation(active)`;
- **add** `jfCoherence(active)`.

Do **not** add `jfCoherence` to SC/TSO/PSO/RA. SC/TSO/PSO re-forbid LB through
their own acyclic `rf` layers; RA is the *bare* release/acquire model with no
thin-air axiom and is *meant* to allow LB (and, having no dependency rule, even
the dep variants — see §4).

---

## 3. Corpus migration — **Option B chosen** (correct expectations, keep cases)

After the WF relaxation, the no-dep "OOTA twins" `LB-fake-dep` and `OOTA-cycle`
become ALLOWED under RA/WEAKEST because they are **literally `LB`'s event
structure** with no distinguishing dependency. Their Stage-0/1 expectation
`(F,F,F,F,F)` therefore stops matching the encoding. **Decision (2026-05-22): keep
both cases and correct their expected maps to `(F,F,F,A,A)`** — *not* retire them.

### Rationale for correcting rather than retiring

Retiring the two cells reads, to a reviewer, as "we deleted the tests that stopped
agreeing with us" — a defensive move. The honest research framing is the opposite:
**the prior textbook column was wrong for these two cells, and we can now say
exactly why.** The earlier `(…,F,F)` under RA/WEAKEST was never a genuine
model verdict; it was an artifact of the global-`pos` well-formedness lock
(the rf-forward edge) *accidentally* forbidding the bare load-buffering shape on
**every** model (this session's WF-alone-UNSAT probe; `encoding-rootcause-analysis.md`
§2/§4). Once that lock is lifted and dependency structure is what distinguishes
real OOTA (`LBdep-real`/`addr`, forbidden under WEAKEST) from a no-dependency LB
(allowed under bare RA and WEAKEST), the model-faithful verdict for a *dependency-free*
LB shape is `(F,F,F,A,A)` — identical to `LB` itself. Keeping `LB-fake-dep` and
`OOTA-cycle` with the corrected column documents this re-derivation in the corpus:
they stand as the explicit "no-dependency ⇒ allowed under RA/WEAKEST" witnesses
that motivate why `LBdep-real`/`addr` need *semantic* dependencies to be forbidden.
The redundancy with `LB` (+10 always-matching compared cells) is a small,
deliberate price for that traceability.

`LB-fake-dep` and `OOTA-cycle` keep their names and structures; only their
`expected` maps change `(F,F,F,F,F) → (F,F,F,A,A)`. `LBdep-fake`/`LBdep-real`/
`LBdep-addr` keep their structures; their `expected` maps change from all-`UNKNOWN`
to the §4 values, and `isSemantic` is set per case (fake → `false`, real/addr →
`true`).

### Implementation-time test note (added 2026-05-22)

Dropping the rf-forward WF edge (§1.3) breaks `EventStructureEncoderTest.
twoEventWriteReadIsSat`, which asserted `pos(w)<pos(r)` *in bare well-formedness* —
the exact edge being removed, and unavoidably so (any rf-forward edge re-locks LB).
Resolution (approved): that one test keeps its bare-WF **SAT** check and relocates
the `pos(w)<pos(r)` assertion to `wf ∧ coherencePerLocation()`, the layer that now
owns per-location read-after-write (§1.2). This is a relocation, not a weakening:
the invariant is still asserted, at the layer it now belongs to.

---

## 4. Per-model expected outcomes (honest, cited)

`F` = FORBIDDEN, `A` = ALLOWED. Corpus "RA" = **bare release/acquire (coherence
only)**, *not* full RC11 — it has **no** `acyclic(po∪rf)` no-thin-air axiom
(`encoding-rootcause-analysis.md` §1(a) note), which is the whole reason `LB/RA`
is expected ALLOWED.

| Case | SC | TSO | PSO | RA | WEAKEST | Basis |
|---|----|----|----|----|---------|-------|
| `LB` | F | F | F | A | A | SC: total order. TSO/PSO keep `R→W` (Herding Cats §4.4, ppo=po∖W→R, po∖{W→R,W→W}) → LB needs `R→W` reorder → F. RA bare R/A allows (Lahav PLDI'17 §3 coherence, no NTA). WEAKEST allows genuine LB (C&V POPL'19 §3). |
| `3.LB` | F | F | F | A | A | as `LB`, 3-thread cycle (same ppo / coherence reasoning). |
| `LB-acqrel` | F | F | F | A† | A | `buildLB(REL,ACQ)` = release-*reads*/acquire-*writes*, a **non**-synchronising combo (release tags writes, acquire tags reads), so no `sw`; behaves like `LB`. †corpus currently marks RA=`U`; the fix yields actual A — keep `U` or set `A` (judgement call, see §5). |
| `LBdep-fake` | F | F | F | A | A | LB shape (SC/TSO/PSO/RA as above). WEAKEST: fake dep ∉ `sdep` ⇒ no jf-cycle ⇒ allowed (C&V POPL'19 §3, fake/identity dependency is not semantic). |
| `LBdep-real` | F | F | F | A | **F** | LB shape (SC/TSO/PSO/RA as above; RA has no dependency rule ⇒ A). WEAKEST: real data dep ∈ `sdep` ⇒ jf-cycle ⇒ **forbidden** (C&V POPL'19 §3, no-thin-air). |
| `LBdep-addr` | F | F | F | A | **F** | as `LBdep-real`; address dependency is equally semantic (C&V POPL'19 §3 treats `addr` with `data`/`ctrl`). |
| `LB-fake-dep` | F | F | F | A | A | **Option B: kept, expectation corrected `(F,F,F,F,F)→(F,F,F,A,A)`.** No-dep LB shape; the old `(…,F,F)` was the WF-lock artifact (§3), not a model verdict. Same basis as `LB`. |
| `OOTA-cycle` | F | F | F | A | A | **Option B: kept, expectation corrected `(F,F,F,F,F)→(F,F,F,A,A)`.** No-dep LB shape; genuine OOTA forbidding now comes from the *semantic*-dep cases `LBdep-real`/`addr`. Same basis as `LB`. |

> **Correction vs. the original Task-4 numbers:** the prompt's `LBdep-fake =
> {TSO:A, PSO:A}` and `LBdep-real/addr = {RA:F}` are model-inconsistent. TSO/PSO
> forbid load buffering *regardless of dependencies* (they preserve `R→W`); bare
> RA allows *all* LB shapes (no NTA axiom). Dependencies flip the verdict **only
> under WEAKEST**. The table above is the corrected, model-faithful set.

---

## 5. Net mismatch arithmetic

Baseline (Stage 1, commit `754de74`): **118 compared / 113 matched / 5 mismatched**.
The 5: `LB/RA`, `LB/WEAKEST`, `3.LB/RA`, `3.LB/WEAKEST`, `LB-acqrel/WEAKEST`.

Changes and their cell deltas (**Option B — keep both twins, correct their column**):

| Change | Δ compared | Δ matched | Δ mismatched |
|---|---|---|---|
| 5 LB-family cells flip F→A to meet textbook | 0 | +5 | −5 |
| `LB-fake-dep` `{RA,WEAKEST}` flip F→A; exp corrected `(…,F,F)→(…,A,A)` ⇒ still matched | 0 | 0 | 0 |
| `OOTA-cycle` `{RA,WEAKEST}` flip F→A; exp corrected ⇒ still matched | 0 | 0 | 0 |
| `LBdep-fake` U→compared `(F,F,F,A,A)`, now all match | +5 | +5 | 0 |
| `LBdep-real` U→compared `(F,F,F,A,F)`, now all match | +5 | +5 | 0 |
| `LBdep-addr` U→compared `(F,F,F,A,F)`, now all match | +5 | +5 | 0 |
| **Total** | **+15** | **+15** | **−5** |

**After: 133 compared / 133 matched / 0 mismatched.** Net **0 mismatches** — goal
met. (The twins contribute 0 net: their `{RA,WEAKEST}` cells flip to ALLOWED *and*
their expectations are corrected in the same step, so they remain matched — no
removal, no new mismatch.) If `LB-acqrel/RA` is moved `U`→`A` (†), add +1
compared/+1 matched; it does not change the mismatch count.

**Cells that flip to ALLOWED** (for transparency): the 5 textbook flips
(`LB/{RA,WEAKEST}`, `3.LB/{RA,WEAKEST}`, `LB-acqrel/WEAKEST`); `LB-fake-dep` and
`OOTA-cycle` under `{RA,WEAKEST}` (4 cells, expectation corrected in lockstep so
they stay matched); and the dep-case cells moving `U`→ compared `A`
(`LBdep-fake` all five; `LBdep-real`/`addr` under RA only — they stay F under
SC/TSO/PSO and WEAKEST).

---

## 6. Regression risk table (WF relaxation)

Relaxing WF only **removes** constraints, so a currently-**ALLOWED** cell can
never regress. Only currently-**FORBIDDEN-and-matching** cells can, and only if
the model's *consistency* axiom does not independently re-forbid the wired
execution once the global rf-forward edge is gone.

| Currently-matching FORBIDDEN cells | Re-forbidden after WF relax by… | Verdict | Mitigation |
|---|---|---|---|
| `CoRR/CoRW/CoWR/CoWW` (all 5 models) | `coherencePerLocation` (po-loc + rf-loc, per location) | **safe** | none needed (worked example §1.2) |
| `LB/{SC,TSO,PSO}`, `3.LB/{SC,TSO,PSO}`, `LB-acqrel/{SC,TSO,PSO}` | SC full layer; TSO/PSO keep `R→W` + `rfe` in their acyclic layer | **safe** | none |
| `MP/{SC,TSO}`, `MP-rel/{SC,TSO}`, `MP-acq/{SC,TSO}`, `ISA2/{SC,TSO}` | TSO keeps `W→W`; SC full | **safe** | none |
| `IRIW/{SC,TSO,PSO}`, `WRC/{SC,TSO,PSO}`, `S/{SC,TSO}`, `2+2W/{SC,TSO}`, `R/SC`, `RWC/SC`, `3.SB/SC` | preserved-order/`rfe`/`fr` in each model's layer | **safe** | none |
| `CO-MP/*`, `CO-WRC/*`, `CO-IRIW/{SC,TSO,PSO}`, `MP-relacq/*` | one-`eco`-segment `hb;eco?` (RA/PSO Pass 2a/2c); TSO `W→W` | **safe** | none |
| **`LB-fake-dep/RA`** | nothing — RA has no acyclic-rf layer; LB is cross-location so `coherencePerLocation` can't catch it | **REGRESS → A** | **retire (Opt A) / correct exp to A (Opt B)** |
| **`OOTA-cycle/RA`** | nothing (same as above) | **REGRESS → A** | **retire / correct exp to A** |
| **`LB-fake-dep/WEAKEST`** | was double-locked; WF lock gone *and* `acyclic(po∪rf)` replaced by `jfCoherence` (no deps ⇒ no `sdep` cycle) | **REGRESS → A** | **retire / correct exp to A** |
| **`OOTA-cycle/WEAKEST`** | same as above | **REGRESS → A** | **retire / correct exp to A** |

The **only** regressions are the four no-dep-twin cells — exactly the
`encoding-rootcause-analysis.md` §4 "2b-aggressive HIGH-risk" cells, and exactly
why §3's corpus migration is mandatory, not optional. All four are eliminated by
retiring (Option A) or correcting (Option B) the twins. **Net regressions after
migration: 0.**

**Non-validation side effect (not a regression):** the separation atlas
(`MinimalWitnessExtractor`) will shift — freeing the LB shape changes minimum
witness sizes and may add/remove `T/O` cells. The validation column (the headline
metric) is exact; the separation matrix is a best-effort budgeted search
(`encoding-rootcause-analysis.md` §6 "Known artifact"). Re-baseline it after the
change; raise `PER_CALL_BUDGET_MS` if specific separations matter.

---

## 7. Implementation order, files, verification

1. **`DependencyInfo`** — add `isSemantic` (Task 1): `record DepEdge(Event
   consumer, Event producer, boolean isSemantic)`; maps become
   `Map<Event,Set<DepEdge>>`; `addXDep(c,p)` defaults `isSemantic=true`, add
   `addXDep(c,p,boolean)`; add `semanticEdges()` (and keep `getXDeps` views).
2. **`LitmusCorpus`** — `buildLBFakeDep` → `isSemantic=false`; `buildLBRealDep`,
   `buildLBAddrDep` → `isSemantic=true`. Set `LBdep-*` expectations per §4.
   **Option B:** keep `LB-fake-dep` + `OOTA-cycle`, correct their `expected`
   `(F,F,F,F,F) → (F,F,F,A,A)`.
3. **`EventStructureEncoder.encodeWellFormedness`** — delete the rf-forward
   implication (§1.3).
4. **`MinimalWitnessExtractor.gatedWellFormedness`** — delete the matching
   rf-forward implication (§1.3).
5. **`AxiomaticConsistency`** — add `jfCoherence(active)` (§2.4); rewrite
   `consistencyWEAKEST` (§2.5). Update the WEAKEST javadoc bullet.
6. **No edits** to `com.weakest.*` or `ConsistencyChecker.java`.

**Verification checklist:**
- `mvn test` — existing tests stay green. **Watch `AxiomaticConsistencyTest`:** it
  asserts only `SC ⇒ UNSAT` (hard) but **prints** `LB/RA`, `LB/WEAKEST` etc.; its
  hard assertions still hold (SC unaffected), but the printed `RA`/`WEAKEST` cells
  for `LB`/`WRC`/`SB` will change SAT↔UNSAT — confirm no `assertTrue` beyond SC
  trips. (That test builds its own all-`SC`-mo `LB`; SC stays UNSAT.)
- Run `AtlasReconstruct`: expect **0 mismatches**, `123/123` (Option A). Confirm
  `LBdep-fake/WEAKEST = ALLOWED`, `LBdep-real/WEAKEST = LBdep-addr/WEAKEST =
  FORBIDDEN`, all `LBdep-*/RA = ALLOWED`, all `LBdep-*/{TSO,PSO,SC} = FORBIDDEN`.
- Re-baseline the separation atlas (expect more `T/O`; not a validation
  regression).
- Diff `mismatches.txt` before/after.

**Stop-and-report trigger:** if `LBdep-fake/WEAKEST` is FORBIDDEN, or any cell
**outside** the four migrated twins regresses, halt and diagnose — that is a real
algorithm bug, not something to patch over.

---

## 8. Open items to confirm before camera-ready

- **C&V POPL'19 §3 exact equation/definition numbers** for `jf`, `sdep`, and the
  well-justified/jf-acyclicity condition — this plan cites §3 by name; pin the
  numbers against the published PDF (Proc. ACM PL 3, POPL, Art. 70).
- **`jf = rf` identification** (§2.3) — sound for single-execution validation;
  state explicitly as a limitation vs. the full event-structure semantics.
- **`LB-acqrel/RA`** — keep `U` or set `A` (§4 †); decide before the run so the
  arithmetic is exact.
- **Corpus migration choice** — Option A (retire, recommended) vs B (repurpose).

See `encoding-rootcause-analysis.md` for the full 16→5 history and the cell×family
matrix this plan extends.
