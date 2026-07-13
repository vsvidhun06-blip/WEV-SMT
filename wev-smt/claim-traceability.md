# Claim traceability matrix — `paper/wev-smt.tex`

Audit date: 2026-07-13. Scope: every claim in the Abstract, Introduction contribution
list, every theorem/proposition statement, every evaluation-conclusion paragraph, and the
Conclusion. Support is traced to the exact theorem, table, experiment, or artifact in the
repository.

> **Overarching status: the paper is a SCAFFOLD.** The file header (lines 6–10) states it
> plainly: *"Locked prose is NOT yet inserted: every locked section appears as a red
> `\TODO` box."* Consequently **most claim text does not physically exist in the document
> yet** — it is described by a `\TODO{...}` placeholder. Where a claim is a placeholder, the
> matrix traces the *intended* claim (inferred from the `\TODO` text and the backing
> artifact) and marks the claim itself as **NOT-YET-WRITTEN**, because a claim that is not
> written cannot be verified as written and must be re-audited at insertion time.

---

## CRITICAL issues (flag first)

| # | Issue | Where | Why critical |
|---|---|---|---|
| **C-1** | **All primary claim prose is a `\TODO` placeholder** — Abstract, Introduction + contributions C1/C2/C3, all five theorem/proposition **statements**, Related Work, Discussion. | lines 60, 69, 204, 213, 224, 229, 234, 470, 486 | The paper's load-bearing claims are literally absent from the document. Nothing in these can be verified as written; the "traceability" below is against *intended* claims only. |
| **C-2** | **Theorem 4.1 (soundness) has no statement and no proof**, yet is framed as a `\begin{theorem}`. The Conclusion (line 513) explicitly disclaims *"no mechanized proof of soundness."* | thm:sound (204); Conclusion (510–513) | A result presented as a **Theorem** whose only backing is an informal, scoped argument is a Theorem-vs-evidence mismatch. As soon as §4.5 states soundness formally it will be **stronger than the evidence**. |
| **C-3** | **Theorem 4.2 (completeness) statement is absent** — only "three qualifications" are hinted. | thm:complete (213) | A completeness theorem with no visible statement or proof is unverifiable and the highest-risk formal claim in the paper. |
| **C-4** | **The central contribution (jfCoherence / semantic-dependency thin-air) is NOT externally cross-validated.** The herd7 cross-check (Tables 4–5) validates **only SC and TSO**. `herd7_baseline.md:132`: *"the WEAKEST no-thin-air contribution … are **not** cross-validated here."* | tab:sc, tab:tso (381–411); Conclusion (507–508) | Any Abstract/Intro claim of *empirical validation of the thin-air model against an external oracle* would be unsupported. The WEAKEST verdicts rest only on internal evidence (ablation + the LB pair + a self-audit) and construction — no external ground truth, no proof. |
| **C-5** | **Table 1 (the "ten dependency patterns") is empty** — all ten rows are the literal string `[locked]`. | tab:patterns (288–303) | The "ten patterns" contribution has **no rendered content** in the paper. Support exists (`sdep_impl_patterns.md`, the detector code) but is not in the document. |
| **C-6** | **Two whole sections have no source to insert.** Related-Work source `section7_drafts_v3.md` is **confirmed missing from disk**; §2 Background prose is *"NOT in chat"* (line 78). | §2 (78), §7 (470–471) | The paper cannot be completed from repo state; Related Work / Background claims (positioning vs Weakestmo, Promising, IMM, RC11…) are entirely un-sourced. |

**Directional over-claim risks** (not missing support, but claim likely stronger than evidence — watch at insertion):

- **Figure 2 asserts "four mutually incomparable weak models"** (fig:lattice caption, 263) but only **2 of the 6** pairwise directions are backed by witnesses (Prop 4.4 TSO⋢PSO, Prop 4.5 RA⋢WEAKEST). Full mutual incomparability (all pairs) is not evidenced.
- **Prop 4.4 (TSO⋢PSO)** holds for the paper's **RC11-flavoured PSO encoding, not canonical SPARC PSO** (`pso-audit.md:14,251`). Unqualified, it over-claims.
- **Table 3 (Batty) shows `current = FORBIDDEN` for *both* the `-double` and `-single` cases**, i.e. the detector **over-forbids** the redundant-control `-double` that Batty's argument says should be ALLOWED. If §6.3 prose frames this as the detector "handling" control examples rather than as a *known limitation*, it is false. (Correct framing = limitation; see `docs/litmus-parser-coverage.md`.)

---

## Traceability table

Support types: **DP** = direct proof · **BC** = by construction · **EMP** = empirical · **INF** = informal argument · **—** = none.

| Claim | Location | Support (artifact) | Support type | Sufficient? | Issue |
|---|---|---|---|---|---|
| **Abstract** (181-word) — entire abstract | `\TODO` line 60 | none (text absent) | — | **NO** | **C-1.** Claim text not written; unverifiable. Re-audit at insertion. |
| **Title claim**: thin-air distinguishable "on a single execution" via semantic-vs-syntactic deps | 49–50 | `AxiomaticConsistency.jfCoherence` (`:217`); Fig 1; `eval/examples/paper` solver runs | BC + EMP (internal) | Partial | Demonstrated on the LB pair + corpus, **not proven**; hedged in Conclusion ¶2. Acceptable as a scoped design claim only. |
| **Introduction** — all prose | `\TODO` line 69 | none (text absent) | — | **NO** | **C-1.** |
| **Conceptual contribution**: the semantic/syntactic dependency distinction is the mechanism that separates admitted from forbidden thin-air | intended (69–73); realized only in Conclusion 496–503 & Fig 1 | Fig 1 (fig:lbpair); `eval/examples/paper/LB-real*.litmus`, `LB-fake*.litmus`; `docs/pass-3-plan.md §2` | BC + EMP (internal) | Yes (for the claim as a *distinction*) | Statement not in Intro yet (C-1). Backing is solid for the matched pair. |
| **C1** (inferred: the WEAKEST model + `jfCoherence` single-execution no-thin-air axiom) | `\TODO` 69 | `AxiomaticConsistency.consistencyWEAKEST/jfCoherence` (`:148,:217`); §3.4 (TODO 104) | BC | Partial | **C-1** (text absent) + **C-4** (not externally validated). Axiom exists and runs; its *correctness* is unproven. |
| **C2** (inferred: SMT encoding, decidable per-execution) | `\TODO` 69; §3.5 (TODO 108) | `EventStructureEncoder`; integer-layer encoding; Z3 solves in `eval/ablation-raw.csv` | BC + EMP | Yes | **C-1** (text absent). Encoding demonstrably runs and terminates. |
| **C3** (inferred: implementation + evaluation — 10 patterns, 2 998 tests, ablation) | `\TODO` 69; §5–6 | `sdep_impl_patterns.md`; `eval/corpus-summary.md`; `ablation_results.md`; `eval/detector-quality-report.csv` | EMP | Yes | **C-1** (text absent); Table 1 empty (**C-5**). |
| **Fig 1**: LB-real → data dep semantic → `jfCoherence` closes cycle → **forbidden** | fig:lbpair caption 175–184 | `AxiomaticConsistency.jfCoherence` polarity trace (`:210–213`); `eval/examples/paper/LB-real.litmus` run (FORBIDDEN) | BC + EMP | Yes | Sound. |
| **Fig 1**: LB-fake `r⊕r⊕1` constant → dep syntactic only → excluded from sdep → cycle **allowed** | fig:lbpair caption 175–184 | `docs/pass-3-plan.md:166`; `eval/examples/paper/LB-fake-xor-cycle.litmus` run (ALLOWED) | BC + EMP | Yes | Minor: relies on `r⊕r⊕1` wiring value 1 (the plain `r⊕r` variant does *not* — a known trap, correctly avoided here). |
| **§3.4 jfCoherence** definition/axiom | `\TODO` line 104 | `AxiomaticConsistency.jfCoherence` (`:217`) | BC | **NO (as written)** | **C-1.** Definition exists in code but not in the paper. |
| **Theorem 4.1 (soundness, supported-syntax fragment)** | thm:sound, 202–205 | informal argument only; **no** mechanized proof (Conclusion 513) | INF | **NO** | **C-2 (CRITICAL).** "Theorem" framing but statement absent and no proof; Conclusion disclaims soundness. |
| **Theorem 4.2 (completeness, "three qualifications")** | thm:complete, 211–214 | none visible | — | **NO** | **C-3 (CRITICAL).** Statement absent; no proof; unverifiable. |
| **Theorem 4.3 (SC dominates every weak model, by construction)** | thm:scdom, 222–226 | `theorem_4_3_attempt.md` (§ "SC ⊒ PSO/WEAKEST by construction", lines 103, 210) | BC | Partial | Statement absent (**C-1**); backing doc is titled *"attempt"*. Argument is plausible/by-construction but not yet a locked proof. |
| **Proposition 4.4 (TSO ⋢ PSO; incomparable; witness `WR-relacq-cex`)** | prop:tsopso, 227–231 | `pso-audit.md` (witness verified, §4.3) | BC + EMP (witness) | Partial | Statement absent (**C-1**). Holds only for **RC11-flavoured PSO**, not canonical SPARC PSO (`pso-audit.md:14,251`) — over-claims if stated unqualified. |
| **Proposition 4.5 (RA ⋢ WEAKEST; witness `LBdep-real`)** | prop:rawk, 232–236 | `theorem_4_3_attempt.md:185` (`LBdep-real ∈ consistent_RA ∖ consistent_WEAKEST`) | BC + EMP (witness) | Yes | Statement absent (**C-1**); witness verified. |
| **Fig 2 lattice**: SC dominates (solid, by construction) | fig:lattice caption 261–266 | `theorem_4_3_attempt.md` (SC-rooted edges) | BC | Yes | — |
| **Fig 2 lattice**: "four **mutually incomparable** weak models" | fig:lattice caption 263 | only Prop 4.4 + Prop 4.5 (2 of 6 pairwise directions) | BC + EMP (partial) | **Overclaim** | Only 2 non-containments have witnesses; "mutually incomparable" (all pairs) is not evidenced. |
| **§6.1 Setup** prose | `\TODO` line 325 | `eval/corpus-summary.md`; `docs/corpus-validation-findings.md` | EMP | NO (as written) | **C-1.** |
| **§6.2 Hierarchy preservation**: "0 violations across 2 998 fully-validated files" | `\TODO` 330–331 | `docs/corpus-validation-findings.md`; `consistency-validation.csv`; `eval/corpus-summary.md` | EMP | Yes | Statement in TODO (**C-1**) but the number is fully backed. |
| **§6.3 Ablation** conclusion (Table 2 caption): *"current is the only config that allows every fake-dep LB and forbids every real-dep LB"* | tab:ablation caption 340–344; `\TODO` 336 | `ablation_results.md`; `eval/ablation-raw.csv`; generality via `eval/detector-quality-report.csv` (0 misclass.), `eval/sdep-fake-summary.md` (2/231 discriminate) | EMP | Yes (scoped) | "every … LB" is proven on the curated 4+4; the corpus-wide 0-misclassification audit supports the generalization. Prose is TODO (**C-1**). |
| **Table 2 (ablation numbers)** | tab:ablation 346–355 | `eval/ablation-raw.csv` | EMP | Yes | Real data; footnote explains the 358 ms warm-up. |
| **§6.3 Batty control examples** (Table 3) | tab:batty 359–372 | `eval/examples/paper/LB+ctrldata+ctrl-{double,single}.litmus` runs | EMP | Yes (data) | Table honestly shows `current`=FORBIDDEN for **both** → detector **over-forbids** the redundant-control `-double`. Sufficient *only if* §6.3 prose frames it as a **limitation** (see over-claim risk box). |
| **§6.4 herd7 cross-check** (Tables 4–5): SC 86.2% non-ARM, TSO 74% X86 | tab:sc, tab:tso 381–411; `\TODO` 377 | `eval/herd7-results.csv`; `herd7_baseline.md`; `co4_co10_diagnosis.md` | EMP | Yes for SC/TSO | **C-4.** Validates SC/TSO **only** — *not* the WEAKEST contribution (`herd7_baseline.md:132`). Prose must not present this as validation of the thin-air model. |
| **§6.4** ARM 38% agreement is a loader/feature-test artifact, not unsoundness | tab:sc ARM row 393; `\TODO` 377 | `herd7_baseline.md:70–77` (ARM excluded; VMSA/MTE feature tests); `co4_co10_diagnosis.md` (co-wiring limitation) | EMP + INF | Sufficient (with caveat) | The diagnosis rigorously covers `co4`/`co10` and the X86/PPC permissive-side pattern; the sweeping "every ARM disagreement is benign" should stay scoped to the documented buckets. |
| **§6.5 Parser coverage**: "24.3% coverage (2 998 of 12 324)"; skip/PE buckets | tab:skips, tab:perrors 419–461; `\TODO` 416 | `eval/skip-reasons.tsv`, `eval/pe-reasons.tsv`, `eval/corpus-summary.md` | EMP | Yes | Descriptive/honest. Prose is TODO (**C-1**). |
| **Table 1 (ten dependency patterns)** | tab:patterns 288–303 | `sdep_impl_patterns.md`; detector code | EMP | **NO** | **C-5 (CRITICAL).** All ten rows are literal `[locked]` — no content rendered. |
| **§7 Related Work** (11 paragraphs) | `\TODO` 470–479 | source `section7_drafts_v3.md` **missing** | — | **NO** | **C-6 (CRITICAL).** No source on disk; positioning claims un-sourced. |
| **§8 Discussion** prose | `\TODO` 486 | — | — | NO (as written) | **C-1.** Includes a self-noted softening ("three mechanisms agree" → literature-based) not yet applied/verifiable. |
| **Conclusion CC-1**: OOTA "can be ruled out on a single candidate execution, without an event-structure construction" | 496–503 | `jfCoherence` (`:217`); Fig 1; ablation | BC + EMP (internal) | Partial | "ruled out" is strong; no proof it excludes *all* OOTA. Rescued by the ¶2 disclaimer (CC-5). Keep the hedge adjacent. |
| **Conclusion CC-2**: LB-real/LB-fake share identical `po∪rf` + observed values, differ only in dependency information | 499–503 | Fig 1; `eval/examples/paper` (both files, identical wiring) | BC + EMP | Yes | Sound. |
| **Conclusion CC-3**: `jfCoherence` yields a no-thin-air condition that is "SMT-encodable and decidable on a per-execution basis" | 503–507 | `AxiomaticConsistency.jfCoherence` (finite QF integer-layer formula, solved by Z3) | BC | Yes | "Decidable" justified: finite quantifier-free encoding. |
| **Conclusion CC-4**: "implemented … and exercised it on 2 998 supported-syntax litmus tests" | 507–508 | `eval/corpus-weakest-manifest.csv`; `eval/corpus-summary.md`; `docs/corpus-validation-findings.md` | EMP | Yes | Number is backed. |
| **Conclusion CC-5** (disclaimer): "no formal correspondence with Weakestmo/Promising … no mechanized proof of soundness" | 510–513 | n/a (limitation) | INF | Yes (honest) | This disclaimer **contradicts framing Theorem 4.1 as a soundness theorem** — reconcile (see C-2). |
| **Conclusion CC-6** (limitation): results hold for a "word-sized, supported-syntax fragment"; classifier is "a finite pattern set rather than a complete analysis" | 513–516 | §6.5 tables; `sdep_impl_patterns.md` (10 patterns) | EMP | Yes | Honest scoping; matches evidence. |

---

## Summary

- **Claims with NO support (CRITICAL):** the *statements* of Theorem 4.1 (C-2) and Theorem 4.2 (C-3); Table 1's ten-pattern content (C-5); Related Work (C-6). Plus the meta-issue that all primary prose is un-inserted (C-1).
- **Central-contribution support is internal only (C-4):** the thin-air/`jfCoherence` claim is supported by construction + the LB matched pair + the internal ablation and a self-audit, but has **no external validation and no mechanized proof**. The herd7 tables validate SC/TSO, a *different* part of the system.
- **Well-supported claims:** the ablation (Table 2), hierarchy preservation (0/2 998), parser-coverage tables, the RA⋢WEAKEST witness (Prop 4.5), and the Conclusion's empirical statements — all trace to real artifacts and are appropriately scoped.
- **Over-claim risks to fix at insertion:** "four mutually incomparable models" (2/6 backed), Prop 4.4's PSO flavour, and the Batty `-double` framing (a limitation, not a success).
- **The single most important structural risk:** any soundness/validation language in the (unwritten) Abstract/Intro must be reconciled with the Conclusion's honest disclaimers and with C-4 — the model is *demonstrated*, not *proven* or *externally validated*.
