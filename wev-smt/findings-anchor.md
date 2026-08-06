# findings-anchor.md — the true size of the external anchor

**Question.** The paper reports `\PublishedExampleAgreement = 31/34` agreement with the
source-paper classifications over the published validation set (Weakestmo 8 + PwT /
Leaky-Semicolon 6 + JMM causality 20). That number is presented in §6 as external
corroboration of the fake/real dependency detector. It is not: agreement counts every
example, including the ones whose verdict the detector plays no part in deciding.

The number that actually anchors the §6 contribution is: **on how many published examples
does the verdict change if `sdep` is replaced by `dep`** — i.e. how many published
examples carry a *fake* dependency edge lying on a load-buffering (`dep ∪ rf`) cycle,
where classifying that edge as real-vs-fake is what decides ALLOWED vs FORBIDDEN.

**Answer: 2 of 34.**

| source | examples | discriminating |
|---|---:|---:|
| Weakestmo (Chakraborty & Vafeiadis, POPL 2019) | 8 | **0** |
| PwT / Leaky Semicolon (Jeffrey et al., POPL 2022) | 6 | **2** |
| JMM causality (Pugh 2004 / Manson et al.) | 20 | **0** |
| **total** | **34** | **2** |

Discriminating tests: **`pwt/LBfd`** and **`pwt/LBxor`**. Both from one source, one
program shape, two spellings of the same cancellation idiom.

---

## Method

Driver: `wev.smt.ablation.AnchorDiscriminatingRun` (evaluation-only; frozen encoder +
`AxiomaticConsistency`, one fresh `SolverContext` per solve, `maxEvents=64`,
`timeoutSec=30` — bit-for-bit the machinery of `PaperExamplesRun` and
`SdepFakeDiscriminatingRun`; no parser, detector, or semantic logic touched).

```
mvn -o compile -DskipTests
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.ablation.AnchorDiscriminatingRun out=eval/anchor-discriminating.csv
```

Per-test output in `eval/anchor-discriminating.csv`. Two independent criteria were
recorded for each of the 34 examples:

- **Structural** (`fake_data_on_lb_cycle`) — the example carries a DATA edge the
  detector classified `isSemantic = false` *and* that edge lies on a cycle of
  `dep ∪ rf` (Kosaraju SCC over all dependency edges plus the wired reads-from). This
  is the load-buffering shape in which a fake-vs-real relabelling can open or close a
  thin-air justification cycle. `fake_any_on_lb_cycle` widens it to DATA/ADDR/CTRL.
- **Behavioural** (`discriminating`) — `verdict_current ≠ verdict_all-deps-semantic`,
  i.e. the WEAKEST verdict literally changes when `sdep := dep`. This is the criterion
  the headline number uses.

**The two criteria coincide exactly**: 2 structural, 2 behavioural, same two tests. No
example has a fake edge on an LB cycle without flipping, and none flips without one.
That is the expected relationship (a fake edge off every cycle cannot change which
`jf ∪ sdep` cycles exist) and it is worth having confirmed rather than assumed.

## The two discriminating tests

| test | fake pattern | dep edges | none | `sdep = dep` | `sdep` = detector | source verdict |
|---|---|---|---|---|---|---|
| `pwt/LBfd` | `LINEAR_CANCEL` | 2 DATA, both fake | ALLOWED | **FORBIDDEN** | **ALLOWED** | ALLOWED |
| `pwt/LBxor` | `r^r` | 2 DATA, both fake | ALLOWED | **FORBIDDEN** | **ALLOWED** | ALLOWED |

`LBfd` stores `r0 + 1 - r0` on both threads; `LBxor` stores `r0 ^ r0 ^ 1`. Both values
are the program constant `1` for every `r0`, so neither store's value flows from its
load. The detector nets the linear coefficient to 0 (resp. folds `r^r → 0`), marks both
DATA edges `isSemantic = false`, excludes them from `sdep`, and the `r0=r1=1` LB cycle
stays admissible — matching PwT. Under `sdep = dep` the same two edges close a
`jf ∪ sdep` cycle and the outcome is wrongly forbidden.

These are the only published examples in the set where that distinction is load-bearing.

## Why the other 32 do not anchor anything

Grouped by why the detector is irrelevant to their verdict:

**18 examples are dependency-insensitive outright** — all three sdep configurations
(`none`, `all-deps-semantic`, `current`) return the same verdict, so no dependency
analysis of any kind decides them:

> `weakestmo/{CoRR, CoRW, CoWR, LB, MP}`, `pwt/LB`,
> `jmm/{TC01, TC05, TC06, TC08, TC11, TC12, TC13, TC14, TC15, TC16, TC18, TC19}`

These are settled by coherence (CoRR/CoRW/CoWR, TC13), by the absence of any cycle to
close (LB, MP, TC01, TC11, TC12, TC15, TC16, TC19), by candidate-level
coherence-inconsistency (TC05, TC08, TC14), or by the wired value abstraction
(TC06, TC18). Their agreement with the source papers is real validation of the encoder
and of `AxiomaticConsistency` — but it says nothing about the fake/real classifier.

**14 examples are dependency-sensitive but not fake-sensitive** — `none ≠ current`, so
stripping dependencies does change the verdict, but every dependency edge they carry is
*genuine*, so the detector and `sdep = dep` agree:

> `weakestmo/{LB+ctrl, LBd, OOTA}`, `pwt/{LBcd, LBd, LBfc}`,
> `jmm/{TC02, TC03, TC04, TC07, TC09, TC10, TC17, TC20}`

These are the real thin-air cases. They validate that WEAKEST forbids OOTA — which is
what both source papers exist to do — and they are the reason the `none` configuration
is not a viable model. But any model that takes `sdep = dep` gets all 14 right too.
They discriminate WEAKEST from a no-dependency model, not the detector from a
treat-everything-as-real model.

**2 examples would discriminate if the front end could see them** — `pwt/LBfc` and
`jmm/TC04` are *fake control* dependencies (both arms of the guard write the same value,
so the store is unconditional and the source papers ALLOW). The C-dialect parser has no
brace/`else` handling: it flattens both-arms-write to the same event structure as the
genuine `LBcd`/`TC03` and records the `read → store` ctrl edge `isSemantic = true`
unconditionally. The detector therefore never gets the chance to call them fake, and
`current` over-approximates to FORBIDDEN — these are exactly 2 of the paper's 3 reported
divergences. Both currently sit in the 14 above. **If the control-dependency front-end
gap were closed, the anchor would be 4/34, not 2/34** — that is the ceiling for this
example set, and it is worth stating because it shows the shortfall is only partly a
front-end limitation.

## What to say in the paper

The `31/34` figure is a correct and useful statement about *checker-vs-literature
agreement*: it validates the encoder, the consistency axioms, and the litmus front end
against three independent published sources. It should stay, framed as that.

It should not be used, explicitly or by placement, as evidence for the §6 fake/real
detector. For that claim the honest number is **2/34**, from a single source, and the
paper should say so plainly. Suggested framing:

> Of the 34 published examples, 2 (`LBfd`, `LBxor`, both from Jeffrey et al.) are
> *discriminating* for the semantic-dependency analysis: their verdict changes if
> `sdep` is replaced by `dep`. 18 are settled independently of any dependency
> information and 14 carry only genuine dependencies, on which our detector and the
> naive `sdep = dep` model agree. A further 2 (`LBfc`, `TC04`) are fake *control*
> dependencies that our front end cannot currently expose to the detector; closing that
> gap would raise the discriminating set to 4. The published literature therefore
> supplies only a small external anchor for the fake-dependency contribution, which is
> why we complement it with the corpus-scale sweep of §6.x and the author-constructed
> LB family.

This is consistent with, and reinforced by, the corpus-scale result already recorded in
`eval/sdep-fake-summary.md`: of 231 corpus tests carrying at least one fake edge, only
2 discriminate (both PPC LB variants). Fake dependencies are abundant in real litmus
corpora; fake dependencies *positioned on a load-buffering cycle*, where the
classification decides the verdict, are rare everywhere we have looked. That rarity is
itself a defensible finding — it just cannot be reported as broad external validation.

## Artifacts

- `eval/anchor-discriminating.csv` — per-test structural + behavioural columns for all 34.
- `src/main/java/wev/smt/ablation/AnchorDiscriminatingRun.java` — the driver.
- Cross-checks (unchanged, consistent): `eval/source-paper-ablation-raw.csv`,
  `eval/jmm-results.csv`, `eval/sdep-fake-summary.md`.
