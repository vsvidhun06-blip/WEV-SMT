# JMM causality suite — provenance verification (C2-3)

Verifies the provenance and verdict labelling of `eval/examples/jmm/TC01…TC20.litmus`
against the two reference points available **in this repository**: the WEV checker
(`ParseDump`, wired mode) and the Dat3M/LKMM cross-run (`eval/dat3m-comparison.csv`).

## Scope and honest limitation

The tests are the **Java Memory Model causality test cases (Pugh 2004)**, per
`eval/test-provenance.md` (source 3 of the 34 published examples). **Pugh's authoritative
program/decision document is not present in this repository.** Provenance is therefore
verified along the three axes that *are* objectively checkable here:

1. **Structural** — each file parses as a faithful C-dialect causality shape (LB / MP /
   chain) with the documented dependency structure;
2. **Value-reachability** — whether each `exists` is satisfiable under *real* value
   propagation (model-independent);
3. **Cross-source consistency** — the JMM verdict label vs. the Dat3M/LKMM reference tool.

Where these three agree, provenance is treated as confirmed. Where they disagree, the case
is flagged and classified. This is **not** a claim of having re-derived each test from
Pugh's original text.

## Cross-source verdict table

`JMM` = label in `jmm-results.csv`; `WEV` = wired WEAKEST verdict; `Dat3M` = Dat3M under
`linux-kernel.cat`.

| TC | JMM | WEV(wired) | Dat3M/LKMM | status |
|----|-----|-----|-----|--------|
| TC01 | ALLOWED | ALLOWED | ALLOWED | ✅ consistent |
| TC02 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC03 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC04 | ALLOWED | FORBIDDEN | FORBIDDEN | ⚠ model diff (redundant ctrl dep) |
| TC05 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC06 | FORBIDDEN | ALLOWED | FORBIDDEN | ⚠ WEV value-abstraction |
| TC07 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC08 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC09 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC10 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC11 | ALLOWED | ALLOWED | ALLOWED | ✅ |
| TC12 | ALLOWED | ALLOWED | ALLOWED | ✅ |
| TC13 | FORBIDDEN | FORBIDDEN | ALLOWED | ⚠ WEV fixed-co (needs co-enum) |
| TC14 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC15 | ALLOWED | ALLOWED | ALLOWED | ✅ |
| TC16 | ALLOWED | ALLOWED | FORBIDDEN | ⚠ model diff (LKMM stricter thin-air) |
| TC17 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |
| TC18 | **FORBIDDEN** (corrected) | ALLOWED | FORBIDDEN | ⚠ **mislabel fixed** — see below |
| TC19 | ALLOWED | ALLOWED | ALLOWED | ✅ |
| TC20 | FORBIDDEN | FORBIDDEN | FORBIDDEN | ✅ |

16 of 20 are fully consistent across all three sources. The four exceptions:

### TC04 — genuine JMM-vs-LKMM model difference (label correct)
Both arms of P0's guard write `x=1`, so the control dependency is *redundant*: JMM allows
(a compiler may drop the branch), LKMM forbids (syntactic ctrl dep). JMM=ALLOWED is
correct; the divergence is the documented control-dependency limitation (Batty et al.
Thm 2). **No change.**

### TC13 — WEV fixed-co limitation (label correct)
`exists (0:r0=2 /\ 0:r1=1)` requires coherence order `y=2 →co y=1`, which is legal; WEV
pins a single `co` and picks the inconsistent one, so WEV=FORBIDDEN while Dat3M (co
existential) =ALLOWED. JMM=FORBIDDEN is the coherence decision. Fixing WEV needs **co
enumeration** (`CoEnumeration`), not an rf/label change. **No change.**

### TC16 — genuine JMM-vs-LKMM model difference (label correct)
P2 writes `x=2` **unconditionally**, so a fully value-consistent witness exists
(x=2→y=2→z=2→x=2, every read observing a genuinely-produced value). JMM=ALLOWED is correct;
LKMM forbids the thin-air chain syntactically. Value-reachability **confirms** ALLOWED.
**No change.** (Contrast TC18 below — the distinguishing case.)

### TC18 — mislabel corrected (this workstream)
`exists (0:r0=1 /\ 1:r0=1 /\ 2:r0=1)` is **value-unreachable**: `0:r0=1` forces P0's
`y=r0+1` to `2`, so `y ∈ {0,2}`; but `1:r0=1` needs P1 to read `y=1`, which **no write
produces**. The `exists` is therefore unsatisfiable under real value propagation, so the
correct verdict is **FORBIDDEN under every sound model** (Dat3M/LKMM agrees). The prior
`ALLOWED` label was WEV's *wired value-abstraction* artifact (`evalConst` models `y=r0+1`
as the constant `1`, fabricating a spurious `y=1` write) — the same limitation class as
TC06, not the JMM decision. This matches `rf-enumeration-analysis.md` §4 independently.

- **Corrected:** `TC18.litmus` header verdict and the `jmm-results.csv` row → FORBIDDEN,
  now a `value-abstraction` divergence (WEV wired still ALLOWED).
- **Program preserved verbatim.** Pugh's authoritative TC18 program is not in the repo, so
  no structural re-encoding was fabricated. If the intent was a genuinely-ALLOWED test
  (unconditional producer of the read value, TC16-style), that requires the Pugh original
  to encode faithfully — recorded as an open item.

## Effect on the JMM agreement number

| | wired JMM agreement | members that diverge |
|---|---|---|
| Before (as labelled) | 18/20 | TC04, TC06 |
| **After correction** | **17/20** | TC04, TC06, **TC18** |

The prior 18/20 counted TC18 as a genuine agreement; it was an encoding artifact
(`rf-enumeration-analysis.md` §8). The honest wired figure is **17/20**. Under the C1
symbolic-rf value-propagation encoding (designed, not yet built), TC06 and TC18 both flip
to FORBIDDEN and agree, projecting **19/20** — but that number must come from an actual
run, not this document.

## Open items
- [ ] Obtain Pugh's authoritative TC18 (and re-verify all 20 program texts) to decide
      whether TC18 needs a structural re-encode vs. only the verdict correction applied here.
- [ ] TC13 requires co-enumeration to reconcile with Dat3M (separate axis).

_No `.tex` files modified; nothing committed._
