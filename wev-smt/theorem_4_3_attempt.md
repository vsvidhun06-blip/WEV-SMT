# Theorem 4.3 (hierarchy preservation) — by-construction proof attempt

**Goal.** For each covering edge M ⊒ M′ in the strength lattice
(SC ⊒ TSO ⊒ PSO; SC ⊒ RA ⊒ WEAKEST), decide whether the SMT constraint set of M
*logically entails* that of M′, so that `consistent_M ⇒ consistent_M′` holds **by
construction** (not merely empirically — "0 violations across 2,998 corpus files").

**Headline result.** Two of the four edges are PROVED by construction; the other two are
**refuted by verified counterexamples** — and in fact the two model pairs are
*incomparable*, not merely unordered. The by-construction theorem is real **only for the
SC-rooted edges**. All claims below were checked by running both models through the
unmodified encoding (`wev.smt.proof.HierarchyProbe`, reproduce command at the end).

| Edge | Result |
|---|---|
| **SC ⊒ TSO** | **PROVED** (R_TSO ⊆ R_SC) |
| **TSO ⊒ PSO** | **COUNTEREXAMPLE** — `WR-relacq-cex`: TSO allows, PSO forbids; TSO/PSO incomparable |
| **SC ⊒ RA** | **PROVED** (hb;eco? ⊆ (po∪rf∪co∪fr)⁺) |
| **RA ⊒ WEAKEST** | **COUNTEREXAMPLE** — `LBdep-real`: RA allows, WEAKEST forbids; RA/WEAKEST incomparable |

A clause is identified by its method in `src/main/java/wev/smt/AxiomaticConsistency.java`.
The convention throughout: a fixed *execution* X = (rf, co assignment, all events active);
`consistent_M(X)` is the satisfiability of M's formula, the layer variables being
existentially-quantified witnesses. For M ⊒ M′ a **violation** on X is
`consistent_M(X) ∧ ¬consistent_M′(X)` (M allows, M′ forbids).

---

## 0. The five constraint sets

Read off `consistencyX(active)`. Write `G = po ∪ rf ∪ co ∪ fr`. `CPL` =
`coherencePerLocation` (`AxiomaticConsistency:380`): `⋀_L acyclic(po-loc(L) ∪ rf(L) ∪
co(L) ∪ fr(L))`, per-location layers. `RMW` = `rmwAtomicity` (`:571`):
`¬(rf(w,u) ∧ co(w,w′) ∧ co(w′,u))` over RMW reads u. **CPL and RMW are textually identical
clauses in all five models** (same method, same `active`), so they never differ between any
pair and are not re-litigated below.

| Model | Consistency formula | Source |
|---|---|---|
| **SC** | `acyclic(po ∪ rf ∪ co ∪ fr)` ∧ CPL ∧ RMW | `:97`–`107` |
| **TSO** | `acyclic(ppo_T ∪ rfe ∪ co ∪ fr)` ∧ CPL ∧ RMW | `:109`–`119` |
| **PSO** | `acyclic(ppo_P ∪ rfe ∪ co ∪ fr)` ∧ CPL ∧ RMW ∧ **`irreflexive(hb;eco?)`** | `:121`–`136` |
| **RA** | **`irreflexive(hb;eco?)`** ∧ CPL ∧ RMW | `:138`–`146` |
| **WEAKEST** | ⟨inert guard⟩ ∧ CPL ∧ **`jfCoherence`** ∧ RMW | `:148`–`184` |

* `ppo_T` = po minus same-thread **W→R** adjacency (fence/RMW-restored); `addPo(…,
  skipWriteRead=true, skipWriteWrite=false, …)` `:112`, `:477`–`491`. `rfe` = cross-thread rf
  (`addRf(…, externalOnly=true,…)` `:113`, `:597`).
* `ppo_P` = po minus same-thread **W→R and W→W** adjacency (fence/RMW-restored)
  (`skipWriteWrite=true`, `:124`).
* `irreflexive(hb;eco?)` (`irreflexiveHbEco`, `:275`): `hb = (po ∪ sw)⁺` with
  `sw` = release-write →rf→ acquire-read (`:301`–`307`); `eco = (rf ∪ co ∪ fr)⁺`
  (`:310`–`334`); forbids `hb` cycles and `hb;eco` cycles (`:351`–`358`).
* `jfCoherence` (`:217`): `acyclic(sdep ∪ jf)`, with `sdep` = the **semantic** dependency
  edges (`isSemantic=true`, producer→consumer, `:222`–`228`) and `jf ≡ rf` (`:231`–`240`).
* WEAKEST's "inert guard" (`:164`–`179`) emits only `≥` constraints on an otherwise
  ungrounded layer; the code comment (`:157`–`163`) states it adds no constraint. The
  substantive WEAKEST-only clause vs. RA is therefore `jfCoherence`.

**A fact used twice.** `eco` relates only **same-location** events: each base edge
(rf, co, fr) is between two accesses of one location, and the transitive closure shares an
endpoint event (hence one location) at each step. So any `eco(z,x)` forces loc(z)=loc(x).

---

## 1. SC ⊒ TSO — **PROVED**

`consistent_SC` and `consistent_TSO` share CPL and RMW verbatim. It remains to show
`acyclic(G) ⇒ acyclic(ppo_T ∪ rfe ∪ co ∪ fr)`.

*Edge inclusion.* `ppo_T ⊆ po`: for TSO, `addPo` emits a strict subset of the adjacency
pairs it emits for SC — a hop pair (a,b) is dropped only when `skipWriteRead ∧ a∈{W,init} ∧
b∈Read` and it is not fence/RMW-restored (`:484`–`486`); SC has `skipWriteRead=false` and so
emits *every* hop pair, and restoration (`:529`) only re-adds dropped **po** hop pairs.
`rfe ⊆ rf`: `externalOnly` merely skips same-thread rf (`:597`). Hence
`R_TSO = ppo_T ∪ rfe ∪ co ∪ fr ⊆ po ∪ rf ∪ co ∪ fr = R_SC` as edge sets over events.

*Conclusion.* Any integer assignment to the per-event layer that strictly orders every
edge of `R_SC` strictly orders every edge of the subset `R_TSO`. So the witness to
`acyclic(R_SC)` is a witness to `acyclic(R_TSO)`. Thus `consistent_SC(X) ⇒
consistent_TSO(X)`. ∎

*Empirical confirmation.* 0/40 classics violate SC⊒TSO (`HierarchyProbe` scan).

---

## 2. SC ⊒ RA — **PROVED**

Shares CPL and RMW with SC. Remaining obligation: `acyclic(G) ⇒ irreflexive(hb;eco?)`.

`sw ⊆ rf` (a `sw` edge is exactly a release-write→acquire-read **rf** edge, `:301`–`307`),
so `hb = (po∪sw)⁺ ⊆ (po∪rf)⁺ ⊆ G⁺`. Also `eco = (rf∪co∪fr)⁺ ⊆ G⁺`. Therefore every edge
appearing in an `hb` or `hb;eco` cycle is a `G`-edge, and such a cycle is a cycle in `G⁺`.
`consistent_SC` asserts `acyclic(G)`, i.e. `G⁺` is irreflexive, so no such cycle exists and
`irreflexive(hb;eco?)` holds. (Concretely: the layer witnessing `acyclic(G)` strictly
increases along every `hb` and `eco` edge, so a `hb;eco?` cycle would force
`layer(x) < … < layer(x)`.) ∎

*Empirical confirmation.* 0/40 classics violate SC⊒RA.

*Bonus (same argument).* `acyclic(G)` also entails PSO's extra `irreflexive(hb;eco?)` and
WEAKEST's `jfCoherence` (`sdep ⊆ po` since dependencies follow program order, `jf ≡ rf`, so
`sdep∪jf ⊆ G`). Together with §1 this gives **SC ⊒ PSO and SC ⊒ WEAKEST by construction**:
SC is a genuine top of the lattice. The failures below are confined to the two intermediate
"weakening" edges.

---

## 3. TSO ⊒ PSO — **COUNTEREXAMPLE** (and incomparable)

*Base relation does transfer.* `ppo_P ⊆ ppo_T` (PSO additionally drops same-thread W→W,
`:485`; W→R and restoration handled identically), and `rfe, co, fr, CPL, RMW` are identical.
So `consistent_TSO ⇒ (acyclic(ppo_P ∪ rfe ∪ co ∪ fr) ∧ CPL ∧ RMW)` — PSO's base part is
entailed.

*Where it breaks.* PSO carries one clause TSO lacks: `irreflexive(hb;eco?)` (`:134`). It is
**not** entailed by `consistent_TSO`, because `hb` uses the **full** program order — including
the same-thread W→R pairs `ppo_T` deletes — and `sw`/`eco` may use internal rf, none of which
are edges of `R_TSO`.

**Witness `WR-relacq-cex`** (built in `HierarchyProbe.buildWRrelacqCex`, verified):

```
T1:  W x = 1 ;  R a (=0) ;  W_rel b = 1        po: Wx → Ra → Wb
T2:  R_acq b (=1) ;  W x = 2                   po: Rb → Wx2
co(x):  init → Wx2 → Wx        (so Wx2 is co-before Wx; final x = 1)
co(b):  init → Wb ;   rf:  R_acq b ← Wb ,   R a ← init
```

Checker verdicts (SAT=allow, UNSAT=forbid): **SC=forbid, TSO=ALLOW, PSO=FORBID, RA=forbid,
WEAKEST=allow** = (F, **A**, **F**, F, A).

* **PSO forbids** via `hb;eco`: `hb(Wx, Wx2)` = `Wx →po→ Ra →po→ Wb →sw→ R_acq b →po→ Wx2`
  (the `sw` is the release/acquire b-handshake), and `eco(Wx2, Wx) = co(Wx2, Wx)`. That is an
  `hb;eco` cycle → `irreflexive(hb;eco)` fails → UNSAT.
* **TSO allows**: the only program-order edge out of `Wx` is the same-thread **W→R** pair
  `Wx → Ra`, which `ppo_T` drops; `Wx` is co-maximal on x (no co-out) and is not a read (no
  fr-out), so `Wx` has **no outgoing edge** in `ppo_T ∪ rfe ∪ co ∪ fr`. No cycle exists →
  SAT. Because PSO's base relation ⊆ R_TSO, the forbidding is attributable **solely** to the
  `hb;eco` clause.

So `WR-relacq-cex ∈ consistent_TSO ∖ consistent_PSO` — the edge fails. ∎

*Incomparability.* The reverse containment also fails: `2+2W` is **PSO=allow, TSO=forbid**
(TSO keeps W→W, PSO drops it). With `WR-relacq-cex` (TSO-only-allow) this shows
`consistent_TSO ⊄ consistent_PSO` **and** `consistent_PSO ⊄ consistent_TSO`: the two models
are **incomparable**, not a refinement pair.

*Diagnosis.* This is a genuine property of the encodings, exposing that the encoding's TSO
**under-approximates** textbook x86-TSO: a FIFO store buffer would propagate `Wx` before `Wb`
and hence before `Wx2`, forbidding the `co`-reversal — exactly the W→R/fence
under-forbidding already documented in `herd7_baseline.md`. PSO's RC11 release/acquire term
catches it; the encoding's TSO does not. The classics scan shows 0 TSO⊒PSO violations only
because none of them wires "W→R + release/acquire handshake + coherence-reversed co"; the
2,998-file corpus likewise never exercised it.

---

## 4. RA ⊒ WEAKEST — **COUNTEREXAMPLE** (and incomparable)

Shares CPL and RMW with RA; WEAKEST's inert guard adds nothing (§0). The substantive
WEAKEST-only clause is `jfCoherence = acyclic(sdep ∪ jf)` (`jf ≡ rf`). RA does not contain it
and does not entail it: this RA is the **coherence-only** RC11 fragment (no `acyclic(po∪rf)`
no-thin-air axiom), so it *permits* load-buffering po∪rf cycles, whereas `jfCoherence`
*forbids* those that thread a **semantic** dependency.

**Witness `LBdep-real`** (already in the curated corpus; `LBdep-addr`, `3.LBdep-real`
behave identically), verified:

```
T1:  r1 = R x ;  W y = f(r1)      data dep  r1 → Wy   (isSemantic = true)
T2:  r2 = R y ;  W x = g(r2)      data dep  r2 → Wx   (isSemantic = true)
rf:  r1 ← Wx ,  r2 ← Wy           (the load-buffering "reads the future" outcome)
```

Checker verdicts: **SC=forbid, TSO=forbid, PSO=forbid, RA=ALLOW, WEAKEST=FORBID** =
(F, F, F, **A**, **F**) — matching the textbook table the atlas reproduces.

* **WEAKEST forbids** via `jfCoherence`: with the two *semantic* deps in `sdep` and `jf=rf`,
  the cycle closes as `layer(Wx) < layer(r1) < layer(Wy) < layer(r2) < layer(Wx)` (the
  polarity of `:210`–`215`) → UNSAT.
* **RA allows**: the reads are relaxed, so there is no `sw`, `hb` is just intra-thread `po`,
  and there is no `hb;eco` cycle → SAT.

So `LBdep-real ∈ consistent_RA ∖ consistent_WEAKEST` — the edge fails. ∎ (3 such witnesses
in classics; `HierarchyProbe` edge-violation count = 3 on RA⇒WEAKEST.)

*Incomparability.* Reverse containment also fails: `CO-MP` and `MP-relacq` are
**RA=forbid, WEAKEST=allow** (RA's `hb;eco` forbids the synchronised message-passing that
WEAKEST, reasoning only by `jfCoherence`, permits). With `LBdep-real` (RA-only-allow) this
gives `consistent_RA ⊄ consistent_WEAKEST` **and** `consistent_WEAKEST ⊄ consistent_RA`:
**incomparable**.

*Diagnosis.* This is WEAKEST's design point (Chakraborty–Vafeiadis grounding): it forbids
dependency-carried thin-air that the release/acquire fragment allows, while allowing the
synchronised executions RA forbids. WEAKEST is therefore **not uniformly weaker than RA**.
(Project notes already label RA⟹WEAKEST a "non-theorem"; this confirms and localises it.)
The empirical 0/2,998 held only because the public corpus contained no semantic-dependency LB
test — the curated classics do, and all three violate the edge.

---

## 5. Combined summary

| Edge | By-construction? | Witness / reason |
|---|---|---|
| SC ⊒ TSO | **YES (PROVED)** | R_TSO ⊆ R_SC; CPL,RMW shared |
| SC ⊒ PSO *(transitive)* | **YES (PROVED)** | acyclic(G) ⇒ base ∧ hb;eco? |
| SC ⊒ RA | **YES (PROVED)** | hb;eco? ⊆ G⁺ |
| SC ⊒ WEAKEST *(transitive)* | **YES (PROVED)** | sdep∪jf ⊆ G |
| **TSO ⊒ PSO** | **NO (counterexample)** | `WR-relacq-cex`: TSO allows, PSO forbids (hb;eco). Incomparable (also `2+2W` reverse). |
| **RA ⊒ WEAKEST** | **NO (counterexample)** | `LBdep-real`: RA allows, WEAKEST forbids (jfCoherence). Incomparable (also `CO-MP` reverse). |

**What can be claimed by construction:** every relation **rooted at SC** — SC ⊒ TSO,
SC ⊒ PSO, SC ⊒ RA, SC ⊒ WEAKEST — because SC's monolithic `acyclic(po∪rf∪co∪fr)` entails
every other model's relation plus the shared CPL+RMW. SC is a true maximum.

**What remains (and is in fact false):** the two intermediate weakening edges TSO ⊒ PSO and
RA ⊒ WEAKEST are **not** containments. Each weaker model adds an *orthogonal* axiom the
stronger model lacks — PSO's RC11 `irreflexive(hb;eco?)`, WEAKEST's dependency-grounded
`jfCoherence` — so it forbids executions the model "above" it allows. The pairs are
incomparable in the consistency-set order. The structure is therefore **not a total strength
lattice**: it is SC at the top above a layer whose internal edges are partial/incomparable.

**Why the empirical "0/2,998" did not catch this.** The corpus lacks both witnessing shapes:
(i) a same-thread W→R combined with a release/acquire handshake and a coherence-reversed `co`
(TSO/PSO), and (ii) a load-buffering test carrying a *semantic* dependency (RA/WEAKEST). The
empirical result is correct *for that corpus* but does not generalise — it is exactly the
"true only because the corpus didn't exercise the configuration" situation.

---

## 6. Draft §4.7 (rewritten — empirical, with proved subset and known counterexample shapes)

> **§4.7  Hierarchy preservation.** The five models are often pictured as a strength
> lattice SC ⊒ TSO ⊒ PSO and SC ⊒ RA ⊒ WEAKEST, meaning every execution consistent under a
> stronger model is consistent under each weaker one. We can establish part of this *by
> construction* from the SMT encoding. Because SC's consistency is the single acyclicity
> `acyclic(po ∪ rf ∪ co ∪ fr)` together with per-location coherence and RMW atomicity, and
> every other model's relations are sub-relations of that closure (TSO/PSO preserve a subset
> of program order; RA/PSO's `irreflexive(hb;eco?)` and WEAKEST's `acyclic(sdep ∪ jf)` are
> all contained in `(po∪rf∪co∪fr)⁺`), **all four SC-rooted implications hold by
> construction**: an SC-consistent execution is consistent under TSO, PSO, RA and WEAKEST.
> The two *intermediate* edges, however, are **not** entailments, and we have verified
> counterexamples. TSO ⊒ PSO fails: PSO carries the RC11 release/acquire term
> `irreflexive(hb;eco?)` that TSO lacks, and a store followed by a relaxed load and a
> release handshake (`WR-relacq-cex`) is TSO-consistent but PSO-inconsistent; symmetrically
> `2+2W` is PSO-consistent but TSO-inconsistent, so the two models are *incomparable*.
> RA ⊒ WEAKEST fails for the dual reason: WEAKEST's dependency-grounded no-thin-air axiom
> forbids semantic-dependency load buffering (`LBdep-real`) that RA allows, while RA's
> `hb;eco` coherence forbids synchronised message passing (`CO-MP`) that WEAKEST allows —
> again incomparable. The structure is thus SC above a layer of mutually incomparable weak
> models, not a total lattice. Our corpus measurement (0 hierarchy-soundness violations on
> 2,998 fully-validated files) is consistent with this — it simply does not contain the two
> witnessing shapes — and we report it as empirical evidence for the SC-rooted relations
> rather than as a proof of a total lattice. The counterexample shapes are characterised in
> App. X.

---

### Reproduce

`wev.smt.proof.HierarchyProbe` (read-only; invokes the unmodified `AxiomaticConsistency`):

```
mvn -q compile
java -Djava.library.path=target/native \
     -cp "target/classes;$(cat target/ablation-cp.txt)" \
     wev.smt.proof.HierarchyProbe
```

Prints the 40-case classics SAT/UNSAT scan with per-edge violation flags (RA⇒WEAKEST = 3,
all others 0) and the `WR-relacq-cex` TSO⊒PSO counterexample (TSO allow / PSO forbid).
