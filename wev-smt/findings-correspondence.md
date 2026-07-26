# Findings — mechanising the PwT correspondence

Session goal: build the layer that `findings-pwt.md` found missing (the Isabelle
development treated the constancy predicate as an uninterpreted
`event ⇒ event ⇒ bool`, so the per-site PwT correspondence was pen-and-paper
only) and mechanise as much of the correspondence as closes cleanly.

**All four steps landed. `isabelle build -d . WEV_SMT WEV_SMT_PwT` is
`BUILD_EXIT=0` from a clean heap, with no `sorry` and no new `axiomatization`.**

**Headline result, and it is not the one the task anticipated: the program-level
correspondence is an INCLUSION, and the equality is false.** WEAKEST's composed
site-wise dependency over-approximates PwT's program-level semantic dependency —
that is proved unconditionally, and it is the direction Theorem 1 consumes. The
converse is *refuted*, with two mechanised witnesses. So the paper's headline
honesty caveat changes character rather than disappearing: it is no longer "the
program-level correspondence is unproved", it is "the program-level
correspondence is a proved inclusion, the equality is proved false, and the
divergence class is characterised". Details in §4 and §6.

---

## 1. What is now mechanised

New session `WEV_SMT_PwT` (declared in `isabelle/ROOT`, sources in
`isabelle/pwt/`), extending the existing `WEV_SMT` session. The existing
`WEV_SMT_Defs.thy` / `Detector.thy` / `Soundness.thy` are **untouched** — the new
work sits on top of them and imports them.

| File | Contents | Lemmas |
|---|---|---|
| `isabelle/pwt/PwT_Expr.thy` | BV32 syntax + semantics | 18 |
| `isabelle/pwt/PwT_Constancy.thy` | the constancy predicate, hybrid argument | 16 |
| `isabelle/pwt/PwT_Model.thy` | locale + the intended model | 22 |
| `isabelle/pwt/PwT_R4b.thy` | per-site correspondence, exact R4b | 24 |
| `isabelle/pwt/PwT_Program.thy` | program-level correspondence | 26 |
| | **total** | **106** |

Build: `isabelle build -d . WEV_SMT WEV_SMT_PwT` (the artifact's documented
command gains the second session name; `-a` also works).

### STEP 1 — BV32 syntax and semantics (`PwT_Expr.thy`)

`datatype expr = ENum word32 | EReg reg | EUn unop expr | EBin binop expr expr |
ERel relop expr expr` over `word32 = 32 word` from `HOL-Library.Word`, with
`eval :: expr ⇒ env ⇒ word32`.

The operator set was read off `src/main/java/wev/smt/QfbvConstancyOracle.java`,
not assumed:

* AST nodes `Num / Var / Un / Bin / Rel` (lines 147–153) → the five constructors.
* `evalTerm` (lines 173–213) → unary `~` and `-`; binary `+ - * ^ & |`; relational
  `== != < <= > >=`.
* **The relational operators are SIGNED.** `bv.lessThan(l, r, true)` passes
  `signed = true` (lines 191–194), so `eval_rel` uses `word_sless` / `word_sle`,
  not the unsigned word order. This is a fact about the implementation, not a
  modelling choice, and it is easy to get wrong.
* A comparison yields C's `int` 0/1 widened back to 32 bits (lines 197–199), so
  `eval` of an `ERel` is an if-then-else into `{0, 1}`.
* `W = 32` (line 51), so `word32` is monomorphic rather than `'a word`.

Also here: `regs` with `finite_regs`, the coincidence lemma `eval_coincidence`,
the syntactic branch-free (relop-free) fragment `branch_free_expr`, and `subst`
with `eval_subst`. Sanity checks pin the fake patterns (`r^r`, `r-r`, `r&0`),
their real counterparts, `(r&1)&2 = 0`, and `eval (r^r^1) s = 1`.

### STEP 2 — connecting `eval` to the constancy predicate

**`PwT_Constancy.thy`** defines

```isabelle
non_constant e r ⟷ (∃s v1 v2. eval e (s(r := v1)) ≠ eval e (s(r := v2)))
```

exactly as the task specified, plus the pointwise dual `indep`, with
`indep_iff_constant : indep e r ⟷ ¬ non_constant e r`.

`qfbv_query_eq_non_constant` proves that the oracle's *documented* query — the
substitution form `e[reg := v1] ≠ e[reg := v2]` from the class Javadoc (line 32)
— is the same statement as the *implemented* one, which varies an environment.
That is a small lemma but exactly the kind of place where an artifact claim
silently stops matching its code.

**`PwT_Model.thy`** — a necessary correction to the task's framing, stated up
front in the theory header. The task asks to "PROVE `non_constant` equals
`expression_constant`'s negation". **That is not a theorem and cannot be one.**
`expression_constant` is introduced by `axiomatization` in `WEV_SMT_Defs.thy`; it
is an uninterpreted constant with no definition to unfold, and adding the
equation as a further axiom would *enlarge* the trust base, which is the opposite
of the point. What discharges the abstraction is the standard model-theoretic
move, and that is what was done:

1. Re-present the axiomatization as a **locale** `detector_locale` with the same
   signature and the same two assumptions, and re-prove Lemma 2
   (`detector_soundness_l`), detector exactness (`detector_exactness_l`) and
   Theorem 1 (`soundness_l`) inside it.
2. Show the global axiomatization is an instance
   (`interpretation weakest: detector_locale dep expression_constant oracle_unsat
   branch_free`), with bridge lemmas `weakest_sdep_true`, `weakest_sdep_impl`,
   `weakest_branch_free_pairs`, `weakest_thin_air_cycle`, `weakest_jfcoherence`
   identifying the locale's relations with the global ones — so the locale's
   theorems *are* the existing ones, recovered as
   `detector_soundness_via_locale`, `detector_exactness_via_locale`,
   `soundness_via_locale`.
3. Exhibit the **intended model**: `bv_econst` / `bv_ounsat` / `bv_bfree` over
   BV32 non-constancy, with the oracle's conservative handling of
   out-of-grammar sites modelled explicitly (`site c p = None` ⇒ not UNSAT,
   mirroring `isConstant` returning `null` and the `undecidedReasons` ledger at
   lines 63–67). Both engineering assumptions become **theorems**:
   `bv_oracle_soundness`, `bv_oracle_completeness_branch_free`.

The strength of (3) is worth being precise about: **`bv_detector` has no locale
assumptions at all.** The two engineering assumptions hold in the BV model for
*every* dependency relation, *every* site labelling, and *every* choice of ground
truth at out-of-grammar sites (that last is an unconstrained parameter `outside`,
deliberately — those expressions are by definition not elements of `expr`, so
pinning them would make the interpretation true by fiat exactly where it is
weakest).

### STEP 3 — per-site correspondence, **option (a)** (`PwT_R4b.thy`)

Option (a) was delivered in full: exact observed-value R4b is formalised and its
divergence from `non_constant` is *characterised*, not merely illustrated.

Three criteria, related exactly:

| Isabelle | Meaning | Source |
|---|---|---|
| `pwt_two_point e r` | `∃s v. eval e (s(r:=v)) ≠ eval e (s(r:=0))` | what `PwtEquivalenceSweep.nonConstantIn` (lines 145–172) computes |
| `non_constant e r` | WEAKEST's criterion | decided exactly by `QfbvConstancyOracle` |
| `exact_r4b e r s` | `eval e s ≠ eval e (s(r:=0))` | PwT's R4b as published, anchored at the observed execution |

* **`pwt_two_point_eq_non_constant`** — the paper's per-site lemma, mechanised.
  Anchoring the reference point at 0 rather than searching all pairs loses
  nothing (the formal content of `findings-pwt.md` §3).
* **`exact_r4b_sound`** — exact R4b never reports a dependency that is not there.
* **`non_constant_iff_some_observation : non_constant e r ⟷ (∃s. exact_r4b e r s)`**
  — the STEP 3 result. WEAKEST's criterion is precisely the *existential closure
  of PwT's over observations*. The two criteria do not differ in what they can
  detect at all; they differ only in how much of the observation is held fixed.
* **`exact_r4b_blind_at_zero`** — the sharpest form of the gap, and it costs two
  lines: at any observation where the register already holds 0, the observed and
  reference points coincide, so exact R4b reports nothing however strongly the
  expression depends on the register. Hence
  `exact_r4b_never_pointwise_complete`: **exact R4b cannot be pointwise
  equivalent to non-constancy for *any* non-constant expression.** Masking is
  generic, not a corner case.
* **`divergence_class`** — the divergent observations are exactly
  `{s. eval e s = eval e (s(r := 0))}`.
* **`masking_witness`** — the `findings-pwt.md` §4 witness (`r & 2` observed at
  `r = 1`) mechanised.

Two further results the paper needs:

* **`every_ctx_strictly_stronger`** — the oracle shares the other registers
  between the two copies and leaves them *free*, so its criterion is "there
  **exists** a context in which the expression moves", not "in every context".
  The readings are genuinely different (`r AND q` separates them) and the
  implemented one is the weaker, dependency-admitting one. `findings-pwt.md` §2
  flagged this as needing explicit statement; it is now a proof.
* **The injective fragment.** `inj_in_exact_r4b` : if `e` is injective in `r`,
  exact R4b and non-constancy agree at every observation with `s r ≠ 0`, and
  `exact_r4b_blind_at_zero` says that exception is unavoidable. `+ - ^ ~` and
  unary minus are shown injective (`inj_in_add_const`, `inj_in_sub_const`,
  `inj_in_xor_const`, `inj_in_xor_reg`, `inj_in_add_reg`, `inj_in_sub_reg`,
  `inj_in_uminus`, `inj_in_bnot`); **both** AND forms are shown *not*
  (`not_inj_in_and_const`, `not_inj_in_and_reg`).

### STEP 4 — program-level correspondence (`PwT_Program.thy`)

A branch-free program is `prog = (reg × expr) list` with a fold semantics `run`.
WEAKEST's composed relation is `sites P` (one edge per assignment, from each
register the assigned expression is genuinely non-constant in, to the assigned
register). PwT's program-level criterion is
`prog_dep P x y ⟷ (∃s v1 v2. run P (s(x:=v1)) y ≠ run P (s(x:=v2)) y)`.

**Proved, unconditionally — this is the real target and it closed:**

```isabelle
theorem prog_dep_sound: "prog_dep P x y ⟹ (x, y) ∈ rtrancl (sites P)"
```

WEAKEST's site-wise dependency, composed across the program, over-approximates
PwT's program-level semantic dependency. This is exactly the direction Theorem 1
consumes: acyclicity of what WEAKEST computes implies acyclicity of what PwT's
semantics induces, so no program-level thin-air dependency hides in the gap.

The proof runs through a generalised `flow` lemma stated over difference *sets*
of registers. The generalisation is forced, not stylistic: executing one
instruction turns a difference at `S` into a difference at `S ∪ {z}`, so an
induction hypothesis about single-register differences does not apply to the
tail. `flow` bottoms out in `diff_witness` (PwT_Constancy), which itself needs
the finite hybrid argument `eval_eq_of_indep_on` — the difference set has to be
trimmed to `S ∩ regs e` via `eval_coincidence` before the induction can start,
because `env` is a total function on an infinite identifier type.

Reflexive-transitive, not transitive, closure is what holds: a register the
program never touches flows to itself along the empty chain.

**Proved FALSE — the equality the task asked for does not hold:**

```isabelle
theorem sitewise_not_complete:
  "∃P x y. (x, y) ∈ trancl (sites P) ∧ ¬ prog_dep P x y"
```

Witness `cancelP`: `t := x + 1; y := t - x`. Both steps are genuinely
value-carrying, so the site-wise relation reports a path `x → t → y`, yet the
composite computes the constant `1`. **Non-constancy is not closed under
composition.** Note the witness is already in SSA form — `x`, `t`, `y` are
distinct and never reassigned — so this is *not* an artefact of `sites` ranging
over register names rather than versions.

```isabelle
theorem sitewise_not_complete_single_read:
  "∃P x y. (∀(z,e) ∈ set P. ∃r. regs e = {r})
           ∧ (x, y) ∈ trancl (sites P) ∧ ¬ prog_dep P x y"
```

Witness `maskP`: `t := x & 1; y := t & 2`, which computes `0`. Every instruction
reads exactly one register, so the failure is not caused by re-reading a value
either. **This is the same masking class that separates exact R4b from
non-constancy per-site (§ STEP 3).** One phenomenon, two consequences — which is
the cleanest thing to say about it in the paper.

**Proved on a fragment:** `pipeline_exact`. On an injective pipeline (a program
that repeatedly rewrites one register, every step a bijection in it) the
over-approximation is tight — both criteria fire. `xor_pipeline_exact` is the
worked instance for the corpus's canonical real pattern.

Four **positive controls** (`copy_prog_dep`, `copy_sites`, `const_no_prog_dep`,
`untouched_prog_dep`) pin down `run` / `prog_dep` / `sites` against programs
whose answer is obvious, so the two negative theorems cannot hold vacuously
through a mis-stated definition.

---

## 2. Exactly which existing lemmas this strengthens

| Existing result | Status before | Status now |
|---|---|---|
| `Detector.detector_soundness` (Lemma 2) | true *relative to* an uninterpreted `expression_constant` and an axiom about it | recovered from `detector_locale` as `detector_soundness_via_locale`; the locale is satisfied by the concrete BV semantics (`bv_model_is_a_detector`), so the lemma is no longer relative to an uninterpreted predicate. `bv_detector.bv_detector_soundness` is the concrete form. |
| `Detector.detector_exactness` | exactness on `branch_free_pairs`, with branch-freedom itself uninterpreted | recovered as `detector_exactness_via_locale`, **and strictly strengthened** by `bv_detector.sdep_agree_in_grammar`: the detector is exact on *every in-grammar site*, not only relop-free ones. `bv_detector.sdep_differ_only_outside_grammar` bounds the whole discrepancy by `{(p,c). site c p = None}`. See the caveat in §3. |
| `Soundness.soundness` (Theorem 1) | proved from Lemma 2 over the abstract relations | recovered as `soundness_via_locale`, and transported to the concrete model as `bv_detector.bv_no_thin_air`. Theorem 1 now holds of a detector whose notion of "constant" is BV32 non-constancy. |
| `WEV_SMT_Defs` consistency argument (prose: "interpreting both predicates as universally true satisfies each of them") | a degenerate model — shows only that the axioms are not contradictory | superseded by the *intended* model. `bv_detector` has **no** locale assumptions, so the axioms hold for every dep relation, site labelling and out-of-grammar ground truth. |
| Paper Prop. 5 (`prop:pwt-equiv`, `paper/wev-smt.tex:605`) | pen-and-paper | mechanised as `pwt_two_point_eq_non_constant`. **Must be kept explicitly per-site** — see §6. |
| Paper §7 open problem (a), "formal PwT equivalence" (`paper/wev-smt.tex:1057`) | open | per-site: closed. Program-level: closed as an inclusion; the equality is *refuted*. |

**New result with no predecessor:** `optimistic_oracle_unsound`. Had
`QfbvConstancyOracle` reported UNSAT rather than `null` on expressions its parser
cannot read, the soundness axiom would fail outright, and with it Lemma 2 and
Theorem 1. The conservative `null` handling is a correctness requirement, not a
defensive habit — and that is now a refutation rather than a remark.

---

## 3. Remaining assumptions, named

Nothing below is hidden inside a proof; each is either an explicit locale
parameter or a stated boundary.

**A1 — Z3 decides the QF_BV constancy query correctly.** Unchanged as a trust
boundary, but **narrowed**: it is now *only* about the solver. Previously
`oracle_soundness` bundled together "Z3 is correct" and "the intended semantics
of `expression_constant` is BV constancy"; the second half is now discharged by
the model. `oracle_completeness_branch_free` is likewise no longer an assumption
about Z3 terminating — in the model it is a theorem, because QF_BV is decidable.

**A2 — parser and site-labelling fidelity.** That the Java `BvParser` /
`evalTerm` pair maps a litmus-test value expression to the same element of
`expr` that the paper's grammar assigns it, and that `site c p` reports the
register the producer actually writes. Not mechanisable without modelling the
parser. This is the largest remaining assumption and it is the natural target for
a follow-up (a verified or differentially-tested parser).

**A3 — the event-graph bridge (NOT an assumption discharged here).**
`PwT_Program` reasons about `sites P` for a straight-line program `P`; the
existing theories reason about `dep` over abstract events. That `sites` of a
thread's program coincides with `dep` restricted to that thread is **not
proved** — it is part of A2. See §4.

**A4 — out-of-grammar ground truth is *not* assumed.** The `outside` parameter of
`bv_detector` is unconstrained and every result holds for all instantiations of
it. Recording this as a non-assumption is the point of leaving it a parameter.

**A5 — unchanged from the existing development.** `dep_ctrl` /
`control_conservative` (untouched, still unused by any proof);
`coherence_per_location` and `rmw_atomicity` still stubbed as `True`, with
`soundness_from_jfcoherence_only` still recording that Theorem 1 does not depend
on them.

---

## 4. The precise statement of what remains unproved

Not "future work". Three specific things, in decreasing order of how much they
would be worth.

**(i) The event-graph bridge.** `sites P` (register-level, one straight-line
program) has not been connected to `dep` (event-level, whole litmus test). The
missing statement is:

> For a litmus test `T` with thread `i` compiled to the branch-free program
> `P_i`, and for events `p`, `c` of thread `i` with `c` reading the register `p`
> writes: `(p, c) ∈ dep` iff `(reg_of p, reg_of c) ∈ sites P_i`.

The obstruction is not mathematical, it is that `LitmusParser`'s extraction of
`dep` from AArch64/PPC/x86 assembly is a substantial piece of untrusted string
handling (`splitOperands`, the `[Xn,Wm,SXTW]` and `#`-immediate cases already
recorded in memory as historic bug sites). Mechanising it means modelling the
instruction encodings, which is a project in itself. Until then, the register-level
and event-level developments meet only through A2.

**(ii) A characterisation of the exact fragment.** Program-level exactness is
false in general (`sitewise_not_complete`) and true on injective pipelines
(`pipeline_exact`). What is open is the boundary:

> Characterise `{(P, x, y). prog_dep P x y ⟷ (x, y) ∈ trancl (sites P)}`.

Known points: injective pipelines are inside; the cancellation program
(`t := x+1; y := t-x`) and the masking program (`t := x&1; y := t&2`) are
outside. The two obstructions are distinct and both are elementary — additive
cancellation and bit masking — which suggests the boundary is not a natural
syntactic class, and that the honest thing for the paper to claim is the
inclusion plus the two refutations, not a conjectured equality.

**(iii) Branches, loops, memory.** `run` is deterministic straight-line over
registers only. The relational operators are in the *expression* grammar (so `(r
!= 0)` as a value is covered), but control flow is not, and loads/stores are
abstracted into the event-level `jf` relation rather than modelled. The
branch-free restriction is therefore real and unremoved at the program level,
even though `bv_detector.sdep_agree_in_grammar` removes it *per-site*.

---

## 5. Where the model is deliberately weaker than the implementation

Recorded so a reviewer finds it here first.

* `run` is single-threaded. Multi-threaded composition happens at the event level
  via `jf`, which Theorem 1 already handles abstractly, but the two levels are
  joined only by A2/A3.
* `sites` ranges over register *names*, not SSA versions, so it conflates
  re-assignments. This makes `prog_dep_sound` a stronger statement (the
  over-approximation survives the conflation) but means the relation is coarser
  than an SSA analysis would produce. Neither counterexample relies on it.
* `word32` is monomorphic. The oracle fixes `W = 32`; 64-bit registers in the
  AArch64/PPC corpus are handled by the Java side before the oracle sees them,
  and that truncation is not modelled — it falls under A2.

---

## 6. What to change in the paper

1. **Prop. 5 (`paper/wev-smt.tex:605`) must stay explicitly per-site.** Its
   current wording — "On branch-free arithmetic, free-valuation `sdep` and PwT's
   predicate-transformer criterion agree" — reads as a program-level claim, and
   the program-level claim is now **proved false**
   (`sitewise_not_complete`). The per-site reading ("both reduce to non-constancy
   in the source register") is correct and is now mechanised
   (`pwt_two_point_eq_non_constant`); the sentence should be tightened so the
   program-level reading is not available, and the inclusion
   (`prog_dep_sound`) stated separately as the program-level result.

2. **The mechanisation-status sentence.** Lemma 2 and Theorem 1 were already
   machine-checked; what is new is that they are no longer relative to an
   uninterpreted constancy predicate, and that the per-site PwT lemma and the
   program-level inclusion are machine-checked too. Suggested wording:

   > All results are machine-checked in Isabelle2024 (`isabelle/`,
   > `BUILD_EXIT=0`, no `sorry`). The constancy predicate is not axiomatised: it
   > is defined over a 32-bit expression language matching the QF_BV oracle's
   > grammar, and the detector's two engineering assumptions are derived rather
   > than assumed. The per-site correspondence with PwT's R4b is proved, as is
   > the program-level inclusion `prog_dep ⊆ sites*`; the program-level
   > *equality* is proved false, with the divergence class characterised.

3. **Drop any inference from the 79/79 sweep to exact-R4b agreement.** This
   confirms `findings-pwt.md` §4 from the other direction: `not_inj_in_and_reg`
   shows the corpus's `&`/`|` register-on-register forms are outside the
   injective fragment, so they *could* host a divergence — the sweep simply
   observes them at values that do not expose it. The per-site lemma is doing
   all the work; the number is corpus evidence, not equivalence evidence.

4. **§7 open problem (a) can be retired and replaced** by (ii) and (i) of §4
   above, which are sharper and are the honest residue.

5. **Artifact build command** gains a session:
   `isabelle build -d . WEV_SMT WEV_SMT_PwT`.
