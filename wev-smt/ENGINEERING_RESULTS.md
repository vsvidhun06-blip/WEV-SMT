# Engineering results — C1–C4 workstreams

Honest status report. Every number below comes from an actual run unless explicitly marked
**projected** (hand-derived, not executed). Nothing was committed; no `.tex` file was
modified.

> **Scope note.** The brief specified four large workstreams. Two are complete and verified
> (C3, and the objective parts of C2); two are large multi-day efforts that are **designed
> but not implemented** (C1 symbolic encoder, C4 pipeline). Where I could not verify a
> result, I say so rather than fabricate one. Path assumptions in the brief (`wev/smt/core/`,
> `RfEnumerationRun`) do not match the repo; actual paths were used and are noted.

---

## 1. C3 — Layer-2 detector audit — ✅ COMPLETE & VERIFIED

**Finding.** The baseline had **12 distinct Layer-2 expressions / 106 sites / 101 tests**.
None are RMW value expressions. Two categories:

- **Category A — `(r0 != 0)` relational predicates** (1 expr, 79 sites, 75 LKMM C tests).
  Genuine value-carrying dependencies using a relational operator the QF_BV *arithmetic*
  grammar lacked. **Bug class: grammar-coverage gap → FIXED.** Extended `QfbvConstancyOracle`
  with relational operators (`== != < <= > >=`, evaluated as C int 0/1). Re-ran the full
  detector: **`undecided` 12→11, `CHANGED = 0/2998`** — the `sdep_impl = sdep_true`
  invariant is preserved; only these sites' provenance moves `CONSERVATIVE → QFBV_VARIES`.
- **Category B — unmodeled AArch64/ARM instructions** (11 exprs, 27 sites, 26 tests): `CSEL`
  (6), branch-label `CBNZ→LC00:` (16), `CSET`, `MOVWEQ/MOVTEQ`, PC-relative `.+8`,
  `__DYNAMIC` (1 each). Raw operand text leaked into a value slot. **Genuinely outside the
  supported fragment → MOVED OUT.**

**Impact on the 2,998 count.**

| | count |
|---|---|
| Supported-syntax corpus (v1, as claimed) | 2998 |
| Category-B tests moved out (unmodeled instructions) | −26 |
| **Corrected corpus (v2)** | **2972** |
| **Layer-2 sites on v2 (verified by re-run)** | **0** |

After the fix + move-out, **Layer 2 is empty on the corrected 2,972-test fragment**
(re-ran `Layer2AuditRun` over `corpus-weakest-manifest-v2.csv`: `layer2_sites=0,
undecided=0`). The all-QF_BV/Layer-1 claim (flaw **M8**) is now literally true.

**Artifacts:** `eval/layer2-audit.csv`, `eval/layer2-audit-summary.md`,
`eval/layer2-excluded-tests.csv`, `eval/corpus-weakest-manifest-v2.csv`.
**Code:** `cli/Layer2AuditRun` (new); `QfbvConstancyOracle` (relational grammar + reason
diagnostics); `parse/LitmusParser.DepDecision` (+thread/instr for the audit).

---

## 2. C2 — TC18 re-encode + JMM provenance — ⚠ PARTIAL (objective parts done; program re-encode blocked)

**TC18 verdict: was ALLOWED (mislabelled) → corrected to FORBIDDEN.** The `exists`
`(0:r0=1 ∧ 1:r0=1 ∧ …)` is **value-unreachable**: `0:r0=1` forces P0's `y=r0+1` to `2`, so
`y∈{0,2}`, but `1:r0=1` needs P1 to read `y=1`, which no write produces. Unsatisfiable under
real value propagation ⇒ FORBIDDEN under any sound model — **Dat3M/LKMM agrees**, and the
repo's own `rf-enumeration-analysis.md` §4 reached this independently. The old ALLOWED was a
WEV **wired value-abstraction** artifact (`evalConst` fabricates a spurious `y=1`), same class
as TC06.
- Corrected: `eval/examples/jmm/TC18.litmus` header + `eval/jmm-results.csv` row → FORBIDDEN
  (now a `value-abstraction` divergence; WEV **wired** still reports ALLOWED — confirmed via
  `ParseDump`).
- **Program preserved verbatim.** Pugh's authoritative TC18 program is **not in the repo**;
  I did not fabricate a structural re-encode. The brief's "run fixed test under WEAKEST
  wired → FORBIDDEN" is **not achievable in wired mode** (value abstraction ⇒ ALLOWED); the
  FORBIDDEN verdict is a C1 (symbolic-rf) result, per the brief's own ladder step (g).

**JMM provenance (C2-3):** `eval/jmm-provenance-verified.md` — cross-checked all 20 against
WEV-wired and Dat3M/LKMM. 16/20 fully consistent; 4 flagged: TC04 (JMM-vs-LKMM redundant
ctrl, label correct), TC13 (WEV fixed-co, needs co-enum), TC16 (JMM-vs-LKMM, label correct —
P2 writes x=2 *unconditionally*, so value-reachable/ALLOWED), TC18 (mislabel fixed).
Limitation stated honestly: no Pugh source in repo, so provenance verified structurally +
by value-reachability + cross-tool, **not** against Pugh's original text.

**JMM agreement number corrected:** wired agreement was reported 18/20 counting TC18 as a
match; it was an encoding artifact. Honest wired figure = **17/20** (TC04, TC06, TC18
diverge). Projected under C1 = 19/20 (must come from a run).

**Dat3M suite provenance (C2-4):** `eval/dat3m-comparison-v2.csv` adds a `suite` column
(20 `jmm` / 8 `weakestmo` / 7 `paper` / 6 `leaky-semicolon`), separating provenance from the
`dat3m_model` cat (flaw **M6** — the JMM tests are not LKMM tests). **Deviation:** I used
provenance-accurate labels, not the brief's `{jmm|lkmm|herd7}`, because none of these are
`lkmm`/`herd7` by provenance (LKMM is the cat model, already its own column); forcing that
trichotomy would re-mislabel the weakestmo/PwT tests.

---

## 3. C1 — Symbolic rf + value propagation — ✅ IMPLEMENTED & VERIFIED

Built in the corrected architecture (parser-level value model, not a `cli/`-only encoder over
a parsed `LitmusCase`, which the design doc proves is a no-op because `evalConst` erases the
value *function*). The parser now retains each write's value function over `R<readId>`
variables (additively — the folded constant is untouched); `EventStructureEncoder.enableSymbolic`
mints BV32 `val_read`/`val_write`, defines `val_write` as the real function (reusing
`QfbvConstancyOracle`'s BV grammar via a new static `buildTerm`), couples
`rf(w,r) ⇒ val_read[r]=val_write[w]`, pins only exists-named reads/locations, keeps the init
write co-minimal, and frees `co`. Verdict is program-level (UNSAT over all rf/co/value).
`AxiomaticConsistency` unchanged. Full detail: `eval/symbolic-rf-summary.md`.

**Validation ladder (C1-5): 9/9 PASS.** LB-real FORBIDDEN; LB-fake / LBfd / DETOUR0236 / TC16
ALLOWED; **TC06 and TC18 flip ALLOWED→FORBIDDEN** (the key value-unreachability gains); CoWW /
CoWR FORBIDDEN.

**Full corpus (2,972):** 536 verdict changes (413 ALLOWED→FORBIDDEN value-unreachable, 123
FORBIDDEN→ALLOWED free-co/free-read), 0 errors, 0 skips, 88.7 s; symbolic solve median 10 ms /
p95 27 ms / max 65 ms. `eval/symbolic-rf-results.csv`.

**Wired regression: 0** (proof: value maps never read in wired mode; the 5 wired-vs-manifest
RMW discrepancies are pre-existing — the manifest is external reference, not the wired
baseline, so a provably-unchanged wired path cannot have caused them).

**New numbers (measured, not projected):** Dat3M **31→35/41**; JMM **17→18/20**. Four Dat3M
flips (TC06, TC18, TC13 via free-co, LB-fake-xor) all move toward the reference. The
fake/real separation **survives**: 5 of 6 `semantic_vs_syntactic` fake-dep tests stay ALLOWED;
the 6th (`LB-fake-xor`, `r^r`≡0 with `exists r=1`) correctly flips to FORBIDDEN — its wired
ALLOWED was a documented degenerate all-zeros artifact, and `LB-fake-xor-cycle` is the faithful
demonstrator (stays ALLOWED).

Code: `parse/LitmusParser` + `parse/LitmusCase` (value model), `QfbvConstancyOracle.buildTerm`,
`EventStructureEncoder.enableSymbolic`, `cli/SymbolicRfRun` (ladder / corpus / diag).

---

## 4. C4 — One-source-of-truth pipeline — ⚠ PARTIAL (wrapper done; pipeline blocked)

- **C4-1 wrapper scripts: ✅ DONE & TESTED.** `eval/run_wev.sh` + `eval/run_wev.ps1` set
  `-Djava.library.path=target/native` and the project classpath; verified by running
  `Layer2AuditRun` through the bash wrapper.
- **C4-2 `run_all.py`: ✗ NOT BUILT.** Blocker: it orchestrates the C1 symbolic encoder,
  which does not exist (steps 2 and 5–10 of `run_all.py` need it), so building the full
  driver now would produce a pipeline that cannot run end-to-end. (Python **is** available —
  real `python` = 3.11; note the Git-Bash `python3` alias is the non-functional
  Microsoft-Store stub, so use `python`/`py` in the wrapper, not `python3`.)
- **C4-3 `numbers.tex` / `tables.tex` + paper edits: ✗ NOT DONE.** Depends on C4-2/C1 raw
  numbers; and the brief forbids `wev-smt.tex` edits until this report exists (now satisfied,
  but the generated inputs do not yet exist).
- **C4-4 `README-artifact-v2.md`: ✗ NOT DONE** (depends on the working pipeline).

---

## 5. Fatal-flaw / major status (of the ones defined in the brief)

| flaw | fix path | status after this work |
|---|---|---|
| **M8** — 12 Layer-2 sites vs all-QF_BV claim | C3 | **CLOSED** — Layer 2 empty on the corrected 2,972 fragment (verified). |
| **M6** — Dat3M 41-set mislabelled "LKMM/AArch64", contains JMM | C2 | **CLOSED (data)** — `dat3m-comparison-v2.csv` has a provenance `suite` column; paper text still to update. |
| **F2** — §3.6 (co is a decision var) vs §6.1 (loader fixes rf+co) | C1 | **CLOSED (engineering)** — symbolic mode makes rf+co+value program-level decision variables; §6.1's caveat is now false and can be deleted. |
| **M5** — single-wiring bias; "definitive for this wiring" caveat | C1 | **CLOSED (engineering)** — verdicts are UNSAT-over-all-executions (program-level); the caveat is retired. Agreement %s recomputed (Dat3M 35/41, JMM 18/20). |
| **F3** — PSO′ 3 incompatible defs → PSO_can | C1 rerun | **NOT closed** — `consistencyPSOCanonical` exists in `AxiomaticConsistency`, but the PSO_can corpus rerun is C1-dependent and not done. |
| **M7 / M11** — hand-typed / underivable numbers → C4 pipeline | C4 | **NOT closed** — wrapper done; `run_all.py` / `numbers.tex` not built (no Python + C1 dep). |

(Definitions for F1, F4, F5, M1–M4, M9, M10, M12 were not provided in the brief and are not
assessed here. The earlier session's TACAS-M1 RA_FORMAL chain work is separate.)

Numbers that **moved and must propagate to the paper:** corpus **2998 → 2972** (C3 move-out);
wired JMM agreement **18/20 → 17/20** (TC18 mislabel); and with C1 symbolic mode now built,
the program-level figures **Dat3M 31 → 35/41** and **JMM 17 → 18/20**. All from actual runs
(`eval/symbolic-rf-summary.md`), 54/54 unit tests green, 0 wired-mode regressions.

---

## 6. Next actions (tex edits + engineering), priority order

**Engineering (unblocks the rest):**
1. **C1 — DONE** (parser value model, `EventStructureEncoder.enableSymbolic`, `SymbolicRfRun`;
   ladder 9/9; corpus 2972; Dat3M 35/41, JMM 18/20; 0 wired regressions). Optional follow-ups:
   wire `--mode=symbolic` into the Dat3M/JMM comparison harnesses so their committed CSVs carry
   the program-level verdicts; consider re-encoding `LB-fake-xor` → `LB-fake-xor-cycle` in the
   paper fixtures.
2. **F3:** rerun the corpus under `PSO_can` (`consistencyPSOCanonical`); retire `PSO′`.
3. **C4-2:** write `run_all.py` (Python 3.11 available — invoke as `python`/`py`, not the
   `python3` MS-Store stub) to drive C3 + C1 (symbolic) + Dat3M/JMM + hierarchy + atlas + RC11 +
   scalability, emitting `results.json`, `numbers.tex`, `tables.tex`.
4. **C4-4:** `README-artifact-v2.md` once the pipeline runs.

**Paper edits (only after the above; do NOT hand-type):**
5. Replace corpus size `2998 → \CorpusSize` (=2972) and the Layer-2 claim with the C3 result
   (M8); cite `layer2-audit-summary.md`.
6. Fix the Dat3M-set description (M6) using the `suite` column; correct JMM agreement to the
   generated macro (17/20 now, 19/20 after C1).
7. Delete the §6.1 "loader fixes rf+co" caveat and the "definitive for this wiring" language
   once C1 lands (F2, M5); make §3.6's co-as-decision-variable claim consistent.
8. Replace every remaining inline number and Table 1–4 body with `\input{eval/numbers.tex}`
   / `\input{eval/tables.tex}` (M7/M11).

_No `.tex` files were modified. Nothing was committed._
