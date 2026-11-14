(*  Title:      PwT_Program.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 4: lifting the per-site correspondence to a whole branch-free program.

    SUMMARY OF WHAT IS AND IS NOT PROVED HERE (the honest version).

      PROVED, in full, no assumptions:
        prog_dep_sound   -- WEAKEST's site-wise dependency relation, composed
                            across a branch-free program, OVER-APPROXIMATES the
                            program-level semantic dependency: if the final value
                            of y moves with the initial value of x, then there is
                            a chain of site-wise edges from x to y.
                            This is the direction Theorem 1 needs.

      PROVED FALSE, with witnesses:
        sitewise_not_complete       -- the converse fails: a site-wise chain can
                            exist where no program-level dependency does. Witness:
                            t := x + 1; y := t - x, whose final y is the constant
                            1. Per-site non-constancy is not preserved under
                            composition.
        sitewise_not_complete_single_read -- and it fails even when every
                            instruction reads exactly one register, so the cause
                            is not re-reading a value. Witness:
                            t := x & 1; y := t & 2, whose final y is 0. This is
                            the same masking class that obstructs the per-site
                            exact-R4b agreement in PwT_R4b, which is the point:
                            one phenomenon, two consequences.

      PROVED, on a fragment:
        pipeline_exact   -- on an injective pipeline (every step a bijection in
                            the carried register) the over-approximation is
                            tight: both criteria fire.

    So the program-level correspondence is an INCLUSION, not an equality, and the
    inclusion is strict. That is not a gap in the proof; it is a property of the
    criteria, and it is now a theorem rather than a caveat. What remains open is
    a characterisation of exactly which programs are exact -- see
    findings-correspondence.md.
*)

theory PwT_Program
  imports PwT_R4b
begin

section \<open>Branch-free programs\<close>

text \<open>
  A branch-free program is a list of assignments, executed in order. This is the
  fragment the detector is exact on per-site (Detector.detector_exactness), so it
  is the fragment on which a program-level lifting could conceivably hold.

  Registers are not in SSA form: an instruction may overwrite a register that an
  earlier one wrote, and the site-wise relation below is over register NAMES, so
  it deliberately conflates versions. That conflation is a source of
  over-approximation and is part of what the soundness theorem has to survive.
\<close>

type_synonym instr = "reg \<times> expr"
type_synonym prog = "instr list"

fun run :: "prog \<Rightarrow> env \<Rightarrow> env" where
  "run [] s = s"
| "run ((x, e) # P) s = run P (s(x := eval e s))"

section \<open>The two program-level relations\<close>

text \<open>
  sites P is what WEAKEST computes: one edge per assignment, from each register
  the assigned expression is genuinely non-constant in, to the assigned register.
  Per-site this is exactly the QF_BV verdict (PwT_Constancy, PwT_Model).
\<close>

definition sites :: "prog \<Rightarrow> (reg \<times> reg) set" where
  "sites P = {(r, x). \<exists>e. (x, e) \<in> set P \<and> non_constant e r}"

lemma sites_Nil [simp]: "sites [] = {}"
  by (simp add: sites_def)

lemma sites_Cons: "sites ((z, e) # Q) = {(r, z) |r. non_constant e r} \<union> sites Q"
  by (auto simp: sites_def)

lemma sites_mono_Cons: "sites Q \<subseteq> sites ((z, e) # Q)"
  by (auto simp: sites_def)

text \<open>
  prog_dep P x y is PwT's notion at the level of a whole program, stated exactly
  as the per-site criterion was: the final value of y moves with the initial
  value of x. Nothing about the criterion changes; only the function whose
  constancy is asked about does, from a single expression to the whole program's
  input-output map.
\<close>

definition prog_dep :: "prog \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> bool" where
  "prog_dep P x y \<longleftrightarrow> (\<exists>s v1 v2. run P (s(x := v1)) y \<noteq> run P (s(x := v2)) y)"

subsection \<open>Positive controls\<close>

text \<open>
  Two of the results below are NEGATIVE -- they assert the absence of a
  program-level dependency. A mis-stated prog_dep or run would make such results
  hold vacuously, so both definitions are pinned down against programs whose
  answer is obvious first.
\<close>

lemma copy_prog_dep:
  assumes "x \<noteq> y"
  shows "prog_dep [(y, EReg x)] x y"
  unfolding prog_dep_def
  using assms
  by (rule_tac exI[of _ "\<lambda>_. 0"], rule_tac exI[of _ 0], rule_tac exI[of _ 1]) simp

lemma copy_sites:
  "sites [(y, EReg x)] = {(x, y)}"
  using real_identity
  by (auto simp: sites_def dest!: non_constant_imp_read)

lemma const_no_prog_dep:
  assumes "x \<noteq> y"
  shows "\<not> prog_dep [(y, ENum 5)] x y"
  using assms by (simp add: prog_dep_def)

lemma untouched_prog_dep:
  "prog_dep [] x x"
  unfolding prog_dep_def
  by (rule_tac exI[of _ "\<lambda>_. 0"], rule_tac exI[of _ 0], rule_tac exI[of _ 1]) simp

section \<open>The soundness theorem\<close>

text \<open>
  The generalised statement that carries the induction: environments differing
  anywhere in a set S, rather than at a single register. This generalisation is
  forced. Executing one instruction turns a difference at S into a difference at
  S together with the assigned register, so an induction hypothesis about
  single-register differences would not apply to the tail of the program.

  The reflexive-transitive closure, not the transitive one, is what holds: a
  register the program never touches flows to itself along the empty chain.
\<close>

lemma flow:
  "(\<forall>r. r \<notin> S \<longrightarrow> s1 r = s2 r) \<Longrightarrow> run P s1 y \<noteq> run P s2 y
     \<Longrightarrow> \<exists>x \<in> S. (x, y) \<in> rtrancl (sites P)"
proof (induction P arbitrary: s1 s2 S)
  case Nil
  then have "s1 y \<noteq> s2 y" by simp
  with Nil.prems(1) have "y \<in> S" by blast
  then show ?case by auto
next
  case (Cons i Q)
  obtain z e where iz: "i = (z, e)" by (cases i) auto

  let ?t1 = "s1(z := eval e s1)"
  let ?t2 = "s2(z := eval e s2)"

  from Cons.prems(2) iz have tail: "run Q ?t1 y \<noteq> run Q ?t2 y" by simp

  have sub: "rtrancl (sites Q) \<subseteq> rtrancl (sites ((z, e) # Q))"
    by (rule rtrancl_mono[OF sites_mono_Cons])

  show ?case
  proof (cases "eval e s1 = eval e s2")
    case True
    \<comment> \<open>The assignment writes the same value in both runs, so the difference set
        does not grow and the tail is handled by the induction hypothesis at S.\<close>
    have "\<forall>r. r \<notin> S \<longrightarrow> ?t1 r = ?t2 r"
      using Cons.prems(1) True by simp
    from Cons.IH[OF this tail] obtain x where
      xS: "x \<in> S" and xy: "(x, y) \<in> rtrancl (sites Q)" by blast
    from xS xy sub iz show ?thesis by auto
  next
    case False
    \<comment> \<open>The assignment writes different values, so by diff_witness some register
        in S is one the assigned expression genuinely depends on -- that is the
        new site-wise edge into z.\<close>
    have "\<exists>a \<in> S. non_constant e a"
      by (rule diff_witness) (use Cons.prems(1) False in auto)
    then obtain a where aS: "a \<in> S" and anc: "non_constant e a" by blast
    then have edge: "(a, z) \<in> sites ((z, e) # Q)" by (auto simp: sites_Cons)

    have "\<forall>r. r \<notin> insert z S \<longrightarrow> ?t1 r = ?t2 r"
      using Cons.prems(1) by simp
    from Cons.IH[OF this tail] obtain x where
      xS: "x \<in> insert z S" and xy: "(x, y) \<in> rtrancl (sites Q)" by blast

    show ?thesis
    proof (cases "x \<in> S")
      case True
      with xy sub iz show ?thesis by auto
    next
      case False
      with xS have "x = z" by simp
      with xy sub have "(z, y) \<in> rtrancl (sites ((z, e) # Q))" by auto
      with edge have "(a, y) \<in> rtrancl (sites ((z, e) # Q))"
        by (rule converse_rtrancl_into_rtrancl)
      with aS iz show ?thesis by auto
    qed
  qed
qed

theorem prog_dep_sound:
  assumes "prog_dep P x y"
  shows "(x, y) \<in> rtrancl (sites P)"
proof -
  from assms obtain s v1 v2 where
    d: "run P (s(x := v1)) y \<noteq> run P (s(x := v2)) y"
    by (auto simp: prog_dep_def)
  have "\<forall>r. r \<notin> {x} \<longrightarrow> (s(x := v1)) r = (s(x := v2)) r" by simp
  from flow[OF this d] show ?thesis by blast
qed

corollary prog_dep_subset_sites:
  "{(x, y). prog_dep P x y} \<subseteq> rtrancl (sites P)"
  using prog_dep_sound by blast

text \<open>
  This is the program-level counterpart of Lemma 2, and it is the direction
  Theorem 1 consumes: acyclicity of the relation WEAKEST computes implies
  acyclicity of the relation PwT's semantics induces, so no program-level
  thin-air dependency can hide in the gap between them.
\<close>

section \<open>The converse fails: composition does not preserve non-constancy\<close>

subsection \<open>Cancellation\<close>

definition cancelP :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "cancelP x t y = [(t, EBin OAdd (EReg x) (ENum 1)), (y, EBin OSub (EReg t) (EReg x))]"

lemma cancelP_run:
  assumes "x \<noteq> t"
  shows "run (cancelP x t y) s y = 1"
  using assms by (simp add: cancelP_def)

lemma cancelP_no_prog_dep:
  assumes "x \<noteq> t"
  shows "\<not> prog_dep (cancelP x t y) x y"
  by (simp add: prog_dep_def cancelP_run[OF assms])

lemma cancelP_sites_chain:
  assumes xt: "x \<noteq> t" and ty: "t \<noteq> y"
  shows "(x, y) \<in> trancl (sites (cancelP x t y))"
proof -
  have e1: "(x, t) \<in> sites (cancelP x t y)"
    using real_add_one by (auto simp: cancelP_def sites_def)
  have "non_constant (EBin OSub (EReg t) (EReg x)) t"
    by (rule inj_in_imp_non_constant, rule inj_in_sub_reg) (use xt in simp)
  then have e2: "(t, y) \<in> sites (cancelP x t y)"
    by (auto simp: cancelP_def sites_def)
  from e1 e2 show ?thesis by (auto intro: trancl_into_trancl r_into_trancl)
qed

text \<open>
  Both steps are genuinely value-carrying and the site-wise relation therefore
  reports a dependency path from x to y, yet the composite program computes the
  constant 1. The site-wise criterion is strictly coarser than the program-level
  one, and the reason is elementary: non-constancy is not closed under
  composition.
\<close>

theorem sitewise_not_complete:
  "\<exists>P x y. (x, y) \<in> trancl (sites P) \<and> \<not> prog_dep P x y"
proof -
  have d1: "(''x'' :: reg) \<noteq> ''t''" by simp
  have d2: "(''t'' :: reg) \<noteq> ''y''" by simp
  have "(''x'', ''y'') \<in> trancl (sites (cancelP ''x'' ''t'' ''y''))"
    by (rule cancelP_sites_chain[OF d1 d2])
  moreover have "\<not> prog_dep (cancelP ''x'' ''t'' ''y'') ''x'' ''y''"
    by (rule cancelP_no_prog_dep[OF d1])
  ultimately show ?thesis by blast
qed

subsection \<open>Masking, in a single-read chain\<close>

text \<open>
  Cancellation could be dismissed as an artefact of the second instruction
  reading x twice over. It cannot. The masking program below has each
  instruction reading exactly ONE register, so the site-wise relation is a
  simple chain x -> t -> y, and it still over-approximates: bit 0 survives the
  first step and is discarded by the second.

  This is the same class of expression that separates PwT's exact R4b from
  non-constancy per-site (PwT_R4b.masking_witness). Masking is the single
  phenomenon behind both the per-site and the program-level divergence.
\<close>

definition maskP :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "maskP x t y = [(t, EBin OAnd (EReg x) (ENum 1)), (y, EBin OAnd (EReg t) (ENum 2))]"

lemma maskP_single_read:
  "\<forall>(z, e) \<in> set (maskP x t y). \<exists>r. regs e = {r}"
  by (auto simp: maskP_def)

lemma maskP_run: "run (maskP x t y) s y = 0"
  by (simp add: maskP_def and.assoc)

lemma maskP_no_prog_dep: "\<not> prog_dep (maskP x t y) x y"
  by (simp add: prog_dep_def maskP_run)

lemma maskP_sites_chain:
  assumes xt: "x \<noteq> t" and ty: "t \<noteq> y"
  shows "(x, y) \<in> trancl (sites (maskP x t y))"
proof -
  have e1: "(x, t) \<in> sites (maskP x t y)"
    using real_mask_one by (auto simp: maskP_def sites_def)
  have e2: "(t, y) \<in> sites (maskP x t y)"
    using real_mask_two by (auto simp: maskP_def sites_def)
  from e1 e2 show ?thesis by (auto intro: trancl_into_trancl r_into_trancl)
qed

theorem sitewise_not_complete_single_read:
  "\<exists>P x y. (\<forall>(z, e) \<in> set P. \<exists>r. regs e = {r})
           \<and> (x, y) \<in> trancl (sites P) \<and> \<not> prog_dep P x y"
proof -
  have d1: "(''x'' :: reg) \<noteq> ''t''" by simp
  have d2: "(''t'' :: reg) \<noteq> ''y''" by simp
  have "(''x'', ''y'') \<in> trancl (sites (maskP ''x'' ''t'' ''y''))"
    by (rule maskP_sites_chain[OF d1 d2])
  moreover have "\<not> prog_dep (maskP ''x'' ''t'' ''y'') ''x'' ''y''"
    by (rule maskP_no_prog_dep)
  moreover have "\<forall>(z, e) \<in> set (maskP ''x'' ''t'' ''y''). \<exists>r. regs e = {r}"
    by (rule maskP_single_read)
  ultimately show ?thesis by blast
qed

section \<open>A fragment on which the lifting is exact\<close>

text \<open>
  Injectivity in the carried register is what both counterexamples lack, and it
  is exactly what makes composition safe: a composition of injections is an
  injection, and an injection on a type with at least two elements is
  non-constant.

  The fragment: a pipeline that repeatedly rewrites one register, each step
  injective in it. Every real (as opposed to fake) dependency pattern built from
  + - ^ ~ and unary minus lies in it; the AND patterns do not, which is the same
  dividing line PwT_R4b draws per-site.
\<close>

definition pipeline :: "reg \<Rightarrow> expr list \<Rightarrow> prog" where
  "pipeline r es = map (Pair r) es"

definition prog_inj :: "prog \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> bool" where
  "prog_inj P x y \<longleftrightarrow>
     (\<forall>s v1 v2. run P (s(x := v1)) y = run P (s(x := v2)) y \<longrightarrow> v1 = v2)"

lemma pipeline_Nil [simp]: "pipeline r [] = []"
  by (simp add: pipeline_def)

lemma pipeline_Cons [simp]: "pipeline r (e # es) = (r, e) # pipeline r es"
  by (simp add: pipeline_def)

lemma pipeline_inj:
  assumes "\<forall>e \<in> set es. inj_in e r"
  shows "prog_inj (pipeline r es) r r"
  using assms
proof (induction es)
  case Nil
  show ?case by (simp add: prog_inj_def)
next
  case (Cons e es)
  have IH: "prog_inj (pipeline r es) r r" using Cons by simp
  show ?case
    unfolding prog_inj_def
  proof (intro allI impI)
    fix s :: env and v1 v2 :: word32
    assume eq: "run (pipeline r (e # es)) (s(r := v1)) r
                = run (pipeline r (e # es)) (s(r := v2)) r"
    have "run (pipeline r (e # es)) (s(r := v)) r
          = run (pipeline r es) (s(r := eval e (s(r := v)))) r" for v
      by simp
    with eq have "run (pipeline r es) (s(r := eval e (s(r := v1)))) r
                  = run (pipeline r es) (s(r := eval e (s(r := v2)))) r"
      by simp
    with IH have "eval e (s(r := v1)) = eval e (s(r := v2))"
      unfolding prog_inj_def by blast
    moreover have "inj_in e r" using Cons.prems by simp
    ultimately show "v1 = v2" by (rule_tac inj_inD) auto
  qed
qed

lemma prog_inj_imp_prog_dep:
  assumes "prog_inj P x y"
  shows "prog_dep P x y"
proof -
  have "run P ((\<lambda>_. 0)(x := 0)) y \<noteq> run P ((\<lambda>_. 0)(x := 1)) y"
  proof
    assume "run P ((\<lambda>_. 0)(x := 0)) y = run P ((\<lambda>_. 0)(x := 1)) y"
    with assms have "(0 :: word32) = 1" unfolding prog_inj_def by blast
    then show False by simp
  qed
  then show ?thesis by (auto simp: prog_dep_def)
qed

lemma pipeline_sites:
  assumes "es \<noteq> []" and "\<forall>e \<in> set es. inj_in e r"
  shows "(r, r) \<in> sites (pipeline r es)"
proof -
  from assms(1) obtain e es' where "es = e # es'" by (cases es) auto
  moreover from this assms(2) have "non_constant e r"
    by (simp add: inj_in_imp_non_constant)
  ultimately show ?thesis by (auto simp: pipeline_def sites_def)
qed

theorem pipeline_exact:
  assumes "es \<noteq> []" and "\<forall>e \<in> set es. inj_in e r"
  shows "prog_dep (pipeline r es) r r \<and> (r, r) \<in> trancl (sites (pipeline r es))"
proof
  show "prog_dep (pipeline r es) r r"
    by (rule prog_inj_imp_prog_dep) (rule pipeline_inj[OF assms(2)])
next
  show "(r, r) \<in> trancl (sites (pipeline r es))"
    using pipeline_sites[OF assms] by blast
qed

text \<open>
  Worked instance: the real counterpart of the corpus's canonical fake
  dependency. Every step is an XOR with an immediate, hence a bijection, hence
  the whole pipeline carries the dependency -- and the site-wise relation agrees.
\<close>

lemma xor_pipeline_exact:
  "prog_dep (pipeline r [EBin OXor (EReg r) (ENum 1), EBin OXor (EReg r) (ENum 2)]) r r
   \<and> (r, r) \<in> trancl
       (sites (pipeline r [EBin OXor (EReg r) (ENum 1), EBin OXor (EReg r) (ENum 2)]))"
  by (rule pipeline_exact) (auto simp: inj_in_xor_const)

end
