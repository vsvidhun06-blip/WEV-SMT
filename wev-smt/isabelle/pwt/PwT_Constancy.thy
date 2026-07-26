(*  Title:      PwT_Constancy.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 2, part 1: the concrete non-constancy predicate that the QF_BV oracle
    decides, and the structural lemmas about it that the later steps need.

    WEV_SMT_Defs leaves expression_constant uninterpreted. This theory supplies
    the interpretation: expression_constant is the negation of non_constant
    below, and non_constant is stated over the concrete BV32 semantics of
    PwT_Expr rather than over an abstract event predicate. PwT_Model then
    discharges the abstraction by exhibiting the model.
*)

theory PwT_Constancy
  imports PwT_Expr
begin

section \<open>Non-constancy: what the oracle decides\<close>

text \<open>
  The implemented query (QfbvConstancyOracle lines 103-116) builds two
  environments that share the valuation of every register other than reg and
  differ at reg, then asks Z3 whether the two evaluations can differ. That is
  literally the statement below: one shared environment s, two values v1, v2 for
  the differentiated register, existentially quantified together.

  Note which quantifier sits where. s is EXISTENTIALLY quantified -- the other
  registers are free variables in the Z3 query, and SAT means "there is some
  context in which the expression moves with reg". This is the weaker,
  dependency-admitting reading, and it is deliberate: over-approximating the
  dependency relation is the sound direction for Theorem 1.
\<close>

definition non_constant :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "non_constant e r \<longleftrightarrow> (\<exists>s v1 v2. eval e (s(r := v1)) \<noteq> eval e (s(r := v2)))"

text \<open>
  The pointwise-independence form. Stating both and proving them equivalent
  costs one lemma and buys a great deal downstream: non_constant is the natural
  form for exhibiting witnesses, indep is the natural form for rewriting under
  an evaluation.
\<close>

definition indep :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "indep e r \<longleftrightarrow> (\<forall>s v. eval e (s(r := v)) = eval e s)"

lemma indep_iff_constant: "indep e r \<longleftrightarrow> \<not> non_constant e r"
proof
  assume "indep e r"
  then show "\<not> non_constant e r"
    by (auto simp: indep_def non_constant_def)
next
  assume nc: "\<not> non_constant e r"
  then have all: "\<forall>s v1 v2. eval e (s(r := v1)) = eval e (s(r := v2))"
    by (simp add: non_constant_def)
  show "indep e r"
    unfolding indep_def
  proof (intro allI)
    fix s v
    from all have "eval e (s(r := v)) = eval e (s(r := s r))" by blast
    then show "eval e (s(r := v)) = eval e s" by simp
  qed
qed

lemma non_constant_iff_not_indep: "non_constant e r \<longleftrightarrow> \<not> indep e r"
  by (simp add: indep_iff_constant)

section \<open>The oracle's Javadoc form is the implemented form\<close>

text \<open>
  QfbvConstancyOracle documents its query as a substitution,

      \<exists> (other regs), r1, r2 .  e[reg := r1] \<noteq> e[reg := r2]

  but implements it by varying an environment. The two are the same statement.
  This is a small lemma but it is exactly the kind of gap where an artifact
  claim silently stops matching its code, so it is proved rather than asserted.
\<close>

definition qfbv_query :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "qfbv_query e r \<longleftrightarrow>
     (\<exists>s v1 v2. eval (subst e r (ENum v1)) s \<noteq> eval (subst e r (ENum v2)) s)"

theorem qfbv_query_eq_non_constant: "qfbv_query e r \<longleftrightarrow> non_constant e r"
  by (auto simp: qfbv_query_def non_constant_def eval_subst)

section \<open>Non-constancy only in registers actually read\<close>

lemma indep_of_unread:
  assumes "r \<notin> regs e"
  shows "indep e r"
  unfolding indep_def
proof (intro allI)
  fix s v
  show "eval e (s(r := v)) = eval e s"
    by (rule eval_coincidence) (use assms in auto)
qed

lemma non_constant_imp_read: "non_constant e r \<Longrightarrow> r \<in> regs e"
  using indep_of_unread indep_iff_constant by blast

section \<open>The hybrid lemma\<close>

text \<open>
  If two environments differ only on registers the expression is independent of,
  the expression evaluates the same in both. The proof is a hybrid argument:
  walk from one environment to the other one register at a time, each step
  invisible to the expression by independence.

  The walk must be finite, and env is a total function on an infinite type of
  identifiers, so the finiteness has to come from somewhere. It comes from
  regs e: the coincidence lemma of PwT_Expr lets the difference set be trimmed
  to S \<inter> regs e before the induction starts.
\<close>

lemma eval_eq_of_indep_on:
  assumes "finite D"
      and "\<And>x. x \<notin> D \<Longrightarrow> s1 x = s2 x"
      and "\<And>x. x \<in> D \<Longrightarrow> indep e x"
  shows "eval e s1 = eval e s2"
  using assms
proof (induction D arbitrary: s1)
  case empty
  then have "s1 = s2" by auto
  then show ?case by simp
next
  case (insert a F)
  let ?s1' = "s1(a := s2 a)"
  have agree: "\<And>x. x \<notin> F \<Longrightarrow> ?s1' x = s2 x"
  proof -
    fix x assume "x \<notin> F"
    show "?s1' x = s2 x"
    proof (cases "x = a")
      case True then show ?thesis by simp
    next
      case False
      with \<open>x \<notin> F\<close> have "x \<notin> insert a F" by simp
      with insert.prems(1) show ?thesis using False by simp
    qed
  qed
  have "eval e ?s1' = eval e s2"
    by (rule insert.IH) (use agree insert.prems(2) in auto)
  moreover have "eval e ?s1' = eval e s1"
    using insert.prems(2)[of a] by (simp add: indep_def)
  ultimately show ?case by simp
qed

text \<open>
  Contrapositive, and the form actually used: if two environments agree outside
  a set S and the expression nonetheless evaluates differently, then some
  register in S is one the expression genuinely depends on. This is the bridge
  from "the value changed" to "there is a dependency edge", and it is the engine
  of the program-level soundness theorem in PwT_Program.
\<close>

theorem diff_witness:
  assumes agree: "\<And>x. x \<notin> S \<Longrightarrow> s1 x = s2 x"
      and diff: "eval e s1 \<noteq> eval e s2"
  shows "\<exists>x \<in> S. non_constant e x"
proof (rule ccontr)
  assume "\<not> (\<exists>x \<in> S. non_constant e x)"
  then have indep_S: "\<And>x. x \<in> S \<Longrightarrow> indep e x"
    by (simp add: indep_iff_constant)

  \<comment> \<open>Trim s2 to agree with s1 outside the (finite) read set of e.\<close>
  let ?s2' = "\<lambda>x. if x \<in> regs e then s2 x else s1 x"
  have trim: "eval e ?s2' = eval e s2"
    by (rule eval_coincidence) simp

  let ?D = "S \<inter> regs e"
  have "finite ?D" by simp
  moreover have "\<And>x. x \<notin> ?D \<Longrightarrow> s1 x = ?s2' x"
  proof -
    fix x assume "x \<notin> ?D"
    show "s1 x = ?s2' x"
    proof (cases "x \<in> regs e")
      case False then show ?thesis by simp
    next
      case True
      with \<open>x \<notin> ?D\<close> have "x \<notin> S" by simp
      with agree True show ?thesis by simp
    qed
  qed
  moreover have "\<And>x. x \<in> ?D \<Longrightarrow> indep e x" using indep_S by simp
  ultimately have "eval e s1 = eval e ?s2'"
    by (rule eval_eq_of_indep_on)
  with trim diff show False by simp
qed

section \<open>Worked instances: the fake patterns and their real counterparts\<close>

text \<open>
  These are the classification verdicts the detector must produce on the
  patterns that appear in the corpus. They are now theorems about the BV32
  semantics rather than assumptions about an uninterpreted predicate.
\<close>

lemma fake_xor_self: "\<not> non_constant (EBin OXor (EReg r) (EReg r)) r"
  by (simp add: non_constant_def)

lemma fake_xor_self_plus_one:
  "\<not> non_constant (EBin OXor (EBin OXor (EReg r) (EReg r)) (ENum 1)) r"
  by (simp add: non_constant_def)

lemma fake_sub_self: "\<not> non_constant (EBin OSub (EReg r) (EReg r)) r"
  by (simp add: non_constant_def)

lemma fake_and_zero: "\<not> non_constant (EBin OAnd (EReg r) (ENum 0)) r"
  by (simp add: non_constant_def)

lemma real_identity: "non_constant (EReg r) r"
  unfolding non_constant_def
  by (rule exI[of _ "\<lambda>_. 0"], rule exI[of _ 0], rule exI[of _ 1]) simp

lemma real_add_one: "non_constant (EBin OAdd (EReg r) (ENum 1)) r"
  unfolding non_constant_def
  by (rule exI[of _ "\<lambda>_. 0"], rule exI[of _ 0], rule exI[of _ 1]) simp

lemma real_mask_two: "non_constant (EBin OAnd (EReg r) (ENum 2)) r"
  unfolding non_constant_def
  by (rule exI[of _ "\<lambda>_. 0"], rule exI[of _ 0], rule exI[of _ 2]) simp

lemma real_mask_one: "non_constant (EBin OAnd (EReg r) (ENum 1)) r"
  unfolding non_constant_def
  by (rule exI[of _ "\<lambda>_. 0"], rule exI[of _ 0], rule exI[of _ 1]) simp

lemma real_xor_other:
  assumes "r \<noteq> q"
  shows "non_constant (EBin OXor (EReg r) (EReg q)) r"
  unfolding non_constant_def
  using assms
  by (rule_tac exI[of _ "\<lambda>_. 0"], rule_tac exI[of _ 0], rule_tac exI[of _ 1]) simp

end
