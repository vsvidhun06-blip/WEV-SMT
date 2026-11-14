(*  Title:   PwT_Remarks.thy
    FSEN 2027 #41, repair pass (2026-11-06).

    Mechanises four steps that the paper previously stated in prose next to
    mechanised theorems:

      affine_capacity_card   Thm 3(c): an exact instruction code takes exactly 2^64
                             values on the family {a*x + b} (the cardinality step
                             after the injectivity of affine_capacity_inj).
      aff_capacity_classes   Thm 6(c): coef is an exact code over affine programs and
                             takes exactly 2^32 values on {a*x + b}; every exact code
                             over affine programs takes at least 2^32 values there.
      vdep_exact_single      every one-instruction program is exact for vdep, hence
      sri_not_necessary      SRI is not necessary: [(y, x + w)] is not SRI, yet vdep is
                             exact on it.
      vdep_masking           the masking program t := x & 1; y := t & 2 reads one
                             register per step, x : vdep P y, yet y does not depend on x
                             (the version-aware closure itself, not only sites^+).

    No axioms, no sorry, no oracles beyond the HOL kernel.
*)
theory PwT_Remarks
  imports PwT_Affine WEV_SMT_PwT.PwT_Capacity
begin

section \<open>Cardinalities\<close>

lemma card_word32: "card (UNIV :: word32 set) = 2 ^ 32"
  by (simp add: card_word)

lemma card_word32_pair: "card (UNIV :: (word32 \<times> word32) set) = 2 ^ 64"
proof -
  have "card (UNIV :: (word32 \<times> word32) set) = card (UNIV :: word32 set) * card (UNIV :: word32 set)"
    using card_cartesian_product[of "UNIV :: word32 set" "UNIV :: word32 set"] by simp
  then show ?thesis by (simp add: card_word32)
qed

corollary affine_capacity_card:
  assumes "PwT_Capacity.exact_code code"
  shows "card (range (\<lambda>ab. code (PwT_Capacity.affine x (fst ab) (snd ab)))) = 2 ^ 64"
proof -
  have "card (range (\<lambda>ab. code (PwT_Capacity.affine x (fst ab) (snd ab))))
        = card (UNIV :: (word32 \<times> word32) set)"
    by (rule card_image) (rule PwT_Capacity.affine_capacity_inj[OF assms])
  then show ?thesis by (simp only: card_word32_pair)
qed

definition coef_x :: "reg \<Rightarrow> word32 \<Rightarrow> reg \<Rightarrow> word32" where
  "coef_x x a = (\<lambda>r. if r = x then a else 0)"

lemma inj_coef_x: "inj (coef_x x)"
proof (rule injI)
  fix a b assume "coef_x x a = coef_x x b"
  then have "coef_x x a x = coef_x x b x" by (rule arg_cong[where f = "\<lambda>g. g x"])
  then show "a = b" by (simp add: coef_x_def)
qed

lemma coef_axb_fun: "coef (axb a x b) = coef_x x a"
  by (rule ext) (simp add: coef_axb coef_x_def)

theorem aff_capacity_classes:
  shows "aff_exact coef"
    and "card (range (\<lambda>ab. coef (axb (fst ab) x (snd ab)))) = 2 ^ 32"
    and "aff_exact c \<Longrightarrow> 2 ^ 32 \<le> card (range (\<lambda>ab. c (axb (fst ab) x (snd ab))))"
proof -
  show "aff_exact coef" by (subst aff_exact_iff) blast
  have eq: "(\<lambda>ab. coef (axb (fst ab) x (snd ab))) = coef_x x \<circ> fst"
    by (rule ext) (simp add: coef_axb_fun)
  have "range (\<lambda>ab. coef (axb (fst ab) x (snd ab))) = range (coef_x x)"
    by (simp only: eq image_comp[symmetric] range_fst)
  then have "card (range (\<lambda>ab. coef (axb (fst ab) x (snd ab)))) = card (range (coef_x x))"
    by (rule arg_cong)
  also have "\<dots> = card (UNIV :: word32 set)" by (rule card_image) (rule inj_coef_x)
  finally show "card (range (\<lambda>ab. coef (axb (fst ab) x (snd ab)))) = 2 ^ 32"
    by (simp only: card_word32)
  assume ex: "aff_exact c"
  have inj: "inj (\<lambda>a. c (axb a x 0))"
  proof (rule injI)
    fix a1 a2 assume "c (axb a1 x 0) = c (axb a2 x 0)"
    then show "a1 = a2" by (rule aff_capacity(3)[OF ex])
  qed
  have sub: "range (\<lambda>a. c (axb a x 0)) \<subseteq> range (\<lambda>ab. c (axb (fst ab) x (snd ab)))"
  proof
    fix z assume "z \<in> range (\<lambda>a. c (axb a x 0))"
    then obtain a where z: "z = c (axb a x 0)" by blast
    have "z = (\<lambda>ab. c (axb (fst ab) x (snd ab))) (a, 0)" by (simp add: z)
    then show "z \<in> range (\<lambda>ab. c (axb (fst ab) x (snd ab)))" by blast
  qed
  have fin: "finite (range (\<lambda>ab. c (axb (fst ab) x (snd ab))))" by (rule finite_imageI) simp
  have "card (range (\<lambda>a. c (axb a x 0))) = card (UNIV :: word32 set)" by (rule card_image) (rule inj)
  then have "card (range (\<lambda>a. c (axb a x 0))) = 2 ^ 32" by (simp only: card_word32)
  with card_mono[OF fin sub] show "2 ^ 32 \<le> card (range (\<lambda>ab. c (axb (fst ab) x (snd ab))))"
    by simp
qed

section \<open>The version-aware closure on one instruction; SRI is not necessary\<close>

lemma vdep_single: "vdep [(y, e)] y = {r. non_constant e r}"
  by simp

theorem vdep_exact_single: "prog_dep [(y, e)] r y \<longleftrightarrow> r \<in> vdep [(y, e)] y"
  by (simp add: prog_dep_def non_constant_def)

theorem sri_not_necessary:
  assumes "x \<noteq> w"
  shows "\<not> sri [(y, EBin OAdd (EReg x) (EReg w))]"
    and "\<forall>r. prog_dep [(y, EBin OAdd (EReg x) (EReg w))] r y
             \<longleftrightarrow> r \<in> vdep [(y, EBin OAdd (EReg x) (EReg w))] y"
    and "vdep [(y, EBin OAdd (EReg x) (EReg w))] y = {x, w}"
proof -
  show "\<not> sri [(y, EBin OAdd (EReg x) (EReg w))]"
    using assms by (auto simp: sri_def sri_step_def)
  show "\<forall>r. prog_dep [(y, EBin OAdd (EReg x) (EReg w))] r y
             \<longleftrightarrow> r \<in> vdep [(y, EBin OAdd (EReg x) (EReg w))] y"
    using vdep_exact_single by blast
  have nx: "non_constant (EBin OAdd (EReg x) (EReg w)) x"
    by (rule inj_in_imp_non_constant) (rule inj_in_add_regs(1)[OF assms])
  have nw: "non_constant (EBin OAdd (EReg x) (EReg w)) w"
    by (rule inj_in_imp_non_constant) (rule inj_in_add_regs(2)[OF assms])
  have rd: "non_constant (EBin OAdd (EReg x) (EReg w)) r \<Longrightarrow> r \<in> {x, w}" for r
    using non_constant_imp_read[of "EBin OAdd (EReg x) (EReg w)" r] by auto
  show "vdep [(y, EBin OAdd (EReg x) (EReg w))] y = {x, w}"
    using nx nw rd by (auto simp only: vdep_single mem_Collect_eq)
qed

section \<open>Masking defeats the version-aware closure, not only sites^+\<close>

theorem vdep_masking:
  assumes "x \<noteq> t" and "t \<noteq> y"
  shows "\<forall>(z, e) \<in> set (maskP x t y). \<exists>r. regs e = {r}"
    and "x \<in> vdep (maskP x t y) y"
    and "\<not> prog_dep (maskP x t y) x y"
proof -
  show "\<forall>(z, e) \<in> set (maskP x t y). \<exists>r. regs e = {r}" by (rule maskP_single_read)
  have t: "t \<in> vdep [(y, EBin OAnd (EReg t) (ENum 2))] y"
    using real_mask_two by simp
  show "x \<in> vdep (maskP x t y) y"
    using t real_mask_one by (force simp: maskP_def)
  show "\<not> prog_dep (maskP x t y) x y" by (rule maskP_no_prog_dep)
qed

end
