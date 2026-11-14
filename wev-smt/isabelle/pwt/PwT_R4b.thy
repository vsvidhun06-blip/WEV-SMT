(*  Title:      PwT_R4b.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 3: the per-site PwT correspondence, mechanised.

    Three criteria are formalised here and related to each other exactly.

      pwt_two_point   the universal-v two-point check the paper attributes to
                      PwT's R4b, and the criterion the sweep in
                      src/main/java/wev/smt/cli/PwtEquivalenceSweep.java
                      (nonConstantIn, lines 145-172) actually computes: probe
                      the register at many values and compare each against the
                      value at 0.

      non_constant    WEAKEST's criterion, decided exactly by the QF_BV oracle
                      (PwT_Constancy).

      exact_r4b       PwT's R4b as published: a two-point check anchored at the
                      OBSERVED execution, i.e. comparing the value the
                      expression actually took against its value with the
                      register zeroed.

    Result (a) of the task is what is delivered: exact_r4b is formalised and its
    divergence from non_constant is characterised completely, not merely
    illustrated. See findings-correspondence.md.
*)

theory PwT_R4b
  imports PwT_Constancy
begin

section \<open>PwT's universal-v two-point check\<close>

text \<open>
  Anchoring the reference point at 0 rather than searching over all pairs is
  what PwtEquivalenceSweep does (first is bound on the first iteration and never
  reassigned, and PROBE[0] = 0). The lemma below is the formal content of the
  informal remark in findings-pwt.md \<section>3 that anchoring loses nothing.
\<close>

definition pwt_two_point :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "pwt_two_point e r \<longleftrightarrow> (\<exists>s v. eval e (s(r := v)) \<noteq> eval e (s(r := 0)))"

theorem pwt_two_point_eq_non_constant: "pwt_two_point e r \<longleftrightarrow> non_constant e r"
proof
  assume "pwt_two_point e r"
  then show "non_constant e r" by (auto simp: pwt_two_point_def non_constant_def)
next
  assume "non_constant e r"
  then obtain s v1 v2 where d: "eval e (s(r := v1)) \<noteq> eval e (s(r := v2))"
    by (auto simp: non_constant_def)
  show "pwt_two_point e r"
  proof (cases "eval e (s(r := v1)) = eval e (s(r := 0))")
    case True
    with d have "eval e (s(r := v2)) \<noteq> eval e (s(r := 0))" by simp
    then show ?thesis by (auto simp: pwt_two_point_def)
  next
    case False
    then show ?thesis by (auto simp: pwt_two_point_def)
  qed
qed

text \<open>
  Soundness of the sampled implementation. PwtEquivalenceSweep is a finite probe
  battery, not a decision procedure: it is sound in the REAL direction and only
  heuristically complete in the FAKE direction. This lemma is the sound
  direction -- a witnessed difference is a genuine dependency, whatever the
  probe schedule.
\<close>

lemma probe_sound:
  "eval e (s(r := v1)) \<noteq> eval e (s(r := v2)) \<Longrightarrow> non_constant e r"
  by (auto simp: non_constant_def)

section \<open>Which quantifier sits on the other registers\<close>

text \<open>
  The oracle shares the other registers between the two copies and leaves them
  free, so its criterion is "there EXISTS a context in which the expression
  moves with reg", not "in every context". The two readings are genuinely
  different, and the weaker (dependency-admitting) one is the one implemented.
  The paper should say so; the separation below is the evidence.
\<close>

definition non_constant_every_ctx :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "non_constant_every_ctx e r \<longleftrightarrow>
     (\<forall>s. \<exists>v1 v2. eval e (s(r := v1)) \<noteq> eval e (s(r := v2)))"

lemma every_ctx_imp_non_constant:
  "non_constant_every_ctx e r \<Longrightarrow> non_constant e r"
  by (auto simp: non_constant_every_ctx_def non_constant_def)

text \<open>
  Strictly weaker: r AND q moves with r in the context q = 1 but is frozen at 0
  in the context q = 0. WEAKEST admits the dependency; the every-context reading
  would reject it.
\<close>

theorem every_ctx_strictly_stronger:
  assumes "r \<noteq> q"
  shows "non_constant (EBin OAnd (EReg r) (EReg q)) r \<and>
         \<not> non_constant_every_ctx (EBin OAnd (EReg r) (EReg q)) r"
proof
  show "non_constant (EBin OAnd (EReg r) (EReg q)) r"
    unfolding non_constant_def
    using assms
    by (rule_tac exI[of _ "\<lambda>_. 1"], rule_tac exI[of _ 0], rule_tac exI[of _ 1]) simp
next
  show "\<not> non_constant_every_ctx (EBin OAnd (EReg r) (EReg q)) r"
    unfolding non_constant_every_ctx_def
    using assms by (auto intro!: exI[of _ "\<lambda>_. 0"])
qed

section \<open>PwT's exact, observation-anchored R4b\<close>

text \<open>
  R4b as published compares the value the expression actually took in the
  observed execution against its value with the register zeroed. Both the
  register under test AND the other registers are pinned to the observation --
  that is the whole difference from the universal-v variant.
\<close>

definition exact_r4b :: "expr \<Rightarrow> reg \<Rightarrow> env \<Rightarrow> bool" where
  "exact_r4b e r s \<longleftrightarrow> eval e s \<noteq> eval e (s(r := 0))"

subsection \<open>Exact R4b is sound but never complete\<close>

theorem exact_r4b_sound: "exact_r4b e r s \<Longrightarrow> non_constant e r"
proof -
  assume "exact_r4b e r s"
  then have "eval e (s(r := s r)) \<noteq> eval e (s(r := 0))"
    by (simp add: exact_r4b_def)
  then show ?thesis by (rule probe_sound)
qed

text \<open>
  The sharpest form of the incompleteness, and it costs two lines: at any
  observation in which the register already holds 0, the observed point and the
  reference point coincide, so exact R4b reports no dependency no matter how
  strongly the expression depends on the register. Exact R4b therefore cannot be
  pointwise equivalent to non-constancy for ANY non-constant expression --
  masking is not a corner case of the comparison, it is generic.
\<close>

lemma exact_r4b_blind_at_zero:
  assumes "s r = 0"
  shows "\<not> exact_r4b e r s"
proof -
  from assms have "s(r := 0) = s" by (rule fun_upd_idem)
  then show ?thesis by (simp add: exact_r4b_def)
qed

corollary exact_r4b_never_pointwise_complete:
  assumes "non_constant e r"
  shows "\<exists>s. non_constant e r \<and> \<not> exact_r4b e r s"
  using assms exact_r4b_blind_at_zero[of "\<lambda>_. 0" r e] by auto

subsection \<open>The exact characterisation of the gap\<close>

text \<open>
  This is the STEP 3 result. WEAKEST's criterion is precisely PwT's exact R4b
  quantified over observations: an expression is non-constant in a register iff
  there EXISTS an observation at which R4b fires. The two criteria therefore do
  not differ in what they can detect at all -- they differ only in how much of
  the observation is held fixed, and WEAKEST's is the existential closure of
  PwT's over the environment.
\<close>

theorem non_constant_iff_some_observation:
  "non_constant e r \<longleftrightarrow> (\<exists>s. exact_r4b e r s)"
proof
  assume "non_constant e r"
  then have "pwt_two_point e r" by (simp add: pwt_two_point_eq_non_constant)
  then obtain s v where "eval e (s(r := v)) \<noteq> eval e (s(r := 0))"
    by (auto simp: pwt_two_point_def)
  moreover have "(s(r := v))(r := 0) = s(r := 0)" by simp
  ultimately have "exact_r4b e r (s(r := v))" by (simp add: exact_r4b_def)
  then show "\<exists>s. exact_r4b e r s" by blast
next
  assume "\<exists>s. exact_r4b e r s"
  then show "non_constant e r" using exact_r4b_sound by blast
qed

text \<open>
  Consequently the divergence class is exactly the masking observations: those
  at which the expression's observed value happens to coincide with its value at
  register 0, even though some other observation would expose the dependency.
\<close>

theorem divergence_class:
  assumes "non_constant e r"
  shows "{s. \<not> exact_r4b e r s} = {s. eval e s = eval e (s(r := 0))}"
  by (auto simp: exact_r4b_def)

subsection \<open>The concrete witness of findings-pwt.md \<section>4\<close>

text \<open>
  e = r AND 2 observed at r = 1. The observed value is 0 and the zeroed value is
  0, so exact R4b sees no dependency; but r = 2 gives 2, so the expression is
  non-constant and WEAKEST keeps the edge. This is the smallest divergence, and
  it is a masking expression -- a register ANDed with an immediate.
\<close>

abbreviation mask2 :: "reg \<Rightarrow> expr" where
  "mask2 r \<equiv> EBin OAnd (EReg r) (ENum 2)"

theorem masking_witness:
  "non_constant (mask2 r) r \<and> \<not> exact_r4b (mask2 r) r ((\<lambda>_. 0)(r := 1))"
proof
  show "non_constant (mask2 r) r" by (rule real_mask_two)
next
  show "\<not> exact_r4b (mask2 r) r ((\<lambda>_. 0)(r := 1))"
    by (simp add: exact_r4b_def)
qed

section \<open>The fragment on which the two criteria do agree\<close>

text \<open>
  Injectivity in the register is the property that rules masking out. It is
  exactly the property the fake-dependency corpus's REAL patterns have and the
  masking patterns lack, so it is the right dividing line to state in the paper.
\<close>

definition inj_in :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "inj_in e r \<longleftrightarrow>
     (\<forall>s v1 v2. eval e (s(r := v1)) = eval e (s(r := v2)) \<longrightarrow> v1 = v2)"

lemma inj_inD:
  assumes "inj_in e r" and "eval e (s(r := v1)) = eval e (s(r := v2))"
  shows "v1 = v2"
  using assms unfolding inj_in_def by blast

lemma inj_in_imp_non_constant:
  assumes "inj_in e r"
  shows "non_constant e r"
proof -
  have "eval e ((\<lambda>_. 0)(r := 0)) \<noteq> eval e ((\<lambda>_. 0)(r := 1))"
  proof
    assume "eval e ((\<lambda>_. 0)(r := 0)) = eval e ((\<lambda>_. 0)(r := 1))"
    from inj_inD[OF assms this] show False by simp
  qed
  then show ?thesis by (rule probe_sound)
qed

text \<open>
  On the injective fragment, exact R4b and non-constancy agree at every
  observation except the degenerate one where the register already holds the
  reference value. Nothing better is possible: exact_r4b_blind_at_zero says the
  exception is unavoidable.
\<close>

theorem inj_in_exact_r4b:
  assumes "inj_in e r"
  shows "exact_r4b e r s \<longleftrightarrow> s r \<noteq> 0"
proof
  assume "exact_r4b e r s"
  then show "s r \<noteq> 0" using exact_r4b_blind_at_zero by blast
next
  assume nz: "s r \<noteq> 0"
  have "eval e (s(r := s r)) \<noteq> eval e (s(r := 0))"
  proof
    assume "eval e (s(r := s r)) = eval e (s(r := 0))"
    from inj_inD[OF assms this] nz show False by simp
  qed
  then show "exact_r4b e r s" by (simp add: exact_r4b_def)
qed

subsection \<open>Which corpus patterns are injective\<close>

lemma inj_in_add_const: "inj_in (EBin OAdd (EReg r) (ENum c)) r"
  by (simp add: inj_in_def)

lemma inj_in_sub_const: "inj_in (EBin OSub (EReg r) (ENum c)) r"
  by (simp add: inj_in_def)

lemma inj_in_uminus: "inj_in (EUn UMinus (EReg r)) r"
  by (simp add: inj_in_def)

lemma inj_in_bnot: "inj_in (EUn BNot (EReg r)) r"
  unfolding inj_in_def
proof (intro allI impI)
  fix s :: env and v1 v2 :: word32
  assume "eval (EUn BNot (EReg r)) (s(r := v1)) = eval (EUn BNot (EReg r)) (s(r := v2))"
  then have "NOT v1 = (NOT v2 :: word32)" by simp
  then have "NOT (NOT v1) = (NOT (NOT v2) :: word32)" by simp
  then show "v1 = v2" by simp
qed

lemma xor_cancel_right:
  fixes a b c :: word32
  assumes "a XOR c = b XOR c"
  shows "a = b"
proof -
  have "(a XOR c) XOR c = (b XOR c) XOR c" using assms by simp
  then show ?thesis by (simp add: xor.assoc)
qed

lemma inj_in_xor_const: "inj_in (EBin OXor (EReg r) (ENum c)) r"
  unfolding inj_in_def
  by (auto intro: xor_cancel_right)

lemma inj_in_xor_reg:
  assumes "r \<noteq> q"
  shows "inj_in (EBin OXor (EReg r) (EReg q)) r"
  unfolding inj_in_def
  using assms by (auto intro: xor_cancel_right)

lemma inj_in_add_reg:
  assumes "r \<noteq> q"
  shows "inj_in (EBin OAdd (EReg r) (EReg q)) r"
  unfolding inj_in_def using assms by simp

lemma inj_in_sub_reg:
  assumes "r \<noteq> q"
  shows "inj_in (EBin OSub (EReg r) (EReg q)) r"
  unfolding inj_in_def using assms by simp

text \<open>
  And which are not. Both AND forms fall outside the injective fragment, so both
  can host a masking divergence: the immediate form does so already at r = 1
  (masking_witness), and the register-on-register form does so in any context
  where the other operand has a zero bit. The corpus swept in
  eval/pwt-equivalence-sweep.csv contains only the register-on-register form,
  and at observations that happen not to expose the divergence -- which is why
  that sweep is silent on the variant-versus-exact-R4b question.
\<close>

lemma not_inj_in_and_const: "\<not> inj_in (EBin OAnd (EReg r) (ENum 2)) r"
proof
  assume inj: "inj_in (EBin OAnd (EReg r) (ENum 2)) r"
  have "eval (EBin OAnd (EReg r) (ENum 2)) ((\<lambda>_. 0)(r := 0))
        = eval (EBin OAnd (EReg r) (ENum 2)) ((\<lambda>_. 0)(r := 1))"
    by simp
  from inj_inD[OF inj this] show False by simp
qed

lemma not_inj_in_and_reg:
  assumes rq: "r \<noteq> q"
  shows "\<not> inj_in (EBin OAnd (EReg r) (EReg q)) r"
proof
  assume inj: "inj_in (EBin OAnd (EReg r) (EReg q)) r"
  have "eval (EBin OAnd (EReg r) (EReg q)) ((\<lambda>_. 0)(r := 0))
        = eval (EBin OAnd (EReg r) (EReg q)) ((\<lambda>_. 0)(r := 1))"
    using rq by simp
  from inj_inD[OF inj this] show False by simp
qed

end
