(*  Title:   PwT_Block.thy
    FSEN 2027 #41, novelty pass.

    Block (procedure) summaries. Lifts ctx_equiv_iff_sem_equiv from one instruction to
    straight-line blocks: two blocks are interchangeable for dependence in every
    surrounding program iff they have the same denotation. Hence every exact block
    summary, i.e. one from which dependence in every context can be computed, refines
    the block's denotation; in particular the block's exact dependence relation is not
    such a summary.

      run_app                   run (P @ Q) s = run Q (run P s)
      run_coinc                 a block's final z depends only on its registers and z
      block_equiv_iff           block_equiv P1 P2 <-> run P1 = run P2
      exact_block_refines_run   an exact block summary refines the denotation
      run_exact_block           the denotation is an exact block summary
      dep_relation_not_exact    the exact dependence relation is not an exact block summary

    No axioms, no sorry, no oracles beyond the HOL kernel.
*)
theory PwT_Block
  imports PwT_Versioned
begin

lemma run_app: "run (P @ Q) s = run Q (run P s)"
  by (induction P arbitrary: s) auto

definition pvars :: "prog \<Rightarrow> reg set" where
  "pvars P = (\<Union>(z, e) \<in> set P. insert z (regs e))"

lemma finite_pvars [simp]: "finite (pvars P)"
  by (auto simp: pvars_def)

lemma pvars_Cons [simp]: "pvars ((z, e) # Q) = insert z (regs e) \<union> pvars Q"
  by (auto simp: pvars_def)

lemma run_coinc:
  "(\<And>w. w \<in> insert z (pvars P) \<Longrightarrow> s1 w = s2 w) \<Longrightarrow> run P s1 z = run P s2 z"
proof (induction P arbitrary: s1 s2)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain x e where i: "i = (x, e)" by (cases i)
  have "eval e s1 = eval e s2"
    by (rule eval_coincidence) (use Cons.prems i in auto)
  then have "\<And>w. w \<in> insert z (pvars Q) \<Longrightarrow> (s1(x := eval e s1)) w = (s2(x := eval e s2)) w"
    using Cons.prems i by auto
  then have "run Q (s1(x := eval e s1)) z = run Q (s2(x := eval e s2)) z"
    by (rule Cons.IH)
  with i show ?case by simp
qed

lemma run_untouched: "x \<notin> pvars P \<Longrightarrow> run P s x = s x"
proof (induction P arbitrary: s)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  with Cons.prems have "x \<noteq> z" "x \<notin> pvars Q" by auto
  with Cons.IH i show ?case by simp
qed

definition pin :: "env \<Rightarrow> reg list \<Rightarrow> prog" where
  "pin s0 rs = map (\<lambda>r. (r, ENum (s0 r))) rs"

lemma run_pin: "run (pin s0 rs) s w = (if w \<in> set rs then s0 w else s w)"
  by (induction rs arbitrary: s) (auto simp: pin_def)

lemma pvars_pin: "pvars (pin s0 rs) = set rs"
  by (auto simp: pin_def pvars_def)

definition block_equiv :: "prog \<Rightarrow> prog \<Rightarrow> bool" where
  "block_equiv P1 P2 \<longleftrightarrow>
     (\<forall>pre post a b. prog_dep (pre @ P1 @ post) a b = prog_dep (pre @ P2 @ post) a b)"

definition probe :: "reg \<Rightarrow> reg \<Rightarrow> word32 \<Rightarrow> prog" where
  "probe x z k = [(x, EBin OMul (ERel RNe (EReg z) (ENum k)) (EBin OAdd (EReg x) (ENum 1)))]"

lemma block_sep:
  assumes d: "run P1 s0 z \<noteq> run P2 s0 z"
  obtains pre post x where
    "\<not> prog_dep (pre @ P1 @ post) x x" and "prog_dep (pre @ P2 @ post) x x"
proof -
  let ?R = "insert z (pvars P1 \<union> pvars P2)"
  obtain rs where rs: "set rs = ?R" using finite_list[of ?R] by auto
  obtain x where x: "x \<notin> ?R"
    using ex_new_if_finite[OF infinite_UNIV_listI, of ?R] by auto
  let ?pre = "pin s0 rs" and ?post = "probe x z (run P1 s0 z)"
  have pinned: "run Pi (run ?pre s) z = run Pi s0 z"
    if "Pi = P1 \<or> Pi = P2" for Pi s
    by (rule run_coinc) (use that rs in \<open>auto simp: run_pin\<close>)
  have xkeep: "run Pi (run ?pre s) x = s x" if "Pi = P1 \<or> Pi = P2" for Pi s
  proof -
    have "x \<notin> pvars Pi" using that x by auto
    then have "run Pi (run ?pre s) x = run ?pre s x" by (rule run_untouched)
    also have "\<dots> = s x" using x rs by (simp add: run_pin)
    finally show ?thesis .
  qed
  have fin: "run (?pre @ Pi @ ?post) s x =
             (if run Pi s0 z \<noteq> run P1 s0 z then 1 else 0) * (s x + 1)"
    if "Pi = P1 \<or> Pi = P2" for Pi s
    using pinned[OF that, of s] xkeep[OF that, of s] by (simp add: run_app probe_def)
  have "\<not> prog_dep (?pre @ P1 @ ?post) x x"
    unfolding prog_dep_def using fin[of P1] by simp
  moreover have "prog_dep (?pre @ P2 @ ?post) x x"
  proof -
    have "run (?pre @ P2 @ ?post) ((\<lambda>_. 0)(x := 0)) x \<noteq>
          run (?pre @ P2 @ ?post) ((\<lambda>_. 0)(x := 1)) x"
      using fin[of P2] d by simp
    then show ?thesis unfolding prog_dep_def by blast
  qed
  ultimately show ?thesis by (rule that)
qed

theorem block_equiv_iff: "block_equiv P1 P2 \<longleftrightarrow> run P1 = run P2"
proof
  assume e: "block_equiv P1 P2"
  show "run P1 = run P2"
  proof (rule ccontr)
    assume "run P1 \<noteq> run P2"
    then have "\<exists>s0 z. run P1 s0 z \<noteq> run P2 s0 z" by (auto simp: fun_eq_iff)
    then obtain s0 z where "run P1 s0 z \<noteq> run P2 s0 z" by blast
    then obtain pre post x where "\<not> prog_dep (pre @ P1 @ post) x x" "prog_dep (pre @ P2 @ post) x x"
      by (rule block_sep)
    with e show False unfolding block_equiv_def by blast
  qed
next
  assume "run P1 = run P2"
  then show "block_equiv P1 P2"
    unfolding block_equiv_def prog_dep_def by (simp add: run_app)
qed

definition exact_block :: "(prog \<Rightarrow> 'c) \<Rightarrow> bool" where
  "exact_block \<sigma> \<longleftrightarrow>
     (\<exists>F. \<forall>P pre post a b. prog_dep (pre @ P @ post) a b = F (\<sigma> P) pre post a b)"

theorem exact_block_refines_run:
  assumes "exact_block \<sigma>" "\<sigma> P1 = \<sigma> P2"
  shows "run P1 = run P2"
proof -
  from assms(1) obtain F where F: "\<And>P pre post a b. prog_dep (pre @ P @ post) a b = F (\<sigma> P) pre post a b"
    unfolding exact_block_def by blast
  have "block_equiv P1 P2"
    unfolding block_equiv_def using F assms(2) by simp
  then show ?thesis by (simp add: block_equiv_iff)
qed

theorem run_exact_block: "exact_block run"
proof -
  define F where "F d pre post a b =
    (\<exists>s v1 v2. run post (d (run pre (s(a := v1)))) b \<noteq> run post (d (run pre (s(a := v2)))) b)"
    for d :: "env \<Rightarrow> env" and pre post a b
  have "\<forall>P pre post a b. prog_dep (pre @ P @ post) a b = F (run P) pre post a b"
    by (simp add: F_def prog_dep_def run_app)
  then show ?thesis unfolding exact_block_def by (rule exI[of _ F])
qed

lemma single_dep:
  "prog_dep [(t, e)] a b \<longleftrightarrow> (if b = t then non_constant e a else a = b)"
proof (cases "b = t")
  case True
  then show ?thesis by (simp add: prog_dep_def non_constant_def)
next
  case False
  have "prog_dep [(t, e)] a b \<longleftrightarrow> a = b"
  proof
    assume "prog_dep [(t, e)] a b"
    then show "a = b" using False by (auto simp: prog_dep_def split: if_splits)
  next
    assume "a = b"
    then have "run [(t, e)] ((\<lambda>_. 0)(a := 0)) b \<noteq> run [(t, e)] ((\<lambda>_. 0)(a := 1)) b"
      using False by simp
    then show "prog_dep [(t, e)] a b" unfolding prog_dep_def by blast
  qed
  with False show ?thesis by simp
qed

lemma nc_reg: "non_constant (EReg x) a \<longleftrightarrow> a = x"
proof
  assume "non_constant (EReg x) a"
  then show "a = x" by (auto simp: non_constant_def split: if_splits)
next
  assume "a = x"
  then have "eval (EReg x) ((\<lambda>_. 0)(a := 0)) \<noteq> eval (EReg x) ((\<lambda>_. 0)(a := 1))" by simp
  then show "non_constant (EReg x) a" unfolding non_constant_def by blast
qed

lemma nc_and1: "non_constant (EBin OAnd (EReg x) (ENum 1)) a \<longleftrightarrow> a = x"
proof
  assume "non_constant (EBin OAnd (EReg x) (ENum 1)) a"
  then show "a = x" by (auto simp: non_constant_def split: if_splits)
next
  assume "a = x"
  then have "eval (EBin OAnd (EReg x) (ENum 1)) ((\<lambda>_. 0)(a := 0)) \<noteq>
             eval (EBin OAnd (EReg x) (ENum 1)) ((\<lambda>_. 0)(a := 1))" by simp
  then show "non_constant (EBin OAnd (EReg x) (ENum 1)) a" unfolding non_constant_def by blast
qed

theorem dep_relation_not_exact:
  assumes "t \<noteq> x"
  shows "{(a, b). prog_dep [(t, EReg x)] a b} = {(a, b). prog_dep [(t, EBin OAnd (EReg x) (ENum 1))] a b}"
    and "run [(t, EReg x)] \<noteq> run [(t, EBin OAnd (EReg x) (ENum 1))]"
    and "\<not> exact_block (\<lambda>P. {(a, b). prog_dep P a b})"
proof -
  show eq: "{(a, b). prog_dep [(t, EReg x)] a b} = {(a, b). prog_dep [(t, EBin OAnd (EReg x) (ENum 1))] a b}"
    by (auto simp: single_dep nc_reg nc_and1)
  have "run [(t, EReg x)] ((\<lambda>_. 0)(x := 2)) t \<noteq> run [(t, EBin OAnd (EReg x) (ENum 1))] ((\<lambda>_. 0)(x := 2)) t"
    using assms by simp
  then show ne: "run [(t, EReg x)] \<noteq> run [(t, EBin OAnd (EReg x) (ENum 1))]" by metis
  show "\<not> exact_block (\<lambda>P. {(a, b). prog_dep P a b})"
    using exact_block_refines_run[of "\<lambda>P. {(a, b). prog_dep P a b}"] eq ne by blast
qed

end
