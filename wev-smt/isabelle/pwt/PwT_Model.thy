(*  Title:      PwT_Model.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 2, part 2: discharging the abstraction that WEV_SMT_Defs makes.

    WHAT "DISCHARGE" CAN AND CANNOT MEAN HERE.

    WEV_SMT_Defs introduces expression_constant by axiomatization, i.e. as an
    uninterpreted constant of type event => event => bool constrained only by
    two axioms. No equation of the form

        expression_constant c p = (\<not> non_constant e r)

    is provable about it: an uninterpreted constant has no definition to unfold,
    and adding such an equation as a further axiom would strengthen, not shrink,
    the trust base. Proving it is not merely hard; it is not a theorem.

    What CAN be done, and is done below, is the standard model-theoretic
    discharge:

      (1) Re-present the axiomatization as a locale (detector_locale) with the
          same signature and the same two assumptions, and re-prove Lemma 2,
          detector exactness and Theorem 1 inside it. Nothing is lost: the
          global axiomatization is shown to be an instance of the locale, and
          the locale's theorems specialise back to the ones in Detector.thy and
          Soundness.thy.

      (2) Exhibit the INTENDED model: interpret the locale with
          expression_constant instantiated to the negation of the concrete BV32
          non_constant predicate of PwT_Constancy, and the oracle instantiated
          to the query QfbvConstancyOracle actually poses (including its
          conservative treatment of expressions outside the modelled grammar).
          Both axioms then become THEOREMS.

    The strength of (2) is worth being precise about. bv_detector below has NO
    assumptions. So the two engineering assumptions of WEV_SMT_Defs hold in the
    BV model for every choice of dependency relation, of site labelling, and of
    the ground truth at sites outside the modelled grammar. Compare the prose
    consistency argument in WEV_SMT_Defs, which offers only the degenerate model
    "interpret both predicates as universally true". That model shows the axioms
    are not contradictory; this one shows they are satisfied by the semantics
    the implementation is actually computing.

    WHAT REMAINS ASSUMED after this theory (see findings-correspondence.md):
      A1  Z3 decides the QF_BV query correctly.
      A2  The Java BvParser/evalTerm pair maps a litmus-test value expression to
          the same element of the expr datatype that the paper's grammar assigns
          it, and site c p reports the register the producer writes.
    Neither is mechanisable without modelling the solver and the parser; both
    are named rather than hidden.
*)

theory PwT_Model
  imports PwT_Constancy WEV_SMT.Soundness
begin

section \<open>The detector axiomatization, as a locale\<close>

text \<open>
  Same signature and same two assumptions as the axiomatization in
  WEV_SMT_Defs, but polymorphic in the event type and, crucially, interpretable.
\<close>

locale detector_locale =
  fixes dep_r  :: "('e \<times> 'e) set"
    and econst :: "'e \<Rightarrow> 'e \<Rightarrow> bool"
    and ounsat :: "'e \<Rightarrow> 'e \<Rightarrow> bool"
    and bfree  :: "'e \<Rightarrow> 'e \<Rightarrow> bool"
  assumes ax_soundness: "ounsat c p \<Longrightarrow> econst c p"
      and ax_completeness_bf: "bfree c p \<Longrightarrow> econst c p \<Longrightarrow> ounsat c p"
begin

definition sdep_true_l :: "('e \<times> 'e) set" where
  "sdep_true_l = {(p, c). (p, c) \<in> dep_r \<and> \<not> econst c p}"

definition sdep_impl_l :: "('e \<times> 'e) set" where
  "sdep_impl_l = {(p, c). (p, c) \<in> dep_r \<and> \<not> ounsat c p}"

definition branch_free_pairs_l :: "('e \<times> 'e) set" where
  "branch_free_pairs_l = {(p, c). bfree c p}"

lemma dep_containment_l: "sdep_impl_l \<subseteq> dep_r \<and> sdep_true_l \<subseteq> dep_r"
  unfolding sdep_impl_l_def sdep_true_l_def by blast

theorem detector_soundness_l: "sdep_true_l \<subseteq> sdep_impl_l"
  unfolding sdep_true_l_def sdep_impl_l_def using ax_soundness by auto

theorem detector_exactness_l:
  "sdep_impl_l \<inter> branch_free_pairs_l = sdep_true_l \<inter> branch_free_pairs_l"
  unfolding sdep_impl_l_def sdep_true_l_def branch_free_pairs_l_def
  using ax_soundness ax_completeness_bf by auto

definition thin_air_cycle_l :: "('e \<times> 'e) set \<Rightarrow> bool" where
  "thin_air_cycle_l jf \<longleftrightarrow> (\<exists>x. (x, x) \<in> trancl (sdep_true_l \<union> jf))"

definition jfcoherence_consistent_l :: "('e \<times> 'e) set \<Rightarrow> bool" where
  "jfcoherence_consistent_l jf \<longleftrightarrow> acyclic (sdep_impl_l \<union> jf)"

theorem soundness_l:
  assumes jfc: "jfcoherence_consistent_l jf"
  shows "\<not> thin_air_cycle_l jf"
proof
  assume "thin_air_cycle_l jf"
  then obtain x where cyc: "(x, x) \<in> trancl (sdep_true_l \<union> jf)"
    by (auto simp: thin_air_cycle_l_def)
  have sub: "sdep_true_l \<union> jf \<subseteq> sdep_impl_l \<union> jf"
    by (rule Un_mono[OF detector_soundness_l subset_refl])
  have "(x, x) \<in> trancl (sdep_impl_l \<union> jf)"
    by (rule trancl_mono[OF cyc sub])
  moreover from jfc have "(x, x) \<notin> trancl (sdep_impl_l \<union> jf)"
    by (simp add: jfcoherence_consistent_l_def acyclic_def)
  ultimately show False by simp
qed

end

section \<open>The existing axiomatization is an instance of the locale\<close>

text \<open>
  Nothing is lost by moving to the locale: the global constants of WEV_SMT_Defs
  satisfy it, and the locale's relations coincide with the global ones. The
  bridge lemmas below are what make the locale's theorems the same theorems as
  Detector.detector_soundness, Detector.detector_exactness and
  Soundness.soundness rather than new ones that merely look similar.
\<close>

interpretation weakest:
  detector_locale dep expression_constant oracle_unsat branch_free
  by unfold_locales (auto intro: oracle_soundness oracle_completeness_branch_free)

lemma weakest_sdep_true: "weakest.sdep_true_l = sdep_true"
  by (simp add: weakest.sdep_true_l_def sdep_true_def)

lemma weakest_sdep_impl: "weakest.sdep_impl_l = sdep_impl"
  by (simp add: weakest.sdep_impl_l_def sdep_impl_def)

lemma weakest_branch_free_pairs: "weakest.branch_free_pairs_l = branch_free_pairs"
  by (simp add: weakest.branch_free_pairs_l_def branch_free_pairs_def)

lemma weakest_thin_air_cycle: "weakest.thin_air_cycle_l jf = thin_air_cycle jf"
  by (simp add: weakest.thin_air_cycle_l_def thin_air_cycle_def weakest_sdep_true)

lemma weakest_jfcoherence: "weakest.jfcoherence_consistent_l jf = jfcoherence_consistent jf"
  by (simp add: weakest.jfcoherence_consistent_l_def jfcoherence_consistent_def
                weakest_sdep_impl)

text \<open>The three headline results of the existing development, recovered from the locale.\<close>

corollary detector_soundness_via_locale: "sdep_true \<subseteq> sdep_impl"
  using weakest.detector_soundness_l by (simp add: weakest_sdep_true weakest_sdep_impl)

corollary detector_exactness_via_locale:
  "sdep_impl \<inter> branch_free_pairs = sdep_true \<inter> branch_free_pairs"
  using weakest.detector_exactness_l
  by (simp add: weakest_sdep_true weakest_sdep_impl weakest_branch_free_pairs)

corollary soundness_via_locale:
  "jfcoherence_consistent jf \<Longrightarrow> \<not> thin_air_cycle jf"
  using weakest.soundness_l
  by (simp add: weakest_thin_air_cycle weakest_jfcoherence)

section \<open>The intended model: BV32 constancy\<close>

text \<open>
  A dependency site is a pair (consumer c, producer p). The model equips each
  site with

    site c p = Some (e, r)   the consumer's value expression e, and the register
                             r that the producer writes, when the expression
                             lies inside the modelled BV grammar;
    site c p = None          when it does not -- QfbvConstancyOracle.isConstant
                             returns null for these (line 43-44 and the
                             undecidedReasons ledger at lines 63-67).

  outside c p is the semantic ground truth at the sites the grammar does not
  reach. It is left as an unconstrained parameter. That is the honest modelling
  choice: those expressions are by definition not elements of expr, so the model
  has nothing to say about them, and pinning them to a convenient value would
  make the interpretation true by fiat exactly where it is weakest.
\<close>

type_synonym 'e sitemap = "'e \<Rightarrow> 'e \<Rightarrow> (expr \<times> reg) option"

definition bv_econst :: "'e sitemap \<Rightarrow> ('e \<Rightarrow> 'e \<Rightarrow> bool) \<Rightarrow> 'e \<Rightarrow> 'e \<Rightarrow> bool" where
  "bv_econst site outside c p =
     (case site c p of None \<Rightarrow> outside c p | Some (e, r) \<Rightarrow> \<not> non_constant e r)"

text \<open>
  The oracle. On a site inside the grammar it is exactly the QF_BV query, which
  by qfbv_query_eq_non_constant is exactly non-constancy. On a site outside the
  grammar it returns False -- not "constant" -- which is the mechanised form of
  the implementation's conservative null handling: an undecided site keeps its
  syntactic edge.
\<close>

definition bv_ounsat :: "'e sitemap \<Rightarrow> 'e \<Rightarrow> 'e \<Rightarrow> bool" where
  "bv_ounsat site c p =
     (case site c p of None \<Rightarrow> False | Some (e, r) \<Rightarrow> \<not> non_constant e r)"

text \<open>
  Branch-freedom. A site is branch-free when it is inside the grammar AND its
  expression contains no relational node. Bundling grammar-membership into
  branch_free is not a convenience: it is the precise reason the exactness
  result is confined to the branch-free fragment. An expression the parser
  cannot read is one the oracle cannot decide, so exactness cannot be claimed
  there.
\<close>

definition bv_bfree :: "'e sitemap \<Rightarrow> 'e \<Rightarrow> 'e \<Rightarrow> bool" where
  "bv_bfree site c p = (\<exists>e r. site c p = Some (e, r) \<and> branch_free_expr e)"

subsection \<open>Both engineering assumptions become theorems\<close>

theorem bv_oracle_soundness:
  "bv_ounsat site c p \<Longrightarrow> bv_econst site outside c p"
  by (auto simp: bv_ounsat_def bv_econst_def split: option.splits prod.splits)

theorem bv_oracle_completeness_branch_free:
  "bv_bfree site c p \<Longrightarrow> bv_econst site outside c p \<Longrightarrow> bv_ounsat site c p"
  by (auto simp: bv_bfree_def bv_ounsat_def bv_econst_def)

text \<open>
  Hence the axiomatization of WEV_SMT_Defs is satisfied by the BV model, for
  every dependency relation, every site labelling and every out-of-grammar
  ground truth. This subsumes and strictly strengthens the informal consistency
  argument recorded in WEV_SMT_Defs.
\<close>

text \<open>
  Note the arity: dep_r does not occur in either locale assumption, so Isabelle's
  generated locale predicate ranges over the three oracle parameters only. The
  dependency relation is therefore quantified away here -- the model satisfies
  the axioms for every dep_r, which is exactly the claim being made.
\<close>

theorem bv_model_is_a_detector:
  "detector_locale (bv_econst site outside) (bv_ounsat site) (bv_bfree site)"
  by unfold_locales
     (auto intro: bv_oracle_soundness bv_oracle_completeness_branch_free)

locale bv_detector =
  fixes dep0 :: "('e \<times> 'e) set"
    and site :: "'e sitemap"
    and outside :: "'e \<Rightarrow> 'e \<Rightarrow> bool"
begin

sublocale detector_locale dep0 "bv_econst site outside" "bv_ounsat site" "bv_bfree site"
  by (rule bv_model_is_a_detector)

text \<open>
  The implemented relation, spelled out. WEV-SMT keeps a syntactic edge exactly
  when the site is outside the modelled grammar (conservative) or the
  consumer's expression genuinely moves with the producer's register. This is a
  theorem about the model, and it is literally the behaviour of
  LitmusParser's detector under QfbvConstancyOracle.
\<close>

lemma sdep_impl_l_unfolded:
  "sdep_impl_l =
     {(p, c). (p, c) \<in> dep0 \<and>
              (case site c p of None \<Rightarrow> True | Some (e, r) \<Rightarrow> non_constant e r)}"
  by (auto simp: sdep_impl_l_def bv_ounsat_def split: option.splits)

lemma sdep_true_l_unfolded:
  "sdep_true_l =
     {(p, c). (p, c) \<in> dep0 \<and>
              (case site c p of None \<Rightarrow> \<not> outside c p | Some (e, r) \<Rightarrow> non_constant e r)}"
  by (auto simp: sdep_true_l_def bv_econst_def split: option.splits)

text \<open>
  Consequently the two relations differ only at sites the grammar does not
  reach: on in-grammar sites the detector is EXACT, not merely sound. The
  branch-free exactness theorem of Detector.thy is the special case of this
  obtained by intersecting with the relop-free fragment.
\<close>

theorem sdep_agree_in_grammar:
  assumes "site c p \<noteq> None"
  shows "((p, c) \<in> sdep_impl_l) = ((p, c) \<in> sdep_true_l)"
  using assms
  by (auto simp: sdep_impl_l_unfolded sdep_true_l_unfolded split: option.splits)

theorem sdep_differ_only_outside_grammar:
  "sdep_impl_l - sdep_true_l \<subseteq> {(p, c). site c p = None}"
  by (auto simp: sdep_impl_l_unfolded sdep_true_l_unfolded split: option.splits)

end

subsection \<open>The conservative treatment of undecided sites is load-bearing\<close>

text \<open>
  QfbvConstancyOracle returns null, not "constant", when its parser cannot read
  an expression, and the detector then keeps the edge. Had the oracle instead
  reported UNSAT on those sites -- an "optimistic" oracle -- the soundness axiom
  would fail outright, and with it Lemma 2 and Theorem 1. Recording this as a
  refutation rather than a remark makes the null-handling in the Java a
  correctness requirement rather than a defensive habit.
\<close>

lemma optimistic_oracle_unsound:
  "\<not> detector_locale (bv_econst (\<lambda>(c :: nat) (p :: nat). None) (\<lambda>c p. False))
                     (\<lambda>c p. True) (bv_bfree (\<lambda>(c :: nat) (p :: nat). None))"
proof
  assume A: "detector_locale
               (bv_econst (\<lambda>(c :: nat) (p :: nat). None) (\<lambda>c p. False))
               (\<lambda>c p. True) (bv_bfree (\<lambda>(c :: nat) (p :: nat). None))"
  interpret D: detector_locale "{} :: (nat \<times> nat) set"
      "bv_econst (\<lambda>(c :: nat) (p :: nat). None) (\<lambda>c p. False)" "\<lambda>c p. True"
      "bv_bfree (\<lambda>(c :: nat) (p :: nat). None)"
    by (rule A)
  have "bv_econst (\<lambda>c p. None) (\<lambda>c p. False) (0 :: nat) (0 :: nat)"
    by (rule D.ax_soundness) simp
  then show False by (simp add: bv_econst_def)
qed

end
