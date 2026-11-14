(*  Title:   PwT_Correspondence.thy
    FSEN 2027 #41, fix pass (2026-11-06).

    Part 1. Cousot's ordered composition (SAS 2019, rules (10) and (16.b)).
      After an assignment z := e the relation is {(y,z) | e depends on y} plus the
      identity on every y other than z (so the old value of z is killed). Statement
      lists compose these relations in program order. We prove that this ordered
      composition is exactly the version-aware closure vdep, and that it differs
      from the flow-insensitive closure sites^* on the overwrite program
      y := x; y := w: the ordered composition kills (x, y); sites^* keeps it.

    Part 2. Fixed initial registers.
      esdep (PwT_EventBridge) quantifies over the initial register environment.
      The executable modes bind every initial register to a fixed value (its
      litmus initial value, or 0). esdep_at fixes the environment; label_at binds
      the initial registers to those constants. We prove:
        esdep_at s T i j  <->  non_constant (label_at s T j) (lsym i)
        esdep T i j       <->  (EX s. esdep_at s T i j)
      and the regression  load r; t := r * k; store t : dependent in esdep (some
      initial k), independent in esdep_at when k is initially 0, dependent when it
      is initially 1.

    No axioms, no sorry, no oracles beyond the HOL kernel.
*)
theory PwT_Correspondence
  imports PwT_Versioned WEV_SMT_PwT.PwT_EventBridge
begin

section \<open>Cousot's ordered composition equals vdep\<close>

definition cstep :: "reg \<times> expr \<Rightarrow> (reg \<times> reg) set" where
  "cstep i = (case i of (z, e) \<Rightarrow> {(y, z) | y. non_constant e y} \<union> {(y, y) | y. y \<noteq> z})"

fun crel :: "prog \<Rightarrow> (reg \<times> reg) set" where
  "crel [] = Id"
| "crel (i # Q) = cstep i O crel Q"

theorem crel_iff_vdep: "(x, y) \<in> crel P \<longleftrightarrow> x \<in> vdep P y"
proof (induction P arbitrary: x)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  have "(x, y) \<in> crel (i # Q) \<longleftrightarrow> (\<exists>w. (x, w) \<in> cstep (z, e) \<and> (w, y) \<in> crel Q)"
    by (auto simp: i)
  also have "\<dots> \<longleftrightarrow> (\<exists>w. ((w = z \<and> non_constant e x) \<or> (x = w \<and> x \<noteq> z)) \<and> w \<in> vdep Q y)"
    using Cons.IH by (auto simp: cstep_def)
  also have "\<dots> \<longleftrightarrow> x \<in> vdep (i # Q) y"
    by (auto simp: i split: if_splits)
  finally show ?case .
qed

theorem overwrite_cousot_kills:
  assumes "x \<noteq> y" and "w \<noteq> y" and "x \<noteq> w"
  shows "(x, y) \<notin> crel (overwrite2 x w y)"
    and "(x, y) \<in> rtrancl (sites (overwrite2 x w y))"
    and "\<not> prog_dep (overwrite2 x w y) x y"
  using sites_not_exact_sri[OF assms] assms(3) by (auto simp: crel_iff_vdep)

section \<open>Event-level dependence with the initial registers fixed\<close>

definition esdep_at :: "env \<Rightarrow> thread \<Rightarrow> nat \<Rightarrow> nat \<Rightarrow> bool" where
  "esdep_at s T i j \<longleftrightarrow> legal_pair T i j \<and>
     (\<exists>L v1 v2. store_val T j (L(i := v1)) s \<noteq> store_val T j (L(i := v2)) s)"

lemma esdep_iff_ex_esdep_at: "esdep T i j \<longleftrightarrow> (\<exists>s. esdep_at s T i j)"
  by (auto simp: esdep_def esdep_at_def)

text \<open>The composed label with every initial register bound to its fixed value.\<close>

definition label_at :: "env \<Rightarrow> thread \<Rightarrow> nat \<Rightarrow> expr" where
  "label_at s T j = sexec 0 (take j T) (\<lambda>r. ENum (s r)) (operand (T ! j))"

lemma label_at_store_val:
  "eval (label_at s T j) \<nu> = store_val T j (loads_of \<nu>) s"
  unfolding label_at_def store_val_def
  by (rule sexec_cexec) (simp_all add: loads_of_def)

theorem esdep_at_iff_label_at_non_constant:
  assumes "legal_pair T i j"
  shows "esdep_at s T i j \<longleftrightarrow> non_constant (label_at s T j) (lsym i)"
proof
  assume "esdep_at s T i j"
  then obtain L v1 v2
    where d: "store_val T j (L(i := v1)) s \<noteq> store_val T j (L(i := v2)) s"
    by (auto simp: esdep_at_def)
  let ?\<nu> = "val_of s L"
  have "eval (label_at s T j) (?\<nu>(lsym i := v1)) \<noteq> eval (label_at s T j) (?\<nu>(lsym i := v2))"
    using d by (simp add: label_at_store_val)
  then show "non_constant (label_at s T j) (lsym i)"
    unfolding non_constant_def by blast
next
  assume "non_constant (label_at s T j) (lsym i)"
  then obtain \<nu> v1 v2
    where d: "eval (label_at s T j) (\<nu>(lsym i := v1)) \<noteq> eval (label_at s T j) (\<nu>(lsym i := v2))"
    by (auto simp: non_constant_def)
  then have "store_val T j ((loads_of \<nu>)(i := v1)) s \<noteq> store_val T j ((loads_of \<nu>)(i := v2)) s"
    by (simp add: label_at_store_val)
  then show "esdep_at s T i j"
    using assms unfolding esdep_at_def by blast
qed

section \<open>Regression: load r; t := r * k; store t\<close>

definition kprog :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> thread" where
  "kprog r k t = [Load r, Asgn t (EBin OMul (EReg r) (EReg k)), Store t]"

lemma kprog_legal: "legal_pair (kprog r k t) 0 2"
  by (simp add: kprog_def legal_pair_def is_load_def is_store_def)

lemma kprog_store_val:
  assumes "r \<noteq> k"
  shows "store_val (kprog r k t) 2 L s = L 0 * s k"
  using assms by (simp add: kprog_def store_val_def)

theorem kprog_regression:
  assumes "r \<noteq> k"
  shows "esdep (kprog r k t) 0 2"
    and "s k = 0 \<Longrightarrow> \<not> esdep_at s (kprog r k t) 0 2"
    and "s k = 1 \<Longrightarrow> esdep_at s (kprog r k t) 0 2"
proof -
  have one: "s' k = 1 \<Longrightarrow> esdep_at s' (kprog r k t) 0 2" for s'
  proof -
    assume k1: "s' k = 1"
    have "store_val (kprog r k t) 2 ((\<lambda>_. 0)(0 := 0)) s'
          \<noteq> store_val (kprog r k t) 2 ((\<lambda>_. 0)(0 := 1)) s'"
      using k1 by (simp add: kprog_store_val[OF assms])
    then show ?thesis using kprog_legal unfolding esdep_at_def by blast
  qed
  show "s k = 1 \<Longrightarrow> esdep_at s (kprog r k t) 0 2" by (rule one)
  show "esdep (kprog r k t) 0 2"
    using one[of "\<lambda>_. 1"] by (auto simp: esdep_iff_ex_esdep_at)
  show "s k = 0 \<Longrightarrow> \<not> esdep_at s (kprog r k t) 0 2"
    by (simp add: esdep_at_def kprog_store_val[OF assms])
qed

end
