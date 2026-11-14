(*  Title:   PwT_Versioned.thy
    FSEN 2027 revision.

    Replaces the over-stated Prop. 1(c). The chained closure that an
    instruction-local analysis computes is version-aware: an assignment kills the
    previous value of its target. We define it (vdep), and prove:

      vdep_sound          prog_dep P x y ==> x : vdep P y
      vdep_le_sites       vdep P y is contained in the flow-insensitive sites^* closure
      vdep_exact_sri      on single-read injective programs (every step reads exactly
                          one register and is injective in it):
                          prog_dep P x y <-> x : vdep P y
      sri_needed          per-register injectivity of every step is NOT enough:
                          r := r+q; r := r-q  (each step injective in r and in q)
                          has q : vdep P r but no dependency (the cancellation witness)
      sites_not_exact_sri the flow-insensitive sites^* closure is not exact even on
                          single-read injective programs (overwrite witness)

    No axioms, no sorry, no oracles beyond the HOL kernel.
*)
theory PwT_Versioned
  imports WEV_SMT_PwT.PwT_Program
begin

fun vdep :: "prog \<Rightarrow> reg \<Rightarrow> reg set" where
  "vdep [] y = {y}"
| "vdep ((z, e) # Q) y =
     (\<Union>w \<in> vdep Q y. if w = z then {r. non_constant e r} else {w})"

section \<open>Soundness\<close>

lemma eval_eq_if_agree_on_nc:
  assumes "\<And>r. non_constant e r \<Longrightarrow> s1 r = s2 r"
  shows "eval e s1 = eval e s2"
proof (rule ccontr)
  assume "eval e s1 \<noteq> eval e s2"
  then obtain x where "x \<in> {r. s1 r \<noteq> s2 r}" and "non_constant e x"
    using diff_witness[of "{r. s1 r \<noteq> s2 r}" s1 s2 e] by blast
  with assms show False by auto
qed

lemma vdep_agree:
  assumes "\<And>w. w \<in> vdep P y \<Longrightarrow> s1 w = s2 w"
  shows "run P s1 y = run P s2 y"
  using assms
proof (induction P arbitrary: s1 s2)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  let ?t1 = "s1(z := eval e s1)" and ?t2 = "s2(z := eval e s2)"
  have "?t1 w = ?t2 w" if w: "w \<in> vdep Q y" for w
  proof (cases "w = z")
    case True
    have "eval e s1 = eval e s2"
    proof (rule eval_eq_if_agree_on_nc)
      fix r assume "non_constant e r"
      with w True have "r \<in> vdep ((z, e) # Q) y" by auto
      with Cons.prems i show "s1 r = s2 r" by blast
    qed
    with True show ?thesis by simp
  next
    case False
    with w have "w \<in> vdep ((z, e) # Q) y" by force
    with Cons.prems i False show ?thesis by auto
  qed
  then have "run Q ?t1 y = run Q ?t2 y" by (rule Cons.IH)
  with i show ?case by simp
qed

theorem vdep_sound:
  assumes "prog_dep P x y"
  shows "x \<in> vdep P y"
proof (rule ccontr)
  assume nx: "x \<notin> vdep P y"
  from assms obtain s v1 v2 where d: "run P (s(x := v1)) y \<noteq> run P (s(x := v2)) y"
    unfolding prog_dep_def by blast
  have "run P (s(x := v1)) y = run P (s(x := v2)) y"
    by (rule vdep_agree) (use nx in auto)
  with d show False by simp
qed

section \<open>vdep refines the flow-insensitive closure\<close>

lemma sites_Cons_sup: "sites Q \<subseteq> sites ((z, e) # Q)"
  by (rule sites_mono_Cons)

theorem vdep_le_sites:
  assumes "x \<in> vdep P y"
  shows "(x, y) \<in> rtrancl (sites P)"
  using assms
proof (induction P arbitrary: x)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  from Cons.prems i obtain w where w: "w \<in> vdep Q y"
    and c: "(w = z \<and> non_constant e x) \<or> (w \<noteq> z \<and> x = w)"
    by (auto split: if_splits)
  have "(w, y) \<in> rtrancl (sites Q)" using Cons.IH w by blast
  then have wy: "(w, y) \<in> rtrancl (sites ((z, e) # Q))"
    using rtrancl_mono[OF sites_Cons_sup] by blast
  from c show ?case
  proof
    assume "w = z \<and> non_constant e x"
    then have "(x, w) \<in> sites ((z, e) # Q)" by (auto simp: sites_Cons)
    with wy i show ?thesis by (meson converse_rtrancl_into_rtrancl)
  next
    assume "w \<noteq> z \<and> x = w"
    with wy i show ?thesis by simp
  qed
qed

section \<open>Exactness on single-read injective programs\<close>

definition sri_step :: "instr \<Rightarrow> bool" where
  "sri_step i \<longleftrightarrow> (\<exists>r. regs (snd i) \<subseteq> {r} \<and> inj_in (snd i) r)"

definition sri :: "prog \<Rightarrow> bool" where
  "sri P \<longleftrightarrow> (\<forall>i \<in> set P. sri_step i)"

lemma sri_nc_iff:
  assumes "regs e \<subseteq> {r}" and "inj_in e r"
  shows "non_constant e r' \<longleftrightarrow> r' = r"
  using assms inj_in_imp_non_constant non_constant_imp_read by blast

lemma sri_eval:
  assumes "regs e \<subseteq> {r}"
  shows "eval e s = eval e ((\<lambda>_. 0)(r := s r))"
  by (rule eval_coincidence) (use assms in auto)

lemma sri_shape:
  assumes "sri P"
  shows "\<exists>x h. vdep P y = {x} \<and> inj h \<and> (\<forall>s. run P s y = h (s x))"
  using assms
proof (induction P arbitrary: y)
  case Nil
  show ?case by (rule exI[of _ y], rule exI[of _ id]) simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  from Cons.prems have sQ: "sri Q" by (simp add: sri_def)
  from Cons.prems i obtain r where re: "regs e \<subseteq> {r}" and ie: "inj_in e r"
    by (auto simp: sri_def sri_step_def)
  from Cons.IH[OF sQ] obtain w h where vQ: "vdep Q y = {w}" and ih: "inj h"
    and rQ: "\<forall>s. run Q s y = h (s w)" by blast
  show ?case
  proof (cases "w = z")
    case True
    let ?g = "\<lambda>v. eval e ((\<lambda>_. 0)(r := v))"
    have ig: "inj ?g" using ie by (auto intro: injI simp: inj_in_def)
    have "vdep ((z, e) # Q) y = {r}"
      using vQ True sri_nc_iff[OF re ie] by auto
    moreover have "inj (h \<circ> ?g)" using ih ig by (rule inj_compose)
    moreover have "\<forall>s. run ((z, e) # Q) s y = (h \<circ> ?g) (s r)"
      using rQ True sri_eval[OF re] by simp
    ultimately show ?thesis using i by blast
  next
    case False
    have "vdep ((z, e) # Q) y = {w}" using vQ False by auto
    moreover have "\<forall>s. run ((z, e) # Q) s y = h (s w)" using rQ False by simp
    ultimately show ?thesis using i ih by blast
  qed
qed

theorem vdep_exact_sri:
  assumes "sri P"
  shows "prog_dep P x y \<longleftrightarrow> x \<in> vdep P y"
proof
  assume "prog_dep P x y" then show "x \<in> vdep P y" by (rule vdep_sound)
next
  assume x: "x \<in> vdep P y"
  from sri_shape[OF assms] obtain x' h where v: "vdep P y = {x'}" and ih: "inj h"
    and r: "\<forall>s. run P s y = h (s x')" by blast
  from x v have xx: "x = x'" by simp
  have "run P ((\<lambda>_. 0)(x := 0)) y \<noteq> run P ((\<lambda>_. 0)(x := 1)) y"
    using r xx ih by (simp add: inj_eq)
  then show "prog_dep P x y" unfolding prog_dep_def by blast
qed

section \<open>Witnesses: the hypotheses are needed\<close>

definition cancel2 :: "reg \<Rightarrow> reg \<Rightarrow> prog" where
  "cancel2 r q = [(r, EBin OAdd (EReg r) (EReg q)), (r, EBin OSub (EReg r) (EReg q))]"

lemma inj_in_add_regs:
  assumes "r \<noteq> q" shows "inj_in (EBin OAdd (EReg r) (EReg q)) r" "inj_in (EBin OAdd (EReg r) (EReg q)) q"
  using assms by (auto simp: inj_in_def)

lemma inj_in_sub_regs:
  assumes "r \<noteq> q" shows "inj_in (EBin OSub (EReg r) (EReg q)) r" "inj_in (EBin OSub (EReg r) (EReg q)) q"
  using assms by (auto simp: inj_in_def)

theorem sri_needed:
  assumes "r \<noteq> q"
  shows "(\<forall>i \<in> set (cancel2 r q). inj_in (snd i) r \<and> inj_in (snd i) q)"
    and "q \<in> vdep (cancel2 r q) r"
    and "\<not> prog_dep (cancel2 r q) q r"
    and "\<not> sri (cancel2 r q)"
proof -
  show "\<forall>i \<in> set (cancel2 r q). inj_in (snd i) r \<and> inj_in (snd i) q"
    using inj_in_add_regs[OF assms] inj_in_sub_regs[OF assms] by (simp add: cancel2_def)
  have nq: "non_constant (EBin OSub (EReg r) (EReg q)) q"
    by (rule inj_in_imp_non_constant) (rule inj_in_sub_regs(2)[OF assms])
  have nr: "non_constant (EBin OAdd (EReg r) (EReg q)) q"
    by (rule inj_in_imp_non_constant) (rule inj_in_add_regs(2)[OF assms])
  show "q \<in> vdep (cancel2 r q) r"
    using nq assms by (auto simp: cancel2_def)
  show "\<not> prog_dep (cancel2 r q) q r"
    using assms by (simp add: prog_dep_def cancel2_def)
  show "\<not> sri (cancel2 r q)"
    using assms by (auto simp: sri_def sri_step_def cancel2_def)
qed

definition overwrite2 :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "overwrite2 x w y = [(y, EReg x), (y, EReg w)]"

theorem sites_not_exact_sri:
  assumes "x \<noteq> y" and "w \<noteq> y" and "x \<noteq> w"
  shows "sri (overwrite2 x w y)"
    and "(x, y) \<in> rtrancl (sites (overwrite2 x w y))"
    and "\<not> prog_dep (overwrite2 x w y) x y"
    and "vdep (overwrite2 x w y) y = {w}"
proof -
  have ix: "inj_in (EReg x) x" and iw: "inj_in (EReg w) w" by (auto simp: inj_in_def)
  show "sri (overwrite2 x w y)"
    using ix iw by (auto simp: sri_def sri_step_def overwrite2_def)
  have "(x, y) \<in> sites (overwrite2 x w y)"
    using real_identity by (auto simp: sites_def overwrite2_def)
  then show "(x, y) \<in> rtrancl (sites (overwrite2 x w y))" by blast
  show "\<not> prog_dep (overwrite2 x w y) x y"
    using assms by (simp add: prog_dep_def overwrite2_def)
  have "{r. non_constant (EReg w) r} = {w}"
    using real_identity non_constant_imp_read by fastforce
  then show "vdep (overwrite2 x w y) y = {w}"
    using assms by (auto simp: overwrite2_def)
qed

end
