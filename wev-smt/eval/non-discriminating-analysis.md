# Why the 229 non-discriminating fake-dependency tests don't discriminate

Companion analysis to `eval/sdep-discriminating-all-fake-tests.csv`. Of the 231 corpus
tests that carry ≥1 fake (`isSemantic=false`) dependency edge, **2 discriminate**
(`LB+datas`, `DETOUR0236` — both PPC load-buffering) and **229 do not**. This report
classifies every fake edge in those 229 tests and explains the invariance.

A fake edge can only move a WEAKEST verdict if, when relabelled semantic, it introduces
(or, when relabelled fake, removes) a cycle in `jf ∪ sdep` — the relation the
`jfCoherence` axiom forbids. A **necessary** condition is that the edge lie on a cycle of
the combined `dep ∪ rf` graph (the load-buffering shape). The classification below is
computed by re-parsing each test, recomputing `dep ∪ rf` strongly-connected components
(Kosaraju, identical to the sweep), and checking per-edge cycle membership. It is an
independent recomputation of the sweep's `fake_edge_verdict_relevant` flag.

## Category counts

**229 non-discriminating tests · 308 fake edges** (the other 5 of the 313 total live in
the 2 discriminating tests).

| category | definition | fake edges | tests |
|---|---|---|---|
| **A** | not on any `dep ∪ rf` cycle — verdict-irrelevant | **308 (100%)** | **229 (100%)** |
| **B** | on a `dep ∪ rf` cycle, but the cycle is already forbidden by CoherencePerLocation | 0 | 0 |
| **C** | on a `dep ∪ rf` cycle, but the cycle is forbidden for other reasons | 0 | 0 |
| **D** | other | 0 | 0 |

**Every fake edge in every non-discriminating test is category A.** No fake edge in the
229 tests touches a `dep ∪ rf` cycle, so categories B, C, and D are empty. (This exactly
matches the sweep: the only two tests whose fake edge is verdict-relevant are the two
discriminating ones.)

## Why A is the entire population — and why B/C/D are empty

For a fake edge off every `dep ∪ rf` cycle, relabelling it fake↔semantic cannot create or
destroy any cycle in `jf ∪ sdep` (since `jf ⊆ rf`-justification and the edge's endpoints
share no non-trivial SCC in `dep ∪ rf`). The `jfCoherence` axiom therefore returns the
identical result under all three configurations, so `none = all-deps-semantic = current`.
The fake edge is inert.

B/C/D require a fake edge **on** a `dep ∪ rf` cycle. In this corpus the only cyclic
(load-buffering) topology that reaches the solver with an intact cycle through its fake
dependency is the 2 discriminating PPC tests. Every other fake-carrying test is either
genuinely acyclic in `dep ∪ rf`, or a load-buffering shape whose cycle failed to
reconstruct (see the caveat below). Hence no non-discriminating test ever exposes a fake
edge to a live cycle, and B/C/D are structurally impossible here rather than merely
unobserved.

## Verdict-invariance split (secondary)

Non-discrimination means `none = all-deps-semantic = current`; that common verdict is:

| common verdict | tests | note |
|---|---|---|
| ALLOWED (all 3 configs) | 170 | fake dep off-cycle; nothing forbids the outcome |
| FORBIDDEN (all 3 configs) | 59 | forbidden by **sdep-independent** axioms |

All 59 FORBIDDEN tests are forbidden even under `none` (where `sdep = ∅` and `jfCoherence`
degenerates to `acyclic(jf)`). Because the forbidding survives with sdep emptied, it is
provably **not** caused by the fake dependency — it comes from coherence-per-location /
`acyclic(jf)` / fence ordering. All 59 are PPC, dominated by the `DETOUR` coherence-detour
family (30) whose forbidden outcome is a per-location coherence contradiction independent
of the fake `r^r` data dep sitting on a forward leg.

*(Note: an earlier structural coherence-per-location probe over the wired candidate
produced false positives — the encoder quantifies over `co`, whereas the probe fixed the
wired `co` — so this report attributes the 59 by the sound "forbidden under `none`" test
plus test-family, not by that probe.)*

## Breakdown

**By architecture:** ARM 71 (all ALLOWED) · PPC 158 (99 ALLOWED, 59 FORBIDDEN).

**By fake pattern:** `r^r` (self-XOR) for all 229 — it is the only fake idiom in the corpus.

**By dependency type of the fake edge:** DATA and ADDR only (CTRL fakes: 0), all category A.

**By test-shape family** (name-based; sums to 229):

| family | tests | ALLOWED | FORBIDDEN |
|---|---|---|---|
| DETOUR | 92 | 62 | 30 |
| MP | 48 | 40 | 8 |
| LB | 35 | 23 | 12 |
| S | 15 | 6 | 9 |
| Z6 | 14 | 14 | 0 |
| ISA2 | 5 | 5 | 0 |
| R | 4 | 4 | 0 |
| WRC / WRW / SB / PPOCA | 2 each | 8 | 0 |
| WRR / W / RSDWI / L103 / CAS / A015 / 2+2W | 1–2 each | 9 | 0 |

## Representative examples

**Category A — genuinely acyclic (the fake dep is on a forward leg):**
- `herdtools7/catalogue/aarch64/tests/MP+rel+addr-po-loc-addr.litmus` (ARM, MP, ALLOWED) — the fake `r^r` address dep is on the reader's leg; MP has no `dep ∪ rf` cycle, so it is inert.
- `Dat3M/litmus/PPC/DETOUR0362.litmus` (PPC, DETOUR, FORBIDDEN) — 2 fake `r^r` DATA deps, both off-cycle; forbidden by a per-location coherence contradiction among its x-writes/y-reads regardless of sdep.
- `herdtools7/catalogue/aarch64/tests/SB+CAS-rfi-addr+DMBSY.litmus` (ARM, SB, ALLOWED) — SB is acyclic in `dep ∪ rf`; the fake addr dep never reaches a cycle.

**Category A — load-buffering shape whose cycle did not reconstruct:**
- `Dat3M/litmus/AARCH64/SYS/LB+dmb.sy+dataal.litmus` (ARM, LB, ALLOWED) — 6 events, but P0's cross-thread store is absent from the reconstructed structure, so both reads are wired to their initial writes (`rf = {0←1, 3←4}`) and no `dep ∪ rf` cycle forms; the fake DATA dep is therefore off-cycle.
- `Dat3M/litmus/AARCH64/TUTO/LB+datas.litmus` (ARM, LB, ALLOWED) — same effect (`rf = {0←1, 2←3}`, both from init).

**Categories B, C, D:** none (no non-discriminating test has a fake edge on a `dep ∪ rf` cycle).

## Caveat — the discriminating count (2) is a lower bound

35 of the 229 non-discriminating tests are load-buffering shapes (name contains `LB`),
which *should* carry a `dep ∪ rf` cycle through their fake dependency. At least **20** of
them are off-cycle only because the parser dropped a cross-thread store (program-writes <
threads), so the cycle never forms — the AArch64 `#`-immediate line-truncation limitation
(see `addr-dep-coverage-gap`). With those stores parsed, some LB-family tests would form a
live cycle through their `r^r` fake dep and could then join the discriminating set (as the
one cleanly-parsed PPC `LB+datas` already does). So the reported 2 discriminating tests is
a **lower bound**; a corpus with the `#`-immediate fix would likely surface more.

Nothing in this report modifies parser, detector, or semantic logic — it is a read-only
re-analysis of the sweep output plus a re-parse for per-edge cycle membership.
