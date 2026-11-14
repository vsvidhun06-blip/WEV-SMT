(*  Title:      PwT_ReadRestricted.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    EXPLORATORY. A class-relative strengthening of PwT_Granularity.

    PwT_Granularity refutes ONE concrete site abstraction: the one built from
    non_constant. It leaves open the obvious repair -- pick a different Boolean
    per-site predicate. This theory closes that repair for a precisely delimited
    class, and ONLY for that class.

    THE CLASS. A site abstraction is a predicate beta :: expr => reg => bool.
    A_read is the sub-class satisfying

        beta e r  ==>  r : regs e

    i.e. a site may only mark registers its expression actually mentions. This is
    not an arbitrary restriction: it is exactly the property that
    PwT_Constancy.non_constant_imp_read proves of the paper's own abstraction, so
    A_read contains the abstraction actually shipped, and contains the obvious
    alternatives (syntactic occurrence, taint, non-zero affine coefficient, ...).

    WHY THE RESTRICTION IS NEEDED, AND NOT COSMETIC. Without it the corresponding
    universal statement is FALSE: an unrestricted beta may set bits at registers
    the expression does not mention, and with enough registers those spare bits
    encode the required distinctions outright. That was established by exhaustive
    synthesis outside Isabelle (research/claimD/), which constructs a working
    (beta, f) pair as soon as 2^|Reg| reaches the number of dependency signatures.
    So the theorem below must not be read as "no Boolean abstraction works".

    WIDTH. The development is fixed at 32-bit words, which satisfies the w >= 2
    hypothesis of the informal argument. At width 1 the witnesses degenerate. We
    do not state a width-general theorem: the expression language here is not
    parameterised by width.

    NO sorry. NO axiomatization.
*)

theory PwT_ReadRestricted
  imports PwT_Granularity
begin

section \<open>Site abstractions and the read-restricted class\<close>

text \<open>
  The generalisation of PwT_Granularity.alpha: the summary of an instruction is
  its target together with the set of registers the abstraction marks. Taking
  beta = non_constant recovers alpha exactly.
\<close>

definition alpha_beta :: "(expr \<Rightarrow> reg \<Rightarrow> bool) \<Rightarrow> prog \<Rightarrow> (reg \<times> reg set) list" where
  "alpha_beta \<beta> P = map (\<lambda>i. (fst i, {r. \<beta> (snd i) r})) P"

lemma alpha_beta_non_constant: "alpha_beta non_constant P = alpha P"
  by (simp add: alpha_beta_def alpha_def alpha_instr_def)

definition A_read :: "(expr \<Rightarrow> reg \<Rightarrow> bool) \<Rightarrow> bool" where
  "A_read \<beta> \<longleftrightarrow> (\<forall>e r. \<beta> e r \<longrightarrow> r \<in> regs e)"

text \<open>The paper's own abstraction lies in the class.\<close>

lemma A_read_non_constant: "A_read non_constant"
  by (simp add: A_read_def non_constant_imp_read)

section \<open>Capacity: a one-register site has exactly two abstract states\<close>

lemma A_read_subset: "A_read \<beta> \<Longrightarrow> {r. \<beta> e r} \<subseteq> regs e"
  by (auto simp: A_read_def)

lemma marks_empty_of_no_regs:
  assumes "A_read \<beta>" and "regs e = {}"
  shows "{r. \<beta> e r} = {}"
  using A_read_subset[OF assms(1), of e] assms(2) by simp

text \<open>
  This is the capacity lemma. On an expression over a single register the marked
  set is forced to be one of exactly two values, whatever beta is -- so a
  one-register site carries one bit, independently of how many registers the
  program declares. This is precisely what the unrestricted class fails to
  guarantee.
\<close>

lemma capacity_two:
  assumes "A_read \<beta>" and "regs e = {x}"
  shows "{r. \<beta> e r} = (if \<beta> e x then {x} else {})"
proof (cases "\<beta> e x")
  case True
  have "{r. \<beta> e r} \<subseteq> {x}" using A_read_subset[OF assms(1), of e] assms(2) by simp
  moreover have "x \<in> {r. \<beta> e r}" using True by simp
  ultimately show ?thesis using True by auto
next
  case False
  have "{r. \<beta> e r} \<subseteq> {x}" using A_read_subset[OF assms(1), of e] assms(2) by simp
  moreover have "x \<notin> {r. \<beta> e r}" using False by simp
  ultimately show ?thesis using False by auto
qed

lemma capacity_card: "A_read \<beta> \<Longrightarrow> regs e = {x} \<Longrightarrow> card {r. \<beta> e r} \<le> 1"
  by (simp add: capacity_two)

section \<open>The three witness expressions and the two continuations\<close>

abbreviation eA :: "reg \<Rightarrow> expr" where "eA _ \<equiv> ENum 0"
abbreviation eB :: "reg \<Rightarrow> expr" where "eB x \<equiv> EReg x"
abbreviation eC :: "reg \<Rightarrow> expr" where "eC x \<equiv> EBin OAnd (EReg x) (ENum 1)"

abbreviation g1 :: "reg \<Rightarrow> expr" where "g1 t \<equiv> EReg t"
abbreviation g2 :: "reg \<Rightarrow> expr" where "g2 t \<equiv> EBin OAnd (EReg t) (ENum 2)"

definition twoP :: "reg \<Rightarrow> reg \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> prog" where
  "twoP t y e g = [(t, e), (y, g)]"

lemma regs_eA [simp]: "regs (eA x) = {}" by simp
lemma regs_eB [simp]: "regs (eB x) = {x}" by simp
lemma regs_eC [simp]: "regs (eC x) = {x}" by simp

subsection \<open>What each program computes\<close>

lemma run_twoP_g1:
  assumes "t \<noteq> y"
  shows "run (twoP t y e (g1 t)) s y = eval e s"
  using assms by (simp add: twoP_def)

lemma run_twoP_g2:
  assumes "t \<noteq> y"
  shows "run (twoP t y e (g2 t)) s y = and (eval e s) 2"
  using assms by (simp add: twoP_def)

subsection \<open>Their program-level dependence\<close>

lemma dep_A_g1:
  assumes "t \<noteq> y"
  shows "\<not> prog_dep (twoP t y (eA x) (g1 t)) x y"
  by (simp add: prog_dep_def run_twoP_g1[OF assms])

lemma dep_A_g2:
  assumes "t \<noteq> y"
  shows "\<not> prog_dep (twoP t y (eA x) (g2 t)) x y"
  by (simp add: prog_dep_def run_twoP_g2[OF assms])

lemma dep_B_g1:
  assumes "t \<noteq> y"
  shows "prog_dep (twoP t y (eB x) (g1 t)) x y"
  unfolding prog_dep_def
proof (intro exI)
  show "run (twoP t y (eB x) (g1 t)) ((\<lambda>_. 0)(x := 0)) y
        \<noteq> run (twoP t y (eB x) (g1 t)) ((\<lambda>_. 0)(x := 1)) y"
    by (simp add: run_twoP_g1[OF assms])
qed

lemma dep_C_g1:
  assumes "t \<noteq> y"
  shows "prog_dep (twoP t y (eC x) (g1 t)) x y"
  unfolding prog_dep_def
proof (intro exI)
  show "run (twoP t y (eC x) (g1 t)) ((\<lambda>_. 0)(x := 0)) y
        \<noteq> run (twoP t y (eC x) (g1 t)) ((\<lambda>_. 0)(x := 1)) y"
    by (simp add: run_twoP_g1[OF assms])
qed

lemma dep_B_g2:
  assumes "t \<noteq> y"
  shows "prog_dep (twoP t y (eB x) (g2 t)) x y"
  unfolding prog_dep_def
proof (intro exI)
  show "run (twoP t y (eB x) (g2 t)) ((\<lambda>_. 0)(x := 0)) y
        \<noteq> run (twoP t y (eB x) (g2 t)) ((\<lambda>_. 0)(x := 2)) y"
    by (simp add: run_twoP_g2[OF assms])
qed

text \<open>
  The masking case: @{term "and (and v 1) 2 = 0"} for every v, so this program's
  output is constant even though both of its sites are non-constant. This is the
  same phenomenon as PwT_Program.maskP.
\<close>

lemma dep_C_g2:
  assumes "t \<noteq> y"
  shows "\<not> prog_dep (twoP t y (eC x) (g2 t)) x y"
  by (simp add: prog_dep_def run_twoP_g2[OF assms] and.assoc)

section \<open>The class-relative impossibility\<close>

text \<open>
  Case analysis, not an abstract pigeonhole. Under A_read the constant expression
  eA is forced to the empty marked set, and eB, eC each land in one of two states.
  Whichever way beta falls, two of the three programs share an abstraction while
  disagreeing on prog_dep.
\<close>

theorem A_read_insufficient:
  fixes t y x :: reg
  assumes rd: "A_read \<beta>" and ne: "t \<noteq> y"
  shows "\<not> (\<exists>f. \<forall>P a b. prog_dep P a b = f (alpha_beta \<beta> P) a b)"
proof
  assume "\<exists>f. \<forall>P a b. prog_dep P a b = f (alpha_beta \<beta> P) a b"
  then obtain f where f: "\<And>P a b. prog_dep P a b = f (alpha_beta \<beta> P) a b" by blast

  have emptyA: "{r. \<beta> (eA x) r} = {}"
    by (rule marks_empty_of_no_regs[OF rd]) simp

  show False
  proof (cases "\<beta> (eB x) x")
    case False                                   \<comment> \<open>eB collides with eA\<close>
    have "{r. \<beta> (eB x) r} = {}" using capacity_two[OF rd, of "eB x" x] False by simp
    hence "alpha_beta \<beta> (twoP t y (eA x) (g1 t)) = alpha_beta \<beta> (twoP t y (eB x) (g1 t))"
      by (simp add: alpha_beta_def twoP_def emptyA)
    thus False using f dep_A_g1[OF ne] dep_B_g1[OF ne] by metis
  next
    case True
    show False
    proof (cases "\<beta> (eC x) x")
      case False                                 \<comment> \<open>eC collides with eA\<close>
      have "{r. \<beta> (eC x) r} = {}" using capacity_two[OF rd, of "eC x" x] False by simp
      hence "alpha_beta \<beta> (twoP t y (eA x) (g1 t)) = alpha_beta \<beta> (twoP t y (eC x) (g1 t))"
        by (simp add: alpha_beta_def twoP_def emptyA)
      thus False using f dep_A_g1[OF ne] dep_C_g1[OF ne] by metis
    next
      case True2: True                           \<comment> \<open>eB collides with eC\<close>
      have "{r. \<beta> (eB x) r} = {x}" using capacity_two[OF rd, of "eB x" x] True by simp
      moreover have "{r. \<beta> (eC x) r} = {x}"
        using capacity_two[OF rd, of "eC x" x] True2 by simp
      ultimately have
        "alpha_beta \<beta> (twoP t y (eB x) (g2 t)) = alpha_beta \<beta> (twoP t y (eC x) (g2 t))"
        by (simp add: alpha_beta_def twoP_def)
      thus False using f dep_B_g2[OF ne] dep_C_g2[OF ne] by metis
    qed
  qed
qed

text \<open>
  Specialising to the shipped abstraction recovers PwT_Granularity's result as a
  corollary, which is a consistency check on the generalisation.
\<close>

corollary granularity_as_instance:
  fixes t y :: reg
  assumes "t \<noteq> y"
  shows "\<not> (\<exists>f. \<forall>P a b. prog_dep P a b = f (alpha P) a b)"
  using A_read_insufficient[OF A_read_non_constant assms]
  by (simp add: alpha_beta_non_constant)

text \<open>
  WHAT THIS DOES NOT SAY.

  (1) It is not a universal Boolean impossibility. Dropping A_read makes the
      statement false; see the header.
  (2) It does not show richer representations are necessary. A Boolean
      abstraction outside A_read suffices in this shape once it has enough spare
      registers, so no such inference is available.
  (3) It is confined to two-instruction programs whose sites read a single
      register, at the 32-bit width of this development.
\<close>

end
