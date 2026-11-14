(*  Title:   PwT_TaintLinear.thy
    Real-code significance investigation (FSEN 2027 #41), theory track, 2026-11-11.
    New theory; it imports copies of the base and add-on theories and modifies neither.

    Exactness of the version-aware closure vdep beyond single-read injective (SRI) programs.

    A forward pass from a source register x gives every register a taint class that says how its
    current value depends on the initial value of x when all other initial registers are held fixed:
      None  not at all            TI  injectively (on 32-bit words: bijectively)
      TN    non-constantly        TU  unknown
    An assignment z := e reading the tainted registers treads T e is classified
      None  if it reads no tainted register (side inputs only: the earlier value of z is killed);
      TI    if it reads exactly one tainted register r, r is TI, and e is injective in r
            whatever the other registers hold (inj_in);
      TN    if instead e is uniformly non-constant in r (unc: non-constant whatever the other
            registers hold);
      TU    otherwise.

    Results (no axioms, no sorry, no oracles; TL_Check checks the oracle set):
      taint_sound        TI or TN at the end give prog_dep; None gives no prog_dep
      vdep_imp_tainted   x : vdep P y forces y to end tainted
      tl_exact           unless y ends TU:  prog_dep P x y <-> x : vdep P y
      tl_frag_exact      if the pass never produces TU, vdep is exact for every target
      sri_exact_via_tl   SRI programs never produce TU (re-derives vdep_exact_sri)
      Each hypothesis is needed; each witness meets the other two and vdep is inexact on it:
        tl_needs_linear   cancel2:  r := r+q; r := r-q   two tainted reads       (correlation)
        tl_needs_full     maskP:    t := x&1; y := t&2   unc step on a TN input  (range restriction)
        tl_needs_uniform  annP:     u := 0;  y := x*u    nc but not unc in x     (side-input annihilation)
      Sufficient syntactic conditions: inj_in_add_side, inj_in_sub_side, inj_in_sub_side',
      inj_in_xor_side, inj_in_un, inj_in_imp_unc, unc_and_num, unc_mul_num, unc_eq_side.
*)
theory PwT_TaintLinear
  imports WEV_SMT_Rev.PwT_Versioned WEV_SMT_Rev.PwT_Remarks
begin

section \<open>Taint classes and the forward pass\<close>

datatype tcls = TI | TN | TU

type_synonym tmap = "reg \<Rightarrow> tcls option"

definition unc :: "expr \<Rightarrow> reg \<Rightarrow> bool" where
  "unc e r \<longleftrightarrow> (\<forall>s. \<exists>v1 v2. eval e (s(r := v1)) \<noteq> eval e (s(r := v2)))"

definition treads :: "tmap \<Rightarrow> expr \<Rightarrow> reg set" where
  "treads T e = {r \<in> regs e. T r \<noteq> None}"

definition step_cls :: "tmap \<Rightarrow> expr \<Rightarrow> tcls option" where
  "step_cls T e =
     (if treads T e = {} then None
      else if (\<exists>r. treads T e = {r} \<and> T r = Some TI \<and> inj_in e r) then Some TI
      else if (\<exists>r. treads T e = {r} \<and> T r = Some TI \<and> unc e r) then Some TN
      else Some TU)"

fun taint :: "tmap \<Rightarrow> prog \<Rightarrow> tmap" where
  "taint T [] = T"
| "taint T ((z, e) # Q) = taint (T(z := step_cls T e)) Q"

definition src :: "reg \<Rightarrow> tmap" where
  "src x = (\<lambda>_. None)(x := Some TI)"

lemma step_cls_None: "step_cls T e = None \<longleftrightarrow> treads T e = {}"
  by (simp add: step_cls_def)

lemma step_cls_TI:
  "step_cls T e = Some TI \<Longrightarrow> \<exists>r. treads T e = {r} \<and> T r = Some TI \<and> inj_in e r"
  by (auto simp: step_cls_def split: if_splits)

lemma step_cls_TN:
  "step_cls T e = Some TN \<Longrightarrow> \<exists>r. treads T e = {r} \<and> T r = Some TI \<and> unc e r"
  by (auto simp: step_cls_def split: if_splits)

section \<open>The family invariant\<close>

text \<open>F v is the state reached when the initial x is v (all other initial registers fixed).\<close>

definition good :: "tmap \<Rightarrow> (word32 \<Rightarrow> env) \<Rightarrow> bool" where
  "good T F \<longleftrightarrow> (\<forall>w. (T w = None \<longrightarrow> (\<forall>v1 v2. F v1 w = F v2 w)) \<and>
                   (T w = Some TI \<longrightarrow> inj (\<lambda>v. F v w)) \<and>
                   (T w = Some TN \<longrightarrow> (\<exists>v1 v2. F v1 w \<noteq> F v2 w)))"

lemma goodD:
  assumes "good T F"
  shows "T w = None \<Longrightarrow> F v1 w = F v2 w"
    and "T w = Some TI \<Longrightarrow> inj (\<lambda>v. F v w)"
    and "T w = Some TN \<Longrightarrow> \<exists>v1 v2. F v1 w \<noteq> F v2 w"
  using assms unfolding good_def by blast+

lemma eval_untainted:
  assumes g: "good T F" and t: "treads T e = {}"
  shows "eval e (F v1) = eval e (F v2)"
proof (rule eval_coincidence)
  fix q assume q: "q \<in> regs e"
  have "q \<notin> treads T e" using t by simp
  with q have "T q = None" by (simp add: treads_def)
  then show "F v1 q = F v2 q" by (rule goodD(1)[OF g])
qed

lemma eval_side:
  assumes g: "good T F" and t: "treads T e = {r}"
  shows "eval e (F v) = eval e ((F u)(r := F v r))"
proof (rule eval_coincidence)
  fix q assume q: "q \<in> regs e"
  show "F v q = ((F u)(r := F v r)) q"
  proof (cases "q = r")
    case True
    then show ?thesis by simp
  next
    case False
    have "q \<notin> treads T e" using t False by simp
    with q have "T q = None" by (simp add: treads_def)
    then have "F v q = F u q" by (rule goodD(1)[OF g])
    with False show ?thesis by simp
  qed
qed

lemma good_upd:
  assumes g: "good T F"
    and n: "c = None \<Longrightarrow> (\<forall>v1 v2. G v1 = G v2)"
    and i: "c = Some TI \<Longrightarrow> inj G"
    and m: "c = Some TN \<Longrightarrow> (\<exists>v1 v2. G v1 \<noteq> G v2)"
  shows "good (T(z := c)) (\<lambda>v. (F v)(z := G v))"
  unfolding good_def
proof (intro allI)
  fix w
  show "((T(z := c)) w = None \<longrightarrow> (\<forall>v1 v2. ((F v1)(z := G v1)) w = ((F v2)(z := G v2)) w)) \<and>
        ((T(z := c)) w = Some TI \<longrightarrow> inj (\<lambda>v. ((F v)(z := G v)) w)) \<and>
        ((T(z := c)) w = Some TN \<longrightarrow> (\<exists>v1 v2. ((F v1)(z := G v1)) w \<noteq> ((F v2)(z := G v2)) w))"
  proof (cases "w = z")
    case True
    then show ?thesis using n i m by simp
  next
    case False
    have gw: "(T w = None \<longrightarrow> (\<forall>v1 v2. F v1 w = F v2 w)) \<and> (T w = Some TI \<longrightarrow> inj (\<lambda>v. F v w)) \<and>
              (T w = Some TN \<longrightarrow> (\<exists>v1 v2. F v1 w \<noteq> F v2 w))"
      using g unfolding good_def by blast
    with False show ?thesis by simp
  qed
qed

lemma good_step:
  assumes g: "good T F"
  shows "good (T(z := step_cls T e)) (\<lambda>v. (F v)(z := eval e (F v)))"
proof (rule good_upd[OF g])
  assume "step_cls T e = None"
  then have t: "treads T e = {}" by (simp add: step_cls_None)
  show "\<forall>v1 v2. eval e (F v1) = eval e (F v2)"
    using eval_untainted[OF g t] by blast
next
  assume "step_cls T e = Some TI"
  then obtain r where t: "treads T e = {r}" and tr: "T r = Some TI" and ie: "inj_in e r"
    by (blast dest: step_cls_TI)
  have ir: "inj (\<lambda>v. F v r)" by (rule goodD(2)[OF g tr])
  show "inj (\<lambda>v. eval e (F v))"
  proof (rule injI)
    fix v1 v2 assume h: "eval e (F v1) = eval e (F v2)"
    have "eval e ((F v1)(r := F v1 r)) = eval e (F v1)" by simp
    also have "\<dots> = eval e (F v2)" by (rule h)
    also have "\<dots> = eval e ((F v1)(r := F v2 r))" by (rule eval_side[OF g t])
    finally have "eval e ((F v1)(r := F v1 r)) = eval e ((F v1)(r := F v2 r))" .
    then have "F v1 r = F v2 r" by (rule inj_inD[OF ie])
    with ir show "v1 = v2" by (rule injD)
  qed
next
  assume "step_cls T e = Some TN"
  then obtain r where t: "treads T e = {r}" and tr: "T r = Some TI" and ue: "unc e r"
    by (blast dest: step_cls_TN)
  have ir: "inj (\<lambda>v. F v r)" by (rule goodD(2)[OF g tr])
  have sr: "surj (\<lambda>v. F v r)"
    by (rule finite_UNIV_inj_surj[OF finite_class.finite_UNIV ir])
  from ue obtain a1 a2 where a: "eval e ((F 0)(r := a1)) \<noteq> eval e ((F 0)(r := a2))"
    unfolding unc_def by blast
  have "a1 \<in> range (\<lambda>v. F v r)" using sr by simp
  then obtain v1 where v1: "a1 = F v1 r" by blast
  have "a2 \<in> range (\<lambda>v. F v r)" using sr by simp
  then obtain v2 where v2: "a2 = F v2 r" by blast
  have e1: "eval e (F v1) = eval e ((F 0)(r := a1))"
    unfolding v1 by (rule eval_side[OF g t])
  have e2: "eval e (F v2) = eval e ((F 0)(r := a2))"
    unfolding v2 by (rule eval_side[OF g t])
  from a e1 e2 have "eval e (F v1) \<noteq> eval e (F v2)" by simp
  then show "\<exists>v1 v2. eval e (F v1) \<noteq> eval e (F v2)" by blast
qed

lemma good_run:
  assumes "good T F"
  shows "good (taint T P) (\<lambda>v. run P (F v))"
  using assms
proof (induction P arbitrary: T F)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  have "good (T(z := step_cls T e)) (\<lambda>v. (F v)(z := eval e (F v)))"
    by (rule good_step[OF Cons.prems])
  from Cons.IH[OF this] show ?case by (simp only: i taint.simps run.simps)
qed

lemma good_src: "good (src x) (\<lambda>v. s(x := v))"
  unfolding good_def src_def by (auto intro: injI)

section \<open>Soundness of the classes\<close>

theorem taint_sound:
  shows "taint (src x) P y = Some TI \<Longrightarrow> prog_dep P x y"
    and "taint (src x) P y = Some TN \<Longrightarrow> prog_dep P x y"
    and "taint (src x) P y = None \<Longrightarrow> \<not> prog_dep P x y"
proof -
  have G: "good (taint (src x) P) (\<lambda>v. run P (s(x := v)))" for s
    by (rule good_run[OF good_src])
  { assume h: "taint (src x) P y = Some TI"
    have inj: "inj (\<lambda>v. run P ((\<lambda>_. 0)(x := v)) y)" by (rule goodD(2)[OF G h])
    have "run P ((\<lambda>_. 0)(x := 0)) y \<noteq> run P ((\<lambda>_. 0)(x := 1)) y"
    proof
      assume "run P ((\<lambda>_. 0)(x := 0)) y = run P ((\<lambda>_. 0)(x := 1)) y"
      with inj have "(0::word32) = 1" by (rule injD)
      then show False by simp
    qed
    then show "prog_dep P x y" unfolding prog_dep_def by blast }
  { assume h: "taint (src x) P y = Some TN"
    from goodD(3)[OF G h] obtain v1 v2 where
      "run P ((\<lambda>_. 0)(x := v1)) y \<noteq> run P ((\<lambda>_. 0)(x := v2)) y" by blast
    then show "prog_dep P x y" unfolding prog_dep_def by blast }
  { assume h: "taint (src x) P y = None"
    have "run P (s(x := v1)) y = run P (s(x := v2)) y" for s v1 v2
      by (rule goodD(1)[OF G h])
    then show "\<not> prog_dep P x y" unfolding prog_dep_def by blast }
qed

section \<open>Completeness of the closure on the fragment\<close>

lemma vdep_imp_tainted:
  assumes "r \<in> vdep P y" and "T r \<noteq> None"
  shows "taint T P y \<noteq> None"
  using assms
proof (induction P arbitrary: T r)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  from Cons.prems(1) have "r \<in> (\<Union>w \<in> vdep Q y. if w = z then {r. non_constant e r} else {w})"
    by (simp only: i vdep.simps)
  then obtain w where w: "w \<in> vdep Q y" and rw: "r \<in> (if w = z then {r. non_constant e r} else {w})"
    by (rule UN_E)
  have tw: "(T(z := step_cls T e)) w \<noteq> None"
  proof (cases "w = z")
    case True
    with rw have "non_constant e r" by simp
    then have "r \<in> regs e" by (rule non_constant_imp_read)
    with Cons.prems(2) have "r \<in> treads T e" by (simp add: treads_def)
    then have "step_cls T e \<noteq> None" by (auto simp: step_cls_None)
    with True show ?thesis by simp
  next
    case False
    with rw have "r = w" by simp
    with False Cons.prems(2) show ?thesis by simp
  qed
  have eq: "taint T (i # Q) = taint (T(z := step_cls T e)) Q" by (simp only: i taint.simps)
  from Cons.IH[where T = "T(z := step_cls T e)", OF w tw] show ?case unfolding eq .
qed

theorem tl_exact:
  assumes "taint (src x) P y \<noteq> Some TU"
  shows "prog_dep P x y \<longleftrightarrow> x \<in> vdep P y"
proof
  assume "prog_dep P x y"
  then show "x \<in> vdep P y" by (rule vdep_sound)
next
  assume v: "x \<in> vdep P y"
  have "src x x \<noteq> None" by (simp add: src_def)
  with v have nn: "taint (src x) P y \<noteq> None" by (rule vdep_imp_tainted)
  show "prog_dep P x y"
  proof (cases "taint (src x) P y")
    case None
    with nn show ?thesis by simp
  next
    case (Some c)
    show ?thesis
    proof (cases c)
      case TI
      with Some show ?thesis by (simp add: taint_sound(1))
    next
      case TN
      with Some show ?thesis by (simp add: taint_sound(2))
    next
      case TU
      with Some assms show ?thesis by simp
    qed
  qed
qed

fun no_tu :: "tmap \<Rightarrow> prog \<Rightarrow> bool" where
  "no_tu T [] = True"
| "no_tu T ((z, e) # Q) \<longleftrightarrow> step_cls T e \<noteq> Some TU \<and> no_tu (T(z := step_cls T e)) Q"

lemma no_tu_taint:
  assumes "no_tu T P" and "\<forall>w. T w \<noteq> Some TU"
  shows "taint T P y \<noteq> Some TU"
  using assms
proof (induction P arbitrary: T)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  from Cons.prems(1) have s: "step_cls T e \<noteq> Some TU" and q: "no_tu (T(z := step_cls T e)) Q"
    by (simp_all add: i)
  have aw: "\<forall>w. (T(z := step_cls T e)) w \<noteq> Some TU"
    using s Cons.prems(2) by (auto simp: fun_upd_apply)
  have eq: "taint T (i # Q) = taint (T(z := step_cls T e)) Q" by (simp only: i taint.simps)
  from Cons.IH[where T = "T(z := step_cls T e)", OF q aw] show ?case unfolding eq .
qed

theorem tl_frag_exact:
  assumes "no_tu (src x) P"
  shows "prog_dep P x y \<longleftrightarrow> x \<in> vdep P y"
proof (rule tl_exact)
  have "\<forall>w. src x w \<noteq> Some TU" by (simp add: src_def)
  with assms show "taint (src x) P y \<noteq> Some TU" by (rule no_tu_taint)
qed

section \<open>SRI is a special case\<close>

lemma sri_no_tu:
  assumes "sri P" and "\<forall>w. T w = None \<or> T w = Some TI"
  shows "no_tu T P"
  using assms
proof (induction P arbitrary: T)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  from Cons.prems(1) obtain r where re: "regs e \<subseteq> {r}" and ie: "inj_in e r"
    by (auto simp: i sri_def sri_step_def)
  have sQ: "sri Q" using Cons.prems(1) by (simp add: sri_def)
  have c: "step_cls T e = None \<or> step_cls T e = Some TI"
  proof (cases "treads T e = {}")
    case True
    then show ?thesis by (simp add: step_cls_None)
  next
    case False
    have sub: "treads T e \<subseteq> {r}" using re by (auto simp: treads_def)
    with False have t: "treads T e = {r}" by (simp add: subset_singleton_iff)
    then have "r \<in> treads T e" by simp
    then have "T r \<noteq> None" by (simp add: treads_def)
    moreover have "T r = None \<or> T r = Some TI" using spec[OF Cons.prems(2), where x = r] .
    ultimately have tr: "T r = Some TI" by auto
    have "\<exists>r'. treads T e = {r'} \<and> T r' = Some TI \<and> inj_in e r'" using t tr ie by blast
    with False have "step_cls T e = Some TI" by (simp add: step_cls_def)
    then show ?thesis by simp
  qed
  then have s: "step_cls T e \<noteq> Some TU" by auto
  have aw: "\<forall>w. (T(z := step_cls T e)) w = None \<or> (T(z := step_cls T e)) w = Some TI"
    using c Cons.prems(2) by (auto simp: fun_upd_apply)
  have ih: "no_tu (T(z := step_cls T e)) Q" by (rule Cons.IH[where T = "T(z := step_cls T e)", OF sQ aw])
  have eq: "no_tu T (i # Q) \<longleftrightarrow> step_cls T e \<noteq> Some TU \<and> no_tu (T(z := step_cls T e)) Q"
    by (simp only: i no_tu.simps)
  from s ih show ?case unfolding eq by blast
qed

corollary sri_exact_via_tl:
  assumes "sri P"
  shows "prog_dep P x y \<longleftrightarrow> x \<in> vdep P y"
proof (rule tl_frag_exact)
  have "\<forall>w. src x w = None \<or> src x w = Some TI" by (simp add: src_def)
  with assms show "no_tu (src x) P" by (rule sri_no_tu)
qed

section \<open>Sufficient syntactic conditions\<close>

lemma eval_upd_unread: "r \<notin> regs e \<Longrightarrow> eval e (s(r := v)) = eval e s"
  by (rule eval_coincidence) auto

lemma inj_in_add_side: "r \<notin> regs e \<Longrightarrow> inj_in (EBin OAdd (EReg r) e) r"
  by (simp add: inj_in_def eval_upd_unread)

lemma inj_in_sub_side: "r \<notin> regs e \<Longrightarrow> inj_in (EBin OSub (EReg r) e) r"
  by (simp add: inj_in_def eval_upd_unread)

lemma inj_in_sub_side': "r \<notin> regs e \<Longrightarrow> inj_in (EBin OSub e (EReg r)) r"
  by (simp add: inj_in_def eval_upd_unread)

lemma xor_cancel_word: "(a::word32) XOR c = b XOR c \<Longrightarrow> a = b"
proof -
  assume h: "a XOR c = b XOR c"
  have "a = (a XOR c) XOR c" by (simp add: xor.assoc)
  also have "\<dots> = (b XOR c) XOR c" by (simp only: h)
  also have "\<dots> = b" by (simp add: xor.assoc)
  finally show "a = b" .
qed

lemma inj_in_xor_side: "r \<notin> regs e \<Longrightarrow> inj_in (EBin OXor (EReg r) e) r"
  unfolding inj_in_def by (auto simp: eval_upd_unread dest: xor_cancel_word)

lemma inj_in_un: "inj_in (EUn u (EReg r)) r"
  by (cases u) (simp_all add: inj_in_def)

lemma inj_in_imp_unc: "inj_in e r \<Longrightarrow> unc e r"
  unfolding unc_def
proof
  fix s assume ie: "inj_in e r"
  have "eval e (s(r := 0)) \<noteq> eval e (s(r := 1))"
  proof
    assume "eval e (s(r := 0)) = eval e (s(r := 1))"
    from inj_inD[OF ie this] show False by simp
  qed
  then show "\<exists>v1 v2. eval e (s(r := v1)) \<noteq> eval e (s(r := v2))" by blast
qed

lemma unc_and_num: "c \<noteq> 0 \<Longrightarrow> unc (EBin OAnd (EReg r) (ENum c)) r"
  unfolding unc_def
proof
  fix s assume c: "c \<noteq> 0"
  have "eval (EBin OAnd (EReg r) (ENum c)) (s(r := 0)) \<noteq> eval (EBin OAnd (EReg r) (ENum c)) (s(r := c))"
    using c by simp
  then show "\<exists>v1 v2. eval (EBin OAnd (EReg r) (ENum c)) (s(r := v1))
                     \<noteq> eval (EBin OAnd (EReg r) (ENum c)) (s(r := v2))" by blast
qed

lemma unc_mul_num: "c \<noteq> 0 \<Longrightarrow> unc (EBin OMul (EReg r) (ENum c)) r"
  unfolding unc_def
proof
  fix s assume c: "c \<noteq> 0"
  have "eval (EBin OMul (EReg r) (ENum c)) (s(r := 0)) \<noteq> eval (EBin OMul (EReg r) (ENum c)) (s(r := 1))"
    using c by simp
  then show "\<exists>v1 v2. eval (EBin OMul (EReg r) (ENum c)) (s(r := v1))
                     \<noteq> eval (EBin OMul (EReg r) (ENum c)) (s(r := v2))" by blast
qed

lemma unc_eq_side: "r \<notin> regs e \<Longrightarrow> unc (ERel REq (EReg r) e) r"
  unfolding unc_def
proof
  fix s assume r: "r \<notin> regs e"
  let ?a = "eval e s"
  have "eval (ERel REq (EReg r) e) (s(r := ?a)) = 1" using r by (simp add: eval_upd_unread)
  moreover have "eval (ERel REq (EReg r) e) (s(r := ?a + 1)) = 0" using r by (simp add: eval_upd_unread)
  ultimately have "eval (ERel REq (EReg r) e) (s(r := ?a)) \<noteq> eval (ERel REq (EReg r) e) (s(r := ?a + 1))"
    by simp
  then show "\<exists>v1 v2. eval (ERel REq (EReg r) e) (s(r := v1)) \<noteq> eval (ERel REq (EReg r) e) (s(r := v2))"
    by blast
qed

section \<open>Each hypothesis is needed\<close>

theorem tl_needs_linear:
  assumes rq: "r \<noteq> q"
  shows "\<forall>i \<in> set (cancel2 r q). inj_in (snd i) r \<and> inj_in (snd i) q"
    and "taint (src q) (cancel2 r q) r = Some TU"
    and "q \<in> vdep (cancel2 r q) r"
    and "\<not> prog_dep (cancel2 r q) q r"
proof -
  show "\<forall>i \<in> set (cancel2 r q). inj_in (snd i) r \<and> inj_in (snd i) q"
    and "q \<in> vdep (cancel2 r q) r" and "\<not> prog_dep (cancel2 r q) q r"
    using sri_needed[OF rq] by blast+
  have t1: "treads (src q) (EBin OAdd (EReg r) (EReg q)) = {q}"
    using rq by (auto simp: treads_def src_def)
  have "\<exists>r'. treads (src q) (EBin OAdd (EReg r) (EReg q)) = {r'} \<and> src q r' = Some TI
              \<and> inj_in (EBin OAdd (EReg r) (EReg q)) r'"
    using t1 inj_in_add_regs(2)[OF rq] by (auto simp: src_def)
  then have c1: "step_cls (src q) (EBin OAdd (EReg r) (EReg q)) = Some TI"
    using t1 by (simp add: step_cls_def)
  have t2: "treads ((src q)(r := Some TI)) (EBin OSub (EReg r) (EReg q)) = {r, q}"
    by (auto simp: treads_def src_def)
  have ns: "\<not> (\<exists>r'. ({r, q} :: reg set) = {r'})" using rq by auto
  have c2: "step_cls ((src q)(r := Some TI)) (EBin OSub (EReg r) (EReg q)) = Some TU"
    using t2 ns by (simp add: step_cls_def)
  show "taint (src q) (cancel2 r q) r = Some TU"
    using c1 c2 by (simp add: cancel2_def)
qed

theorem tl_needs_full:
  assumes xt: "x \<noteq> t" and ty: "t \<noteq> y"
  shows "unc (EBin OAnd (EReg x) (ENum 1)) x"
    and "\<not> inj_in (EBin OAnd (EReg x) (ENum 1)) x"
    and "unc (EBin OAnd (EReg t) (ENum 2)) t"
    and "taint (src x) (maskP x t y) y = Some TU"
    and "x \<in> vdep (maskP x t y) y"
    and "\<not> prog_dep (maskP x t y) x y"
proof -
  show u1: "unc (EBin OAnd (EReg x) (ENum 1)) x" by (rule unc_and_num) simp
  show ni: "\<not> inj_in (EBin OAnd (EReg x) (ENum 1)) x"
  proof
    assume ie: "inj_in (EBin OAnd (EReg x) (ENum 1)) x"
    have "eval (EBin OAnd (EReg x) (ENum 1)) ((\<lambda>_. 0)(x := 0))
          = eval (EBin OAnd (EReg x) (ENum 1)) ((\<lambda>_. 0)(x := 2))" by simp
    from inj_inD[OF ie this] show False by simp
  qed
  show "unc (EBin OAnd (EReg t) (ENum 2)) t" by (rule unc_and_num) simp
  show "x \<in> vdep (maskP x t y) y" and "\<not> prog_dep (maskP x t y) x y"
    using vdep_masking[OF xt ty] by blast+
  have t1: "treads (src x) (EBin OAnd (EReg x) (ENum 1)) = {x}"
    by (auto simp: treads_def src_def)
  have noTI: "\<not> (\<exists>r. treads (src x) (EBin OAnd (EReg x) (ENum 1)) = {r} \<and> src x r = Some TI
                     \<and> inj_in (EBin OAnd (EReg x) (ENum 1)) r)"
    using t1 ni by auto
  have isTN: "\<exists>r. treads (src x) (EBin OAnd (EReg x) (ENum 1)) = {r} \<and> src x r = Some TI
                 \<and> unc (EBin OAnd (EReg x) (ENum 1)) r"
    using t1 u1 by (auto simp: src_def)
  have c1: "step_cls (src x) (EBin OAnd (EReg x) (ENum 1)) = Some TN"
    using t1 noTI isTN by (simp add: step_cls_def)
  let ?T1 = "(src x)(t := Some TN)"
  have t2: "treads ?T1 (EBin OAnd (EReg t) (ENum 2)) = {t}"
    by (auto simp: treads_def)
  have notI: "\<not> (\<exists>r. treads ?T1 (EBin OAnd (EReg t) (ENum 2)) = {r} \<and> ?T1 r = Some TI
                    \<and> inj_in (EBin OAnd (EReg t) (ENum 2)) r)"
    using t2 by auto
  have notN: "\<not> (\<exists>r. treads ?T1 (EBin OAnd (EReg t) (ENum 2)) = {r} \<and> ?T1 r = Some TI
                    \<and> unc (EBin OAnd (EReg t) (ENum 2)) r)"
    using t2 by auto
  have c2: "step_cls ?T1 (EBin OAnd (EReg t) (ENum 2)) = Some TU"
    using t2 notI notN by (simp add: step_cls_def)
  show "taint (src x) (maskP x t y) y = Some TU"
    using c1 c2 by (simp add: maskP_def)
qed

definition annP :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> prog" where
  "annP x u y = [(u, ENum 0), (y, EBin OMul (EReg x) (EReg u))]"

theorem tl_needs_uniform:
  assumes xu: "x \<noteq> u" and uy: "u \<noteq> y"
  shows "non_constant (EBin OMul (EReg x) (EReg u)) x"
    and "\<not> unc (EBin OMul (EReg x) (EReg u)) x"
    and "taint (src x) (annP x u y) y = Some TU"
    and "x \<in> vdep (annP x u y) y"
    and "\<not> prog_dep (annP x u y) x y"
proof -
  let ?e = "EBin OMul (EReg x) (EReg u)"
  have "eval ?e (((\<lambda>_. 0)(u := 1))(x := 0)) \<noteq> eval ?e (((\<lambda>_. 0)(u := 1))(x := 1))"
    using xu by simp
  then show nc: "non_constant ?e x" unfolding non_constant_def by blast
  show nu: "\<not> unc ?e x"
  proof
    assume "unc ?e x"
    then obtain v1 v2 where "eval ?e (((\<lambda>_. 0)(u := 0))(x := v1)) \<noteq> eval ?e (((\<lambda>_. 0)(u := 0))(x := v2))"
      unfolding unc_def by blast
    then show False using xu by simp
  qed
  have ni: "\<not> inj_in ?e x" using nu inj_in_imp_unc by blast
  have c1: "step_cls (src x) (ENum 0) = None" by (simp add: step_cls_def treads_def)
  have T1: "(src x)(u := None) = src x" using xu by (auto simp: src_def)
  have t2: "treads (src x) ?e = {x}" using xu by (auto simp: treads_def src_def)
  have notI: "\<not> (\<exists>r. treads (src x) ?e = {r} \<and> src x r = Some TI \<and> inj_in ?e r)"
    using t2 ni by auto
  have notN: "\<not> (\<exists>r. treads (src x) ?e = {r} \<and> src x r = Some TI \<and> unc ?e r)"
    using t2 nu by auto
  have c2: "step_cls (src x) ?e = Some TU" using t2 notI notN by (simp add: step_cls_def)
  show "taint (src x) (annP x u y) y = Some TU"
    using c1 T1 c2 by (simp add: annP_def)
  have "x \<in> vdep [(y, ?e)] y" using nc by simp
  then show "x \<in> vdep (annP x u y) y" using xu by (force simp: annP_def)
  have "run (annP x u y) s y = 0" for s using uy by (simp add: annP_def)
  then show "\<not> prog_dep (annP x u y) x y" by (simp add: prog_dep_def)
qed

end
