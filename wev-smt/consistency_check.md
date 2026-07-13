# Cross-artifact consistency check

**Date:** 2026-06-02
**Scope:** Internal-consistency cross-check of empirical numbers across four artifact
files and the §-draft prose they embed.

> **⚠️ Caveat on the "locked prose".** The task specified cross-checking against
> externally-pasted locked §6.1–§6.5 prose. **No external prose was supplied in the
> request.** This report therefore checks each number against the **in-file §-draft
> paragraphs** that the artifacts already contain, which are the only § text available:
>
> | Task's § | Available text used | Location |
> |---|---|---|
> | §6.2 | "Coverage of the validation corpus" draft | `parser_failures.md` lines 95–112 |
> | §6.3 | "Ablating the dependency classifier" draft (labeled §6.x) | `ablation_results.md` lines 136–162 |
> | §6.4 | "Cross-checking against herd7" draft (labeled §6.x) | `herd7_baseline.md` lines 118–133 |
> | §4.7 | "Hierarchy preservation" draft | `theorem_4_3_attempt.md` lines 235–257 |
> | §6.1, §6.5 | **NOT AVAILABLE** — no artifact file or embedded draft | — |
>
> Checks against §6.1 and §6.5 are reported as **N/A (cannot verify — no source text)**.
> If the external locked prose differs from these embedded drafts, re-run this check
> against it.

---

## Summary

**All verifiable cross-references PASS (24/24).** No numeric conflicts found between any
two artifacts or between an artifact and its embedded §-draft. Two checks (§6.1, §6.5)
are non-verifiable because no source text was provided.

---

## 1. Total corpus file count — target 12,324

| Reference | Value | Status |
|---|---|---|
| `parser_failures.md:1` "the 12,324 corpus files" | 12,324 | PASS |
| `parser_failures.md:5` `2998/12324 = 24.3%` | 12,324 | PASS |
| `parser_failures.md:97` (§6.2 draft) "Of 12,324 litmus files" | 12,324 | PASS |
| Arithmetic: 12,324 − 2,998 = 9,326 (stated complement) | 9,326 | PASS |
| Arithmetic: 7,461 skips + 1,865 PE = 9,326 | 9,326 | PASS |
| Other three files reference 12,324? | not referenced | N/A (no conflict) |

**Verdict: PASS.** 12,324 is internally consistent everywhere it appears; only
`parser_failures.md` cites the total, and its decomposition (2,998 + 7,461 + 1,865 =
12,324) is arithmetically exact. No competing total exists in the other files to conflict.

---

## 2. Validated count — target 2,998

| Reference | Value | Status |
|---|---|---|
| `parser_failures.md:1` "2,998 validated" | 2,998 | PASS |
| `parser_failures.md:14` "OK 697+2301=2,998" | 2,998 | PASS |
| `parser_failures.md:97,109` (§6.2 draft) "2,998 … 0/2,998" | 2,998 | PASS |
| `ablation_results.md:35,46,173` "2,998-file … §6.2 set/manifest" | 2,998 | PASS |
| `ablation_results.md:35` "697 herd7 + 2301 Dat3M" | 2,998 | PASS |
| `theorem_4_3_attempt.md:6,155,198` "2,998 corpus files" | 2,998 | PASS |
| `theorem_4_3_attempt.md:255` (§4.7 draft) "2,998 fully-validated files" | 2,998 | PASS |
| Arithmetic: 697 + 2,301 = 2,998 | 2,998 | PASS |

**Verdict: PASS.** All four files agree on 2,998; the herd7/Dat3M split (697 + 2,301)
reconciles exactly. (`herd7_baseline.md` states 697 rather than 2,998, consistent — it
only covers the herd7 partition.)

---

## 3. herd7 subset count — target 697

| Reference | Value | Status |
|---|---|---|
| `herd7_baseline.md:34` "the 697 files that WEV-SMT fully validates" | 697 | PASS |
| `herd7_baseline.md:120` (§6.4 draft) "697-file herd7 subset" | 697 | PASS |
| `herd7_baseline.md:154,165,167` (artifacts/risk note) "697 rows / 697" | 697 | PASS |
| `parser_failures.md:14` "OK 697+2301" | 697 | PASS |
| `ablation_results.md:35` "697 herd7" | 697 | PASS |
| Arch mix `herd7_baseline.md:35`: ARM 570 + PPC 60 + X86 51 + C 11 + RISCV 5 | 697 | PASS |

**Verdict: PASS.** 697 is consistent across all three files that cite it, and the per-arch
breakdown sums to 697.

---

## 4. Agreement numbers — targets 86%, 74%, 123, 46

| Reference | Value | Status |
|---|---|---|
| `herd7_baseline.md:62` SC non-ARM total: comparable **123**, agree **106**, **86.2%** | 123 / 86% | PASS |
| `herd7_baseline.md:68` TSO X86: comparable **46**, agree **34**, **74%** | 46 / 74% | PASS |
| `herd7_baseline.md:122` (§6.4 draft) "123 non-ARM tests … 106 (86%)" | 123 / 86% | PASS |
| `herd7_baseline.md:123` (§6.4 draft) "34 of 46 X86 tests (74%)" | 46 / 74% | PASS |
| §6.4 per-arch "X86 42/50, PPC 51/59, C 9/9, RISCV 4/5" vs table rows | match | PASS |
| `herd7_baseline.md:153` risk note "denominators are 123 and 46" | 123 / 46 | PASS |

**Cross-checked arithmetic (all PASS):**
- non-ARM files 51+60+11+5 = **127**; errors 1+1+2+0 = **4**; comparable 50+59+9+5 = **123** ✓
- non-ARM agree 42+51+9+4 = **106**; 106 / 123 = **86.2%** ✓ (rounds to 86%)
- TSO X86 comparable 51−5 = **46**; agree 34, disagree 12, 34+12 = 46 ✓; 34/46 = 73.9% ≈ **74%** ✓
- §6.4 fractions: X86 42/50 = 42 agree of 50 comparable ✓; PPC 51/59 ✓; C 9/9 ✓; RISCV 4/5 ✓
- risk note "no verdict for 214 of the 697 under SC" = ARM 210 + non-ARM 4 = **214** ✓

**Verdict: PASS.** Every agreement figure in the §6.4 draft (123, 106, 86%, 46, 34, 74%,
and all per-arch fractions) is reproduced exactly by the `herd7_baseline.md` tables, and
all column sums are internally consistent.

---

## 5. Ablation cell verdicts — LB-fake, LB-real, 3-thread, corpus-slice

Table source: `ablation_results.md:58–62`. Prose source: §6.3 draft `ablation_results.md:136–162`.

| Cell | Table value | §6.3 draft claim | Status |
|---|---|---|---|
| LB-real / `none` | (4, 0, 0, 0) — all ALLOWED | "sdep = ∅ → all four real-dependency LB tests become consistent" | PASS |
| LB-real / `current` | (0, 4, 0, 0) — all FORBIDDEN | "shipped detector … forbids every real-dependency LB" | PASS |
| LB-fake / `all-deps-semantic` | (1, 3, 0, 0) — 3 FORBIDDEN | "sdep = dep → three of the four fake-dependency tests become inconsistent" | PASS |
| LB-fake / `current` | (4, 0, 0, 0) — all ALLOWED | "shipped detector … allows every fake-dependency LB" | PASS |
| 3-thread / all configs | (3, 0, 0, 0) identical | "all three configurations agree" (no-op); Surprises §:122 "ISA2/WRC/IRIW all ALLOWED" | PASS |
| corpus-slice / all configs | (194, 6, 0, 0) identical | "reproduces the unmodified checker on all 200 sampled corpus files (200/200)" | PASS |

**Cross-checked arithmetic (all PASS):**
- corpus-slice cell 194 + 6 = **200** files ✓ (matches "200/200", seed-42 slice of 2,998)
- LB-fake group size 4, LB-real group size 4, 3-thread group size 3 ✓ (match table headers)
- §6.3 "the two endpoints fail in opposite, predictable directions" — none over-allows
  LB-real (4 allowed), all-deps-semantic over-forbids LB-fake (3 forbidden) ✓
- Batty control table (`:77–81`) FORBIDDEN under `current` matches §6.3 "forbids both …
  under the shipped detector, whereas none admits them" ✓

**Verdict: PASS.** All four ablation cells cited in the §6.3 prose match the 3×4 results
table exactly, in both direction and magnitude.

---

## 6. Hierarchy counterexample names — WR-relacq-cex, LBdep-real

Source table: `theorem_4_3_attempt.md:14–19` and combined summary `:205–212`.
Prose source: §4.7 draft `theorem_4_3_attempt.md:235–257`.

| Reference | Counterexample (edge) | Status |
|---|---|---|
| `theorem_4_3_attempt.md:17` table — TSO ⊒ PSO | **WR-relacq-cex** | PASS |
| `theorem_4_3_attempt.md:121` witness build | **WR-relacq-cex** | PASS |
| `theorem_4_3_attempt.md:211` summary — TSO ⊒ PSO | **WR-relacq-cex** | PASS |
| `theorem_4_3_attempt.md:247` (§4.7 draft) | **WR-relacq-cex** | PASS |
| `theorem_4_3_attempt.md:19` table — RA ⊒ WEAKEST | **LBdep-real** | PASS |
| `theorem_4_3_attempt.md:167` witness | **LBdep-real** | PASS |
| `theorem_4_3_attempt.md:212` summary — RA ⊒ WEAKEST | **LBdep-real** | PASS |
| `theorem_4_3_attempt.md:250` (§4.7 draft) | **LBdep-real** | PASS |

**Supporting consistency (all PASS):**
- Reverse-direction witnesses also consistent table↔§4.7: `2+2W` (PSO-allow/TSO-forbid,
  `:144` & `:248`) and `CO-MP` (RA-forbid/WEAKEST-allow, `:188` & `:251`) ✓
- Edge-violation count: §4 "3 such witnesses in classics", reproduce note `:272`
  "RA⇒WEAKEST = 3, all others 0" ✓ — internally consistent
- `LBdep-real` name matches the ablation/herd7 usage (`ablation_results.md:44` LB-real
  group lists `LBdep-real`) ✓

**Verdict: PASS.** Both counterexample names (`WR-relacq-cex`, `LBdep-real`) are identical
between the theorem tables and the §4.7 draft, and the associated edges, directions, and
reverse witnesses all agree.

---

## Non-verifiable items (no source text supplied)

| Item | Reason |
|---|---|
| §6.1 prose consistency | No §6.1 text was pasted and no artifact embeds a §6.1 draft. **Cannot verify.** |
| §6.5 prose consistency | No §6.5 text was pasted and no artifact embeds a §6.5 draft. **Cannot verify.** |
| External "locked" §6.2/§6.3/§6.4/§4.7 vs in-file drafts | Checked against the **embedded** drafts only. If the externally-locked prose differs from these drafts, those numbers must be re-checked against it. |

---

## Final tally

| # | Cross-reference | Verdict |
|---|---|---|
| 1 | Total corpus = 12,324 (all references) | **PASS** |
| 2 | Validated = 2,998 (all relevant sections) | **PASS** |
| 3 | herd7 subset = 697 (`herd7_baseline.md` + §6.4) | **PASS** |
| 4 | Agreement 86% / 74% / 123 / 46 (`herd7_baseline.md` ↔ §6.4) | **PASS** |
| 5 | Ablation cells LB-fake / LB-real / 3-thread / corpus-slice (`ablation_results.md` ↔ §6.3) | **PASS** |
| 6 | Counterexamples WR-relacq-cex / LBdep-real (`theorem_4_3_attempt.md` ↔ §4.7) | **PASS** |
| — | §6.1, §6.5 external prose | **N/A — no source text** |

**No FAILs. No conflicting numbers identified.** The only gap is the absent external
§6.1/§6.5 locked prose, which could not be checked because it was not provided.
