# Encoding root-cause analysis: the 16 validation mismatches

**Status:** analysis only — no encoding changes made. Written before Pass 2 to
correct the working pass plan, which assumed all 7 "RA/PSO fr-cycle" cells live
in one root cause and are fixable in `AxiomaticConsistency`. They do not.

**Inputs read for this analysis (not from memory):**
`AxiomaticConsistency.java`, `EventStructureEncoder.encodeWellFormedness`,
`MinimalWitnessExtractor.gatedWellFormedness`, `LitmusCorpus.classics()`, and the
post-Pass-1 atlas (`mismatches.txt`, 16 cells; atlas run within the 600 s budget).

**TL;DR.** The 16 mismatches have **four** distinct root causes, not three, and
they live in **two** layers of the encoder, not one:

| Layer | Mechanism | Cells |
|---|---|---|
| `AxiomaticConsistency` (RA) | RA forbids multi-`eco`-segment cycles a correct `irreflexive(hb;eco?)` would allow | 3 (+1 shared) |
| `encodeWellFormedness` / `gatedWellFormedness` | a single global `pos` order forces `acyclic(po ∪ rf ∪ co)` on **every** model | 11 |
| `AxiomaticConsistency` (WEAKEST) | `acyclic(po ∪ rf)` forbids the load-buffering shape WEAKEST should permit | 3 (all also well-formedness-blocked) |
| `AxiomaticConsistency` (TSO/PSO) | `ppo` ignores release/acquire fence ordering → **under**-forbids | 2 |

The headline correction: **`S` and `2+2W` under WEAKEST are well-formedness
cases, not WEAKEST-consistency cases** (proven below); and the LB family is
**double-locked** by well-formedness *and* WEAKEST-consistency. A second
correction: the two `expected=FORBIDDEN actual=ALLOWED` cells (`CO-MP/PSO`,
`MP-relacq/PSO`) are a fourth family the current pass plan never mentions.

The most important operational finding is in §4: the corpus encodes three
semantically-distinct tests (`LB`, `LB-fake-dep`, `OOTA-cycle`) with **one
identical event structure**, so any axiom change that flips the LB shape to
*allowed* under RA/WEAKEST necessarily regresses the two OOTA twins. A
**minimal** well-formedness relaxation (drop only cross-location `W→W`/`W→R`)
fixes 6 cells with **zero** regressions and leaves the LB family alone, where it
belongs (it needs dependency-carrying event structures, not an axiom tweak).

---

## 1. The root-cause families

### (a) RA over-forbids: `acyclic(po ∪ co ∪ fr)` instead of `irreflexive(hb;eco?)`

**Cells:** `SB/RA`, `R/RA`, `CO-IRIW/RA` (and the RA half of `2+2W/RA`).

**Why the current encoding over-forbids.** `consistencyRA` (AxiomaticConsistency.java:116)
puts full `po`, release/acquire `rf` (`sw`), `co`, and `fr` into a *single*
acyclicity layer (the Gavrilenko et al. CAV 2019 layer-variable trick). A single
layer forbids **every** cycle in `po ∪ sw ∪ co ∪ fr`, regardless of how the
coherence (`eco`) edges are distributed around the cycle.

The release/acquire model of RC11 is weaker than that. Its coherence axiom is

> **coherence:** `irreflexive(hb ; eco?)`

where `hb = (po ∪ sw)+` is happens-before, `eco = (rf ∪ co ∪ fr)+` is the
extended-coherence order, and `eco?` is `eco` made reflexive (Lahav, Vafeiadis,
Kang, Hur & Dreyer, *Repairing Sequential Consistency in C/C++11*, PLDI 2017, §3,
Fig. 2). Operationally: a forbidden cycle must be a happens-before path followed
by **at most one** `eco` step. A cycle that threads **two or more** `eco`
segments between `hb` segments is *not* of the form `hb ; eco?` and is therefore
**allowed**.

That is exactly the SB/R/IRIW family:

- `SB`: cycle `wx →po r1 →fr wy →po r2 →fr wx` — two `fr` (`eco`) segments. Allowed.
- `R`: cycle `wx →po wy1 →co wy2 →po rx →fr wx` — `co` and `fr`, two `eco` segments. Allowed.
- `CO-IRIW`: cycle `r1 →po r2 →fr w2 →sw r3 →po r4 →fr w1 →sw r1` — two `fr` segments separated by `hb`. Allowed.
- contrast `MP-relacq` (correctly forbidden): `wdata →po wflag →sw rflag →po rdata →fr wdata` — one `hb` path, **one** `fr`. This *is* `hb;eco?`, so `irreflexive(hb;eco?)` still forbids it.

The single-layer encoding cannot tell a one-`eco`-segment cycle from a
two-`eco`-segment cycle — they are both "a cycle in `po∪sw∪co∪fr`" — so it
forbids all of them. That is the entire bug for family (a). (Note: the corpus's
"RA" is the *bare* release/acquire model — coherence only — **not** full RC11; it
deliberately has **no** `acyclic(po∪rf)` no-thin-air axiom, which is why `LB/RA`
is expected *allowed*.)

**Why `irreflexive(hb;eco?)` is the correct RA axiom.** It is the literal RC11
release/acquire coherence condition (PLDI 2017 §3). It is sound for SB/R/IRIW
(allows them — multicopy-atomicity is not assumed under RA) and still forbids
MP-relacq/CO-MP/CO-WRC (single `eco` segment).

**Estimated SMT cost vs. the current layer.**

| | current RA layer | `irreflexive(hb;eco?)` |
|---|---|---|
| integer vars | `n` layer vars (one per event) | `n²` reachability/order vars for `hb+` and `eco+` (or 2 layers + composition) |
| clauses | `O(\|po\|+\|sw\|+\|co\|+\|fr\|)` implications | `O(n³)` transitive-closure clauses (`hb`, `eco`) **+** `O(n²)` composition (`hb;eco`) **+** `n` irreflexivity |
| risk | low (already shipped) | medium — transitive closure is the expensive, error-prone part; must not couple distinct locations |

`hb+` and `eco+` need genuine transitive closure (the layer trick gives
acyclicity but **not** reachability, and we need reachability to compose
`hb;eco`). Practical encodings: (i) Floyd–Warshall-style boolean reachability
matrices `hbReach[a][b]`, `ecoReach[a][b]` with `O(n³)` transitivity clauses, or
(ii) two layered orders plus an explicit `hb;eco` composition relation. Either is
materially heavier than today's single layer; for the corpus (`n ≤ 9`) it is
fine, but it is the highest-effort item in this whole document.

### (b) Well-formedness pins a global order: `acyclic(po ∪ rf ∪ co)` for *all* models

**Cells:** `LB/RA`, `S/PSO`, `S/RA`, `S/WEAKEST`, `2+2W/PSO`, `2+2W/RA`,
`2+2W/WEAKEST`, `3.LB/RA`, plus the well-formedness lock on the three WEAKEST LB
cells (`LB/WEAKEST`, `3.LB/WEAKEST`, `LB-acqrel/WEAKEST`). **11 cells total.**

**Why the current encoding over-forbids.** `encodeWellFormedness`
(EventStructureEncoder.java:109) builds a *single global total order* `pos` over all
events and forces it to respect:

1. **every** `po` edge — including `W→W` and `W→R` (`poConstraints`, line 193);
2. the chosen `rf`: `rf(w,r) ⇒ pos(w) < pos(r)` (line 135);
3. `co` as the projection of `pos` onto same-location writes,
   `co(a,b) ⟺ pos(a) < pos(b)`, with the corpus chain pinned (lines 153, 208).

A total order respecting a relation exists iff that relation is acyclic, so
well-formedness **is** `acyclic(po ∪ rf ∪ co)` — independent of which model's
consistency axiom runs afterwards. `gatedWellFormedness`
(MinimalWitnessExtractor.java:221) does the identical thing over `active`
events, so the blast radius of any change here spans **both** files.

`acyclic(po ∪ rf ∪ co)` is an SC-strength backstop. It forbids two distinct
weak behaviours that PSO/RA/WEAKEST permit:

- **cross-location `W→W` (and `W→R`) reorder** — the `S`/`2+2W` shape. Example
  `S`: `wx1 →co wx2 →po(W→W) wy1 →rf ry →po(R→W) wx1` is a `po∪rf∪co` cycle, so
  no global `pos` exists → forbidden by well-formedness *before any consistency
  axiom runs*. `2+2W`: `wx2 →co wx1 →po(W→W) wy1 →co wy2 →po(W→W) wx2`, a
  `po∪co` cycle.
- **load buffering** — the `LB`/`3.LB` shape. Example `LB`:
  `r1 →po(R→W) wy →rf r2 →po(R→W) wx →rf r1`, a `po∪rf` cycle. The offending
  edge here is the `rf`-forward edge `pos(w) < pos(r)` (mechanism 2), *not* a
  `W→W` edge.

These are two **separable** sub-mechanisms (this matters enormously in §3):
the `S`/`2+2W` cycles use `W→W`/`R→W` `po` edges; the `LB` cycles use `R→W` `po`
edges + the `rf`-forward edge. Dropping `W→W`/`W→R` from `pos` kills the
`S`/`2+2W` cycles **without touching** the LB cycles.

**What relaxed well-formedness should be.** Well-formedness should assert that a
*real execution exists*, not that an *SC-style global linearization* exists. The
genuinely-required well-formedness invariants are:

- `co` is a per-location strict total order over same-location writes, pinned to
  the corpus modification order (keep — but as its own relation, not a `pos`
  projection);
- each read reads exactly one same-location write with a matching value
  (keep — the value constraint and the exclusive-or over `rf` choices);
- *per-location* coherence (`acyclic(po-loc ∪ rf ∪ co ∪ fr)`) — **already**
  enforced for every model by `coherencePerLocation` since Pass 1, so it does
  *not* need to live in well-formedness.

What should be **removed or weakened** is the global `pos` linearization of
`po ∪ rf ∪ co`. There are two design points (§3 picks one):

- **minimal (recommended):** keep the global `pos` but drop **cross-location**
  `W→W` and `W→R` `po` edges from it (keep `R→W`, `R→R`, the `rf`-forward edge,
  and `co`). This frees the `S`/`2+2W` reorder while leaving LB blocked.
- **aggressive:** keep only per-thread `po`, give `co` its own per-location
  order, and drop the `rf`-forward edge. This *also* frees LB — and triggers the
  OOTA collision in §4.

**Why same-location `W→W` stays safe under either relaxation.** `CoWW` is a
same-thread, same-location `W→W` whose `co` contradicts `po`. Dropping `W→W` from
the global `pos` would stop well-formedness forbidding it — **but**
`coherencePerLocation` (Pass 1) re-imposes `acyclic(po-loc ∪ co)` per location
and catches it. This is precisely why Pass 1 had to land first: it is the
safety net that makes relaxing the global order possible at all.

**Why PSO/RA permit the `W→W` reorder and SC/TSO do not.** Under
SC, `ppo = po` (all pairs preserved); under TSO, `ppo = po \ (W→R)` (store→load
relaxed only); under PSO, `ppo = po \ (W→R ∪ W→W)` (store→store *also* relaxed)
— Alglave, Maranget & Tautschnig, *Herding Cats*, TOPLAS 2014, §4.4 and the
architecture instances in §6. So a cross-location `W→W` (the `S`/`2+2W` relaxant)
is preserved by SC and TSO but relaxed by PSO/RA/WEAKEST. After a minimal
relaxation, `consistencySC` (full `po` in its layer) and `consistencyTSO`
(`skipWriteRead=true, skipWriteWrite=false` → keeps `W→W`) **re-impose** the
ordering they need, so `S` and `2+2W` stay correctly forbidden under SC/TSO.
Verified per-cell in §4.

### (c) WEAKEST over-forbids LB: `acyclic(po ∪ rf)` vs. justification-coherence

**Cells:** `LB/WEAKEST`, `3.LB/WEAKEST`, `LB-acqrel/WEAKEST` — and **all three
are also well-formedness-blocked** (family b), i.e. *double-locked*.

**Correction to the prior plan.** The working plan listed family (c) as
"`LB`, `3.LB`, `S`, `2+2W` under WEAKEST". That is wrong on two counts:

- **`S/WEAKEST` and `2+2W/WEAKEST` are NOT WEAKEST-consistency cases — they are
  family (b).** Proof: `consistencyWEAKEST` (AxiomaticConsistency.java:129) is
  `acyclic(po ∪ rf)` + the `jf`-coherence loop + `coherencePerLocation`. It calls
  neither `addCo` nor `addFr` into its global layer. The `S` cycle requires the
  `co` edge `wx1→wx2`, and the `2+2W` cycle requires `co` edges on both
  locations; with no global `co`/`fr`, WEAKEST-consistency has *no cycle* on
  these shapes (checked: `S` reduces to chain `wx2→wy1→ry→wx1`; `2+2W` reduces to
  two disjoint `po` edges; per-location layers have no cross-location coupling).
  So WEAKEST-consistency **cannot** forbid `S`/`2+2W`. The only thing that does is
  well-formedness. ⇒ family (b).
- `LB-acqrel/WEAKEST` *is* a WEAKEST LB case and was missing from the list.

**Why WEAKEST-consistency over-forbids the real LB cases.**
`consistencyWEAKEST` includes `acyclic(po ∪ rf)` (`addPo(false,false)` +
`addRf(false,false)`, all `rf` edges in one layer). That flatly forbids **all**
load buffering — the `LB` cycle `r1→wy→r2→wx→r1` is a `po∪rf` cycle. But the
WEAKEST model of Chakraborty & Vafeiadis (*Grounding Thin-Air Reads with Event
Structures*, POPL 2019, §3–4) is built precisely to *allow* genuine LB while
*forbidding* out-of-thin-air (OOTA). It does so over an **event structure** with
a **justification** relation `jf`: an execution is consistent iff its reads are
*well-justified* — each read's value is justified by a write through a chain that
does **not** route through the read's own (po-after) dependencies. Real LB is
well-justified (the written value does not depend on the read); a fake-dependency
OOTA cycle is self-justifying and rejected (POPL 2019 §4, the
`justifies`/`well-justified` conditions).

`acyclic(po ∪ rf)` is a crude over-approximation of "no OOTA": it rejects every
`po∪rf` cycle, including the well-justified ones. The `jf`-coherence loop already
present (AxiomaticConsistency.java:139) only constrains **non-relaxed** reads
reading stale same-location writes; it does not implement the
dependency-sensitive justification check, and all LB-family reads are `RELAXED`,
so the loop is a no-op for them.

**`jf`-coherence vs. `po∪rf` acyclicity.** The distinction is *which* `po∪rf`
cycles to forbid: `acyclic(po∪rf)` forbids all; the WEAKEST model forbids only
those whose read-justification is circular through syntactic dependencies. This
**requires the event structure to carry dependency edges** (addr/data/ctrl) so a
"fake dependency" can be distinguished from a real one. The current WEV
`EventStructure` does not carry them (see §4 — this is why the corpus encodes
`LB`, `LB-fake-dep`, and `OOTA-cycle` with one identical structure).

### (d) TSO/PSO ignore release/acquire ordering → under-forbid

**Cells:** `CO-MP/PSO`, `MP-relacq/PSO` (these two are the *same* event
structure — `buildMP(REL, ACQ)` — with identical expected outcomes, so they are
one bug counted twice).

**Why the current encoding under-forbids.** `consistencyPSO` uses
`addPo(skipWriteRead=true, skipWriteWrite=true)` and `addRf(externalOnly=true)`.
It therefore drops the `W→W` `po` edge `wdata→wflag` **even when both writes are
`RELEASE`**, and it treats `rf` purely as an external edge with no
release/acquire `sw` semantics. The MP-violation cycle
`wdata →po(W→W) wflag →rf rflag →po(R→R) rdata →fr wdata` is broken at the
dropped `W→W` edge, so PSO **allows** it — but the literature forbids it
(`exp(F,F,F,F,U)`), because a release store orders prior stores (release fence /
release sequence) and an acquire load orders subsequent loads, so the writer
*cannot* reorder `wdata`/`wflag`. Contrast `MP-rel` (`REL`,`RLX`) and `MP-acq`
(`RLX`,`ACQ`), both `exp(...,A,...)` under PSO: there the missing synchronisation
(no acquire, or no release) leaves a legal reorder, so PSO correctly allows them.

This family is **not addressed by any pass in the current plan** and points the
opposite way from families (a)–(c): it is *under*-forbidding, fixable in
`consistencyPSO`/`consistencyTSO` by making `ppo` respect release/acquire fences
(do not relax `W→W`/`W→R` across a release write or an acquire read; or add `sw`
and the release-sequence ordering). It touches PSO/TSO only.

---

## 2. Cell × root-cause matrix

`B` = blocked by well-formedness `acyclic(po∪rf∪co)`. `C(M)` = blocked by model
`M`'s consistency axiom. A cell is *forbidden* iff **any** lock is set; to flip it
to *allowed* every active lock must be removed.

| # | Cell | Expected | Current | Active lock(s) | Family | Fix(es) needed |
|---|------|----------|---------|----------------|--------|----------------|
| 1 | `SB/RA` | ALLOWED | FORBIDDEN | C(RA) | (a) | 2a |
| 2 | `LB/RA` | ALLOWED | FORBIDDEN | B (rf-fwd) | (b·LB) | 2b |
| 3 | `LB/WEAKEST` | ALLOWED | FORBIDDEN | B (rf-fwd) + C(WEAKEST) | (b·LB)+(c) | 2b **and** 3 |
| 4 | `R/RA` | ALLOWED | FORBIDDEN | C(RA) | (a) | 2a |
| 5 | `S/PSO` | ALLOWED | FORBIDDEN | B (W→W) | (b·WW) | 2b |
| 6 | `S/RA` | ALLOWED | FORBIDDEN | B (W→W) | (b·WW) | 2b |
| 7 | `S/WEAKEST` | ALLOWED | FORBIDDEN | B (W→W) | (b·WW) | 2b |
| 8 | `2+2W/PSO` | ALLOWED | FORBIDDEN | B (W→W) | (b·WW) | 2b |
| 9 | `2+2W/RA` | ALLOWED | FORBIDDEN | B (W→W) + C(RA) | (a)+(b·WW) | 2a **and** 2b |
| 10 | `2+2W/WEAKEST` | ALLOWED | FORBIDDEN | B (W→W) | (b·WW) | 2b |
| 11 | `CO-MP/PSO` | FORBIDDEN | ALLOWED | *under*-forbids | (d) | PSO rel/acq pass |
| 12 | `CO-IRIW/RA` | ALLOWED | FORBIDDEN | C(RA) | (a) | 2a |
| 13 | `3.LB/RA` | ALLOWED | FORBIDDEN | B (rf-fwd) | (b·LB) | 2b |
| 14 | `3.LB/WEAKEST` | ALLOWED | FORBIDDEN | B (rf-fwd) + C(WEAKEST) | (b·LB)+(c) | 2b **and** 3 |
| 15 | `MP-relacq/PSO` | FORBIDDEN | ALLOWED | *under*-forbids | (d) | PSO rel/acq pass (= #11) |
| 16 | `LB-acqrel/WEAKEST` | ALLOWED | FORBIDDEN | B (rf-fwd) + C(WEAKEST) | (b·LB)+(c) | 2b **and** 3 |

**Per-family tally:** (a) 3 sole + 1 shared = 4 touched · (b) 11 · (c) 3 (all
double-locked) · (d) 2. Single-locked: 1,2,4,5,6,7,8,10,12,13 (10 cells).
Double-locked: 3,9,14,16 (4 cells). Under-forbid: 11,15 (2 cells).

**Key consequences of the lock structure:**

- The four cells the prior plan grouped as "RA/PSO fr-cycle, fix in
  AxiomaticConsistency" (`S/PSO`, `S/RA`, `2+2W/PSO`, `2+2W/RA`) are mostly **B**,
  not **C**. Only `2+2W/RA` carries a real RA-consistency lock; the other three
  are pure well-formedness. The naive "remove global `co`/`fr` from RA" would not
  touch them at all, and (worse) would break the currently-correct `MP-relacq/RA`
  / `CO-MP/RA` / `CO-WRC/RA` (single-`eco`-segment cycles that `irreflexive(hb;eco?)`
  *should* keep forbidding). So that step is wrong twice over.
- The WEAKEST LB cells (3,14,16) are double-locked: Pass 3 alone changes nothing
  for them (well-formedness still forbids); they need Pass 2b *and* Pass 3.
- Families (a) and (b) are independent except at `2+2W/RA`, which needs both.

---

## 3. Revised pass plan

The current Pass 2/3 plan ("RA/PSO global fr+co split → 16→9") is broken: it
mislocates 8 of its 7 target cells (they are well-formedness, not RA/PSO
consistency), its proposed RA edit regresses correct cells, and it omits family
(d) entirely. Revised sequence:

### Pass 2a — RA coherence: `irreflexive(hb;eco?)` *(AxiomaticConsistency only)*
- Replace the single `po∪sw∪co∪fr` layer in `consistencyRA` with RC11 release/acquire
  coherence `irreflexive(hb;eco?)`, `hb=(po∪sw)+`, `eco=(rf∪co∪fr)+` (PLDI 2017 §3).
- Keep `coherencePerLocation` (subsumed, but cheap and an independent safety net).
- **Flips:** `SB/RA`, `R/RA`, `CO-IRIW/RA` → match (−3). Removes the C(RA) lock
  from `2+2W/RA` (still B-locked until 2b).
- **Blast radius:** RA column only. Risk = the transitive-closure encoding (§1a),
  not the model semantics. Must keep `MP-relacq/RA`, `CO-MP/RA`, `CO-WRC/RA`
  forbidden (one-`eco`-segment ⇒ still caught). **16 → 13.**

### Pass 2b — well-formedness relaxation *(encoder + gated path + safety net)*
Touches `EventStructureEncoder.encodeWellFormedness` **and**
`MinimalWitnessExtractor.gatedWellFormedness` (identical change in both).
**Two variants — pick minimal:**

- **2b-min (recommended):** drop **cross-location `W→W` and `W→R`** `po` edges
  from the global `pos` order; keep `R→W`, `R→R`, the `rf`-forward edge, `co`.
  - **Flips:** `S/{PSO,RA,WEAKEST}` (−3), `2+2W/{PSO,WEAKEST}` (−2), and
    `2+2W/RA` (−1, given 2a removed its C(RA) lock). Total **−6**.
  - **Regressions:** **none** (proven in §4 — LB shape stays B-locked because its
    cycle uses `R→W`+`rf`, not `W→W`; SC/TSO re-impose `W→W`; `coherencePerLocation`
    re-imposes same-location order). **13 → 7.**
  - Leaves: 5 LB-family cells + 2 family-(d) cells.

- **2b-aggressive (not recommended):** per-thread `po` only, `co` as its own
  per-location relation, drop the `rf`-forward edge. *Also* frees LB under RA, but
  **regresses `LB-fake-dep/RA` and `OOTA-cycle/RA`** (+2) — see §4. Net for the
  LB-RA shape is a wash or worse, and it makes the encoder's well-formedness
  semantically muddier. Only worth it if the corpus is first given dependency
  edges (below).

### Pass 3 — WEAKEST justification-coherence *(AxiomaticConsistency + corpus)*
- Replace `acyclic(po∪rf)` in `consistencyWEAKEST` with a dependency-sensitive
  `jf`/well-justified check (POPL 2019 §3–4).
- **Blocked on a corpus change.** Today `LB`, `LB-fake-dep`, and `OOTA-cycle` are
  the *same* `EventStructure` (`buildLB(RLX,RLX)`) with *opposite* expected
  outcomes. No axiom can satisfy both. Pass 3 only pays off if the WEV
  `EventStructure` first gains **dependency edges** so the OOTA twins carry a real
  self-justifying dependency the `jf` check can reject while admitting genuine LB.
- **Without** the corpus change: making the LB shape *allowed* under WEAKEST flips
  `LB/WEAKEST`, `3.LB/WEAKEST`, `LB-acqrel/WEAKEST` → match (−3) but regresses
  `LB-fake-dep/WEAKEST`, `OOTA-cycle/WEAKEST` (+2): net −1, and it's a
  *correctness illusion* (we'd be "passing" LB by also wrongly passing OOTA).
- Recommended framing: Pass 3 = (3a) add dependency edges to `EventStructure` +
  give the OOTA twins real fake-dependency structures; then (3b) the `jf` axiom.
  This is the genuinely research-grade item and the right thing to defer/scope
  carefully for the POPL submission.

### Pass 2c (new) — TSO/PSO release/acquire fences *(AxiomaticConsistency only)*
- Make `ppo` in `consistencyTSO`/`consistencyPSO` respect release/acquire: do not
  relax `W→W`/`W→R` across a release write or acquire read (or add `sw` +
  release-sequence ordering). **Flips:** `CO-MP/PSO`, `MP-relacq/PSO` → match (−2).
- Independent of (a)/(b)/(c); small, well-scoped. Could run before or after 2b.

**Projected trajectory (no corpus change):**
16 →(2a) 13 →(2b-min) 7 →(2c) 5 →(3b, LB shape) net −1 with +2 OOTA regression ⇒ **6**.
Of the final ~5–6, **2 are family-(d)** if 2c is skipped and **4 are the
LB/OOTA encoding collision** if Pass 3 runs without the corpus change. With the
corpus dependency edges, the LB family becomes genuinely fixable and the floor
drops toward 2 (just the hard `RWC/RA = U` style judgement calls).

---

## 4. Pass 2b risk table

Relaxing well-formedness only **removes** constraints, so a currently-*allowed*
cell can never regress (you cannot make a SAT model UNSAT by deleting clauses).
**Only currently-matching FORBIDDEN cells can regress**, and only if the model's
*consistency* axiom does not independently re-forbid the wired execution after
the global order is relaxed. SC is always safe (`consistencySC` is full
`acyclic(po∪rf∪co∪fr) ⊇` well-formedness). The table below covers every
currently-matching FORBIDDEN cell, grouped; "2b-min" and "2b-agg" columns give
the verdict under each variant.

| Test (currently-matching FORBIDDEN cells) | Re-forbidden by consistency after relax? | 2b-min | 2b-agg | Note |
|---|---|---|---|---|
| `CoRR/RW/WR/WW` — all of SC,TSO,PSO,RA,WEAKEST | yes — `coherencePerLocation` (per-loc, Pass 1) | **safe** | **safe** | per-location, independent of global order |
| `MP/SC,TSO` | yes — TSO keeps `W→W`; cycle survives | **safe** | **safe** | |
| `LB/SC,TSO,PSO` | yes — TSO/PSO keep `R→W`; external `rf` kept | **safe** | **safe** | LB blocked by ppo, not just WF |
| `IRIW/SC,TSO,PSO` | yes — `R→R` + `fr` + `rfe` all kept | **safe** | **safe** | |
| `WRC/SC,TSO,PSO` | yes — `R→W`,`R→R`,`rfe`,`fr` kept | **safe** | **safe** | |
| `ISA2/SC,TSO` | yes — TSO keeps `W→W` (`wx→wy`) | **safe** | **safe** | ISA2/PSO is expected-A (already allowed) |
| `S/SC,TSO` | yes — TSO keeps `W→W` (`wx2→wy1`) | **safe** | **safe** | the very edge 2b-min keeps in TSO's layer |
| `2+2W/SC,TSO` | yes — TSO keeps `W→W` | **safe** | **safe** | |
| `R/SC`, `RWC/SC`, `3.SB/SC` | yes — SC re-imposes everything | **safe** | **safe** | |
| `CO-MP/SC,TSO,RA`, `MP-relacq/SC,TSO,RA` | yes — `W→W`(TSO)/`sw`+`fr`(RA), one-`eco` cycle | **safe** | **safe** | RA via `hb;eco?` after 2a |
| `CO-WRC/SC,TSO,PSO,RA` | yes — `R→W`/`R→R`/`fr`/`sw` kept | **safe** | **safe** | |
| `CO-IRIW/SC,TSO,PSO` | yes — `R→R`+`fr`+`sw` kept | **safe** | **safe** | CO-IRIW/RA is the family-(a) mismatch |
| `3.LB/SC,TSO,PSO` | yes — `R→W`+`rfe` kept | **safe** | **safe** | 3.LB/RA flips (intended); blocked by WF only there |
| `MP-rel/SC,TSO`, `MP-acq/SC,TSO` | yes — TSO keeps `W→W` | **safe** | **safe** | |
| `LB-acqrel/SC,TSO,PSO` | yes — `R→W`+`rfe` kept | **safe** | **safe** | LB-acqrel/WEAKEST is the mismatch |
| **`LB-fake-dep/RA`** | **no** — RA drops RLX `rf`; forbidden by **WF only** | **safe** | **REGRESS** | same ES as `LB`; flips iff `rf`-fwd dropped |
| **`OOTA-cycle/RA`** | **no** — same as above | **safe** | **REGRESS** | same ES as `LB` |
| `LB-fake-dep/WEAKEST`, `OOTA-cycle/WEAKEST` | yes under 2b (WEAKEST `acyclic(po∪rf)` still bites) | **safe** | **safe** | but **regress at Pass 3** if LB shape freed |

**HIGH-risk cells (require by-hand textbook re-validation before proceeding):**

- `LB-fake-dep/RA`, `OOTA-cycle/RA` — *only* under **2b-aggressive**. They share
  the exact `buildLB(RLX,RLX)` event structure with `LB` (expected ALLOWED under
  RA) but expect FORBIDDEN. Because they are the *same SMT problem*, no axiomatic
  change can keep them forbidden while flipping `LB/RA` to allowed. Under
  **2b-min they stay safe** (the LB shape remains well-formedness-blocked via the
  retained `rf`-forward edge). This single fact is the strongest argument for
  2b-min over 2b-aggressive.
- `LB-fake-dep/WEAKEST`, `OOTA-cycle/WEAKEST` — safe under any 2b, but become the
  same HIGH-risk regression at **Pass 3** if WEAKEST is relaxed to allow LB
  without first giving the corpus dependency edges. Re-validate against POPL 2019
  before Pass 3.

**Everything else is LOW risk:** the relaxation drops only the *global*
`po∪rf∪co` order; SC re-imposes it in full, TSO/PSO re-impose `W→W` and the
preserved-order/`rfe`/`fr` edges their ppo keeps, and `coherencePerLocation`
re-imposes same-location order. The 6 intended flips (`S×3`, `2+2W×3`) and the
non-regressions were each checked by reconstructing the wired cycle and removing
the global-order edges; no LOW-risk cell loses its consistency-level lock.

---

## 5. Decision request

Before any code, I need a direction on three forks:

1. **Pass 2b variant.** Recommend **2b-min** (drop cross-location `W→W`/`W→R`
   from the global `pos`): fixes 6 cells, **zero regressions**, leaves the LB
   family cleanly isolated. 2b-aggressive buys nothing extra without a corpus
   change and regresses 2 cells. Confirm 2b-min?

2. **Sequence.** Recommend **2a → 2b-min → 2c**, deferring Pass 3. That reaches
   **16 → 5** with zero regressions and no LB/OOTA correctness illusion. (2a must
   precede 2b for `2+2W/RA`; 2c is independent.) The prior "2a then 2b
   sequentially" is fine *as far as it goes* but (i) needs 2b pinned to the
   minimal variant, (ii) should add 2c for family (d), and (iii) should treat
   Pass 3 as gated on a corpus change, not a same-turn axiom edit. Confirm, or
   do you want a different order?

3. **LB family / Pass 3 scope.** The LB family (5 cells) cannot improve net
   without giving the WEV `EventStructure` dependency edges and giving
   `LB-fake-dep`/`OOTA-cycle` distinguishing structures. Options: **(i)** defer
   the LB family entirely for this submission and document the
   `acyclic(po∪rf)`-approximation as a known limitation; **(ii)** invest in
   dependency-carrying event structures + `jf`-coherence (the real POPL
   contribution, larger scope). Which do you want, and is it in-scope now or a
   later prompt?

No code will be written until these are settled.

---

## 6. Results (recorded after implementation)

Decisions taken: **2b-min**, sequence **2a → 2b-min → 2c**, Pass 3 (LB family /
`jf`-coherence) deferred to a dedicated session. All three passes landed with
**12/12 unit tests green** and each atlas run inside the 600 s budget
(~484–487 s). The predicted trajectory held exactly.

### Mismatch trajectory: 16 → 13 → 7 → 5

| After pass | Mismatches | Matched | Cells that flipped to MATCH |
|---|---|---|---|
| (start, post-Pass-1) | 16 | 102 | — |
| **2a** — RA `irreflexive(hb;eco?)` | **13** | 105 | `SB/RA`, `R/RA`, `CO-IRIW/RA` |
| **2b-min** — drop cross-loc `W→W`/`W→R` from global `pos` | **7** | 111 | `S/PSO`, `S/RA`, `S/WEAKEST`, `2+2W/PSO`, `2+2W/RA`, `2+2W/WEAKEST` |
| **2c** — `irreflexiveHbEco` into PSO | **5** | 113 | `CO-MP/PSO`, `MP-relacq/PSO` |

Matched-cell count climbed `102 → 105 → 111 → 113`; every delta is exactly the
intended flips for that pass, so no unrelated cell moved in either direction.

### Files changed

- `AxiomaticConsistency.java` — new `irreflexiveHbEco(active)` (per-pair `hb`/`eco`
  boolean reachability matrices + `O(n³)` transitive-closure clauses, since the
  layer trick gives acyclicity but not the reachability needed to compose
  `hb;eco`); `consistencyRA` rewritten to `irreflexiveHbEco + coherencePerLocation`;
  `consistencyPSO` gains `irreflexiveHbEco` on top of its ppo layer (Pass 2c).
- `EventStructureEncoder.java` — new `relaxedPoConstraints()` + `droppedCrossLocation()`;
  `encodeWellFormedness` now calls the relaxed variant. `poConstraints()` left intact
  for relation extraction.
- `MinimalWitnessExtractor.java` — `gatedWellFormedness` po loop applies the same
  cross-location drop (the two well-formedness encoders must relax identically).

### Risk-table outcome: zero regressions

Every cell flagged in §4 was re-checked against the post-pass atlas and **stayed
non-regressed**:

- **`LB-fake-dep/RA`, `OOTA-cycle/RA`** (the decisive 2b-min-vs-aggressive cells)
  — **still FORBIDDEN** ✓. 2b-min retains the `rf`-forward edge, so the LB shape
  stays well-formedness-blocked; 2b-aggressive would have regressed these.
- `S/SC`, `S/TSO`, `2+2W/SC`, `2+2W/TSO` — still FORBIDDEN ✓ (SC keeps full `po`;
  TSO keeps `W→W` in its layer). Confirmed by `PSO\TSO : S(4), 2+2W(4)`.
- All `LB/{SC,TSO,PSO}`, `IRIW/*`, `WRC/*`, `LB-fake-dep/{SC,TSO,PSO,WEAKEST}`,
  `OOTA-cycle/{SC,TSO,PSO,WEAKEST}` — still FORBIDDEN ✓.
- PSO's allowed set (`S`, `2+2W`, `MP`, `MP-rel`, `MP-acq`, `SB`, `ISA2`, `R`)
  — still ALLOWED ✓ (Pass 2c's `hb;eco?` only forbids one-`eco`-segment
  release/acquire cycles; plain relaxed `rf` keeps two `eco` segments). PSO's
  ppo-forbidden set (`IRIW`, `WRC`, `CO-IRIW`, `CO-WRC`) — still FORBIDDEN ✓.
- RA cells that had to keep being forbidden under the new axiom (`MP-relacq/RA`,
  `CO-MP/RA`, `CO-WRC/RA`) — still FORBIDDEN ✓.

### Remaining 5 (all LB family — reserved for Pass 3)

`LB/RA`, `LB/WEAKEST`, `3.LB/RA`, `3.LB/WEAKEST`, `LB-acqrel/WEAKEST`. These cannot
improve on the count without giving the WEV `EventStructure` dependency edges and
distinguishing the OOTA twins (`LB-fake-dep`, `OOTA-cycle` share `LB`'s exact
structure) — the `jf`-coherence work scoped in §3 / §5 option (ii), to be done in
a fresh session.

### Known artifact (not a regression)

Adding the `hb;eco` reachability matrices to RA *and* PSO made those
witness-searches heavier, so the **separation atlas** shows more `T/O` and a few
separations that previously solved now time out (e.g. `ISA2` under `PSO\TSO`,
`CO-MP`/`CO-WRC` under `WEAKEST\RA`). The **validation column is exact** (this is
the headline 16→5); the separation atlas is a best-effort witness search bounded
by the per-call budget. Recover by raising `PER_CALL_BUDGET_MS` or memoizing the
reachability encoding if the separation matrix matters for the submission.
