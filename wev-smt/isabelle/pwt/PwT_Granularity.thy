(*  Title:      PwT_Granularity.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 5: how much information a dependency abstraction must retain.

    PwT_Program showed that the site-wise relation OVER-APPROXIMATES program-level
    dependence, and that the inclusion is strict (cancelP, maskP). Those results
    are about ONE composition scheme: transitive closure of site edges. They leave
    open the obvious repair -- keep the same per-site boolean labels, compose them
    more cleverly.

    This theory closes that. It exhibits two programs that receive the SAME
    boolean dependence abstraction -- each instruction summarised by its target
    register together with the SET of registers it non-constantly depends on --
    but which differ in program-level dependence. Since the abstraction is
    identical, NO function of it whatsoever can compute prog_dep: the obstruction
    is the granularity of the boolean summary, not the way the summary is
    composed.

      addP:  t := x + 1  ;  y := t - x      final y = 1      (no dependence)
      dblP:  t := x + x  ;  y := t - x      final y = x      (dependence)

    Both instructions in both programs are non-constant in exactly the same
    registers, so alpha addP = alpha dblP.

    Note the width hypothesis is real but discharged here rather than assumed:
    the argument needs x + x to be non-constant in x, which fails at width 1
    (where x + x = 0 identically). The development is fixed at 32-bit words, so
    the witness applies; at width 1 the two programs would be separated by the
    abstraction itself and the theorem would not hold.

    NO sorry. NO axiomatization. Everything is a definition or a theorem.
*)

theory PwT_Granularity
  imports PwT_Program
begin

section \<open>The boolean dependence abstraction\<close>

text \<open>
  alpha discards every value: an instruction becomes its target register plus the
  set of registers whose value can change its result. This is exactly the
  information a "depends / does not depend" analysis retains, and it is what the
  site-wise relation of PwT_Program is built from.
\<close>

definition alpha_instr :: "instr \<Rightarrow> reg \<times> reg set" where
  "alpha_instr i = (fst i, {r. non_constant (snd i) r})"

definition alpha :: "prog \<Rightarrow> (reg \<times> reg set) list" where
  "alpha P = map alpha_instr P"

section \<open>A doubling site is non-constant (needs width > 1)\<close>

text \<open>
  The companion of real_add_one. At 32 bits, 0 + 0 = 0 and 1 + 1 = 2 differ, so
  x + x varies with x. This is the one place the width matters: at width 1 the
  same expression is constantly 0.
\<close>

lemma real_add_self: "non_constant (EBin OAdd (EReg r) (EReg r)) r"
  unfolding non_constant_def
  by (rule exI[of _ "\<lambda>_. 0"], rule exI[of _ 0], rule exI[of _ 1]) simp

section \<open>The witness pair\<close>

definition addP :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "addP x t y = [(t, EBin OAdd (EReg x) (ENum 1)), (y, EBin OSub (EReg t) (EReg x))]"

definition dblP :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "dblP x t y = [(t, EBin OAdd (EReg x) (EReg x)), (y, EBin OSub (EReg t) (EReg x))]"

subsection \<open>Their runs\<close>

lemma addP_run:
  assumes "x \<noteq> t"
  shows "run (addP x t y) s y = 1"
  using assms by (simp add: addP_def)

lemma dblP_run:
  assumes "x \<noteq> t" and "x \<noteq> y"
  shows "run (dblP x t y) s y = s x"
  using assms by (simp add: dblP_def)

subsection \<open>Their program-level dependence\<close>

lemma addP_no_prog_dep:
  assumes "x \<noteq> t"
  shows "\<not> prog_dep (addP x t y) x y"
  by (simp add: prog_dep_def addP_run[OF assms])

lemma dblP_prog_dep:
  assumes "x \<noteq> t" and "x \<noteq> y"
  shows "prog_dep (dblP x t y) x y"
  unfolding prog_dep_def
proof (intro exI)
  show "run (dblP x t y) ((\<lambda>_. 0)(x := 0)) y
        \<noteq> run (dblP x t y) ((\<lambda>_. 0)(x := 1)) y"
    by (simp add: dblP_run[OF assms])
qed

subsection \<open>They receive the same abstraction\<close>

lemma dep_set_add_one:
  "{r. non_constant (EBin OAdd (EReg x) (ENum 1)) r} = {x}"
proof
  show "{r. non_constant (EBin OAdd (EReg x) (ENum 1)) r} \<subseteq> {x}"
    using non_constant_imp_read by fastforce
  show "{x} \<subseteq> {r. non_constant (EBin OAdd (EReg x) (ENum 1)) r}"
    using real_add_one by simp
qed

lemma dep_set_add_self:
  "{r. non_constant (EBin OAdd (EReg x) (EReg x)) r} = {x}"
proof
  show "{r. non_constant (EBin OAdd (EReg x) (EReg x)) r} \<subseteq> {x}"
    using non_constant_imp_read by fastforce
  show "{x} \<subseteq> {r. non_constant (EBin OAdd (EReg x) (EReg x)) r}"
    using real_add_self by simp
qed

lemma alpha_addP_eq_dblP:
  "alpha (addP x t y) = alpha (dblP x t y)"
  by (simp add: alpha_def alpha_instr_def addP_def dblP_def
                dep_set_add_one dep_set_add_self)

section \<open>The granularity theorem\<close>

text \<open>
  Two programs, one abstraction, two different answers.
\<close>

theorem boolean_abstraction_too_coarse:
  assumes "x \<noteq> t" and "x \<noteq> y"
  shows "alpha (addP x t y) = alpha (dblP x t y)
         \<and> \<not> prog_dep (addP x t y) x y
         \<and> prog_dep (dblP x t y) x y"
  using alpha_addP_eq_dblP addP_no_prog_dep[OF assms(1)]
        dblP_prog_dep[OF assms] by blast

text \<open>
  The consequence, stated as an impossibility: no function of the boolean
  abstraction computes program-level dependence. This quantifies over ALL
  functions f, hence over all composition schemes -- unlike
  PwT_Program.sitewise_not_complete, which refutes one particular composition
  (transitive closure). Concrete distinct register names discharge the
  side-conditions.
\<close>

theorem no_function_of_alpha_computes_prog_dep:
  "\<not> (\<exists>f. \<forall>P x y. prog_dep P x y = f (alpha P) x y)"
proof
  assume "\<exists>f. \<forall>P x y. prog_dep P x y = f (alpha P) x y"
  then obtain f where f: "\<And>P x y. prog_dep P x y = f (alpha P) x y" by blast
  have ne: "(''x'' :: reg) \<noteq> ''t''" "(''x'' :: reg) \<noteq> ''y''" by simp_all
  have "f (alpha (addP ''x'' ''t'' ''y'')) ''x'' ''y''
      = f (alpha (dblP ''x'' ''t'' ''y'')) ''x'' ''y''"
    by (simp add: alpha_addP_eq_dblP)
  moreover have "\<not> prog_dep (addP ''x'' ''t'' ''y'') ''x'' ''y''"
    by (rule addP_no_prog_dep[OF ne(1)])
  moreover have "prog_dep (dblP ''x'' ''t'' ''y'') ''x'' ''y''"
    by (rule dblP_prog_dep[OF ne(1) ne(2)])
  ultimately show False using f by metis
qed

text \<open>
  What this does and does not say. It does NOT say that site-local analysis is
  hopeless: the obstruction is the boolean codomain, not the locality. A richer
  per-instruction summary -- an affine form (coefficient, constant) per register,
  say -- separates addP from dblP immediately, and for a fixed word width and
  register count the space of transfer functions is finite, so an exact bounded
  site-local summary exists in principle. What the theorem rules out is precisely
  the abstraction the site-wise relation of PwT_Program uses, and hence any repair
  that keeps those labels and only changes how they are combined.
\<close>

end
