# Marko review package — contents

Package for Marko's 30-minute review of the WEV-SMT POPL submission
*Distinguishing Semantic from Syntactic Dependencies for Thin-Air on a
Single Execution*. Read `marko_cover_letter.md` first; it names the four
questions to answer. Assembled 2026-06-02.

> **Note on the `.tex`:** `wev-smt.tex` is the assembled scaffold — locked
> prose is still `\TODO`-marked and will be merged in before sending. The
> figures, tables, bibliography, and cross-references are final. Tooling to
> compile is Overleaf (acmart is built in).

> **Missing:** `section7_drafts_v3.md` (the §7 related-work paragraphs) and
> `REVIEWER_ATTACKS.md` are referenced by the cover letter but are not on
> disk; they are **not** included in this archive.

## Artifact reports (evidence behind §4 and §6)

| File | What it is | Produced | Role in the paper |
|------|-----------|----------|-------------------|
| `ablation_results.md` | sdep-classifier ablation: WEAKEST run under `none` / `all-deps-semantic` / `current` over the LB family + 200-file corpus slice | 2026-05-30 | Backs §6.3 — shows the shipped detector is the only config that allows every fake-dep LB and forbids every real-dep LB. Source of Table 2 (3×4) and Table 3 (Batty control). |
| `parser_failures.md` | Categorization of the 9,326 non-validating corpus files (7,461 skips + 1,865 parse-errors) into feature buckets | 2026-05-30 | Backs §6.5 — defuses the 24.3%-coverage criticism (risk #4). Source of Tables 6 and 7. |
| `herd7_baseline.md` | Verdict-level agreement of WEV-SMT vs herd7 7.58 on the SC and x86-TSO strong fragment | 2026-05-31 | Backs §6.4 — external sanity check (risk #6). Source of Tables 4 (SC) and 5 (TSO). |
| `theorem_4_3_attempt.md` | By-construction analysis of the strength lattice: SC-rooted edges proved, TSO⊒PSO and RA⊒WEAKEST refuted by verified counterexamples | 2026-05-31 | Backs §4.7 (Option C) — Theorem 4.3 + Propositions 4.4/4.5 and Figure 2. |
| `co4_co10_diagnosis.md` | Triage of the same-location coherence disagreements (co4/co10) with herd7 | 2026-05-31 | Supports §6.4 — shows the disagreement is a loader coherence-order *wiring* limitation, not an unsoundness of the coherence axiom. |
| `wev-smt-pass-status.md` | Project status / dev-log (from auto-memory): Pass 3 stages, daily commit milestones through Day 15 | 2026-06-01 | Context only — chronology and provenance of the artifacts above; not cited in the paper. |

## Paper sources

| File | What it is | Produced | Role |
|------|-----------|----------|------|
| `wev-smt.tex` | Assembled acmart scaffold (`acmsmall,review,anonymous`); locked prose `\TODO`-marked, figures/tables/refs final | 2026-06-02 | The paper. Compile on Overleaf. |
| `refs.bib` | Bibliography, 14 entries, `ACM-Reference-Format`. Two entries flagged `% VERIFY` (jeffrey2016thinair, gavrilenko2019bmc) | 2026-06-02 | Bibliography for `wev-smt.tex`. |
| `marko_cover_letter.md` | The cover letter — four questions, POPL deadline (2026-07-09 AoE), package list | 2026-06-02 | **Read first.** Frames the review. |
