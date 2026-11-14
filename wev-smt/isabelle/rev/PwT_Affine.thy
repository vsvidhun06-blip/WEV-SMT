(*  Title:   PwT_Affine.thy
    FSEN 2027 #41, novelty pass.

    Chaining with ring weights. The version-aware closure vdep chains Boolean
    per-instruction marks. Here the same chaining carries a 32-bit coefficient per
    (instruction, register) instead of a bit, and the combination is the ring
    Z/2^32 (multiply along a definition, add across paths) instead of the Boolean
    semiring (and, or). On the affine fragment (+, -, negation, complement and
    multiplication by a literal) the weighted closure wdep is exact, and an
    instruction code is exact over affine programs iff it determines the
    instruction's coefficient vector.

      affine_shift        affine e ==> eval e (s(r:=v)) = coef e r * v + eval e (s(r:=0))
      affine_nc           affine e ==> non_constant e r <-> coef e r ~= 0
      wdep_shift          the same shift law for whole affine programs, with wdep
      wdep_exact          affine_prog P ==> prog_dep P x y <-> wdep P y x ~= 0
      wdep_le_vdep        affine_prog P ==> wdep P y x ~= 0 ==> x : vdep P y
      wdep_summary        wdep reads only the coefficient summary of each step
      wdep_witnesses      x+1-x : weight 0;  x+x-x : weight 1 (Thm. 1's pair)
      affine_ctx_equiv    equal coefficient vectors ==> equal dependence in every affine context
      affine_ctx_sep      different coefficient of x ==> an affine context separates them
      aff_exact_iff       a code is exact over affine programs iff it refines coef
      aff_capacity        on {a*x + b}: the code needs the coefficient a, and only a

    No axioms, no sorry, no oracles beyond the HOL kernel.
*)
theory PwT_Affine
  imports PwT_Versioned
begin

section \<open>The affine fragment and its coefficients\<close>

fun is_num :: "expr \<Rightarrow> bool" where
  "is_num (ENum w) = True"
| "is_num _ = False"

fun numv :: "expr \<Rightarrow> word32" where
  "numv (ENum w) = w"
| "numv _ = 0"

fun affine :: "expr \<Rightarrow> bool" where
  "affine (ENum w) = True"
| "affine (EReg r) = True"
| "affine (EUn u e) = affine e"
| "affine (EBin OAdd a b) = (affine a \<and> affine b)"
| "affine (EBin OSub a b) = (affine a \<and> affine b)"
| "affine (EBin OMul a b) = ((is_num a \<and> affine b) \<or> (is_num b \<and> affine a))"
| "affine (EBin OXor a b) = False"
| "affine (EBin OAnd a b) = False"
| "affine (EBin OOr a b) = False"
| "affine (ERel p a b) = False"

fun coef :: "expr \<Rightarrow> reg \<Rightarrow> word32" where
  "coef (ENum w) r = 0"
| "coef (EReg x) r = (if x = r then 1 else 0)"
| "coef (EUn u e) r = - coef e r"
| "coef (EBin OAdd a b) r = coef a r + coef b r"
| "coef (EBin OSub a b) r = coef a r - coef b r"
| "coef (EBin OMul a b) r = (if is_num a then numv a * coef b r else coef a r * numv b)"
| "coef (EBin OXor a b) r = 0"
| "coef (EBin OAnd a b) r = 0"
| "coef (EBin OOr a b) r = 0"
| "coef (ERel p a b) r = 0"

lemma is_num_ENum: "is_num a \<Longrightarrow> a = ENum (numv a)"
  by (cases a) auto

lemma is_num_affine: "is_num a \<Longrightarrow> affine a"
  by (cases a) auto

lemma affine_shift:
  "affine e \<Longrightarrow> eval e (s(r := v)) = coef e r * v + eval e (s(r := 0))"
proof (induction e)
  case (ENum w)
  then show ?case by simp
next
  case (EReg x)
  then show ?case by simp
next
  case (EUn u e)
  from EUn.prems have "affine e" by simp
  then have IH: "eval e (s(r := v)) = coef e r * v + eval e (s(r := 0))" by (rule EUn.IH)
  show ?case
  proof (cases u)
    case BNot
    have A: "eval (EUn u e) (s(r := v)) = NOT (eval e (s(r := v)))"
      and B: "eval (EUn u e) (s(r := 0)) = NOT (eval e (s(r := 0)))"
      and C: "coef (EUn u e) r = - coef e r" using BNot by simp_all
    show ?thesis unfolding A B C IH not_eq_complement by (simp add: algebra_simps)
  next
    case UMinus
    have A: "eval (EUn u e) (s(r := v)) = - (eval e (s(r := v)))"
      and B: "eval (EUn u e) (s(r := 0)) = - (eval e (s(r := 0)))"
      and C: "coef (EUn u e) r = - coef e r" using UMinus by simp_all
    show ?thesis unfolding A B C IH by (simp add: algebra_simps)
  qed
next
  case (EBin b e1 e2)
  show ?case
  proof (cases b)
    case OAdd
    from EBin.prems OAdd have a1: "affine e1" and a2: "affine e2" by simp_all
    have I1: "eval e1 (s(r := v)) = coef e1 r * v + eval e1 (s(r := 0))"
      by (rule EBin.IH(1)[OF a1])
    have I2: "eval e2 (s(r := v)) = coef e2 r * v + eval e2 (s(r := 0))"
      by (rule EBin.IH(2)[OF a2])
    have A: "eval (EBin b e1 e2) (s(r := v)) = eval e1 (s(r := v)) + eval e2 (s(r := v))"
      and B: "eval (EBin b e1 e2) (s(r := 0)) = eval e1 (s(r := 0)) + eval e2 (s(r := 0))"
      and C: "coef (EBin b e1 e2) r = coef e1 r + coef e2 r" using OAdd by simp_all
    show ?thesis unfolding A B C I1 I2 by (simp add: algebra_simps)
  next
    case OSub
    from EBin.prems OSub have a1: "affine e1" and a2: "affine e2" by simp_all
    have I1: "eval e1 (s(r := v)) = coef e1 r * v + eval e1 (s(r := 0))"
      by (rule EBin.IH(1)[OF a1])
    have I2: "eval e2 (s(r := v)) = coef e2 r * v + eval e2 (s(r := 0))"
      by (rule EBin.IH(2)[OF a2])
    have A: "eval (EBin b e1 e2) (s(r := v)) = eval e1 (s(r := v)) - eval e2 (s(r := v))"
      and B: "eval (EBin b e1 e2) (s(r := 0)) = eval e1 (s(r := 0)) - eval e2 (s(r := 0))"
      and C: "coef (EBin b e1 e2) r = coef e1 r - coef e2 r" using OSub by simp_all
    show ?thesis unfolding A B C I1 I2 by (simp add: algebra_simps)
  next
    case OMul
    show ?thesis
    proof (cases "is_num e1")
      case True
      then obtain c where c: "e1 = ENum c" by (cases e1) auto
      from EBin.prems OMul c have "affine e2" by (auto dest: is_num_affine)
      then have I2: "eval e2 (s(r := v)) = coef e2 r * v + eval e2 (s(r := 0))"
        by (rule EBin.IH(2))
      have A: "eval (EBin b e1 e2) (s(r := v)) = c * eval e2 (s(r := v))"
        and B: "eval (EBin b e1 e2) (s(r := 0)) = c * eval e2 (s(r := 0))"
        and C: "coef (EBin b e1 e2) r = c * coef e2 r" using OMul c by simp_all
      show ?thesis unfolding A B C I2 by (simp add: algebra_simps)
    next
      case False
      with EBin.prems OMul have n2: "is_num e2" and a1: "affine e1" by auto
      then obtain d where d: "e2 = ENum d" by (cases e2) auto
      from a1 have I1: "eval e1 (s(r := v)) = coef e1 r * v + eval e1 (s(r := 0))"
        by (rule EBin.IH(1))
      have A: "eval (EBin b e1 e2) (s(r := v)) = eval e1 (s(r := v)) * d"
        and B: "eval (EBin b e1 e2) (s(r := 0)) = eval e1 (s(r := 0)) * d"
        and C: "coef (EBin b e1 e2) r = coef e1 r * d" using OMul d False by simp_all
      show ?thesis unfolding A B C I1 by (simp add: algebra_simps)
    qed
  next
    case OXor
    with EBin show ?thesis by simp
  next
    case OAnd
    with EBin show ?thesis by simp
  next
    case OOr
    with EBin show ?thesis by simp
  qed
next
  case (ERel p e1 e2)
  then show ?case by simp
qed

theorem affine_nc:
  assumes "affine e"
  shows "non_constant e r \<longleftrightarrow> coef e r \<noteq> 0"
proof
  assume "non_constant e r"
  then obtain s v1 v2 where d: "eval e (s(r := v1)) \<noteq> eval e (s(r := v2))"
    unfolding non_constant_def by blast
  show "coef e r \<noteq> 0"
  proof
    assume "coef e r = 0"
    with d affine_shift[OF assms, of s r v1] affine_shift[OF assms, of s r v2] show False
      by simp
  qed
next
  assume c: "coef e r \<noteq> 0"
  have "eval e ((\<lambda>_. 0)(r := 1)) \<noteq> eval e ((\<lambda>_. 0)(r := 0))"
    using c affine_shift[OF assms, of "\<lambda>_. 0" r 1] by simp
  then show "non_constant e r" unfolding non_constant_def by blast
qed

section \<open>The weighted closure\<close>

text \<open>
  wdep P y r is the coefficient of the initial r in the final y. Its recursion is the
  recursion of vdep with the Boolean semiring replaced by the ring of 32-bit words:
  r is in vdep ((z,e)#Q) y iff (z in vdep Q y and nc e r) or (r ~= z and r in vdep Q y).
\<close>

fun wdep :: "prog \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> word32" where
  "wdep [] y r = (if r = y then 1 else 0)"
| "wdep ((z, e) # Q) y r = wdep Q y z * coef e r + (if r = z then 0 else wdep Q y r)"

definition affine_prog :: "prog \<Rightarrow> bool" where
  "affine_prog P \<longleftrightarrow> (\<forall>(z, e) \<in> set P. affine e)"

lemma affine_prog_Nil [simp]: "affine_prog []"
  by (simp add: affine_prog_def)

lemma affine_prog_Cons [simp]: "affine_prog ((z, e) # Q) \<longleftrightarrow> affine e \<and> affine_prog Q"
  by (auto simp: affine_prog_def)

lemma affine_prog_append [simp]: "affine_prog (P @ Q) \<longleftrightarrow> affine_prog P \<and> affine_prog Q"
  by (auto simp: affine_prog_def)

theorem wdep_shift:
  "affine_prog P \<Longrightarrow> run P (s(r := v)) y = wdep P y r * v + run P (s(r := 0)) y"
proof (induction P arbitrary: s r v)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  obtain z e where i: "i = (z, e)" by (cases i)
  with Cons.prems have ae: "affine e" and aQ: "affine_prog Q" by auto
  note IH = Cons.IH[OF aQ]
  let ?c = "coef e r" and ?E0 = "eval e (s(r := 0))"
  have Ev: "eval e (s(r := v)) = ?c * v + ?E0" by (rule affine_shift[OF ae])
  show ?case
  proof (cases "r = z")
    case True
    have L0: "run (i # Q) (s(r := v)) y = run Q (s(z := ?c * v + ?E0)) y"
      using True Ev i by simp
    have L1: "run Q (s(z := ?c * v + ?E0)) y = wdep Q y z * (?c * v + ?E0) + run Q (s(z := 0)) y"
      by (rule IH)
    have R0: "run (i # Q) (s(r := 0)) y = run Q (s(z := ?E0)) y"
      using True i by simp
    have R1: "run Q (s(z := ?E0)) y = wdep Q y z * ?E0 + run Q (s(z := 0)) y"
      by (rule IH)
    have W: "wdep (i # Q) y r = wdep Q y z * ?c"
      using True i by simp
    show ?thesis unfolding L0 L1 R0 R1 W by (simp add: algebra_simps)
  next
    case False
    let ?X = "?c * v + ?E0"
    have L0: "run (i # Q) (s(r := v)) y = run Q ((s(z := ?X))(r := v)) y"
      using False Ev i by (simp add: fun_upd_twist)
    have L1: "run Q ((s(z := ?X))(r := v)) y = wdep Q y r * v + run Q ((s(z := ?X))(r := 0)) y"
      by (rule IH)
    have L2: "(s(z := ?X))(r := 0) = (s(r := 0))(z := ?X)"
      using False by (simp add: fun_upd_twist)
    have L3: "run Q ((s(r := 0))(z := ?X)) y = wdep Q y z * ?X + run Q ((s(r := 0))(z := 0)) y"
      by (rule IH)
    have R0: "run (i # Q) (s(r := 0)) y = run Q ((s(r := 0))(z := ?E0)) y"
      using i by simp
    have R1: "run Q ((s(r := 0))(z := ?E0)) y = wdep Q y z * ?E0 + run Q ((s(r := 0))(z := 0)) y"
      by (rule IH)
    have W: "wdep (i # Q) y r = wdep Q y z * ?c + wdep Q y r"
      using False i by simp
    show ?thesis unfolding L0 L1 L2 L3 R0 R1 W by (simp add: algebra_simps)
  qed
qed

theorem wdep_exact:
  assumes "affine_prog P"
  shows "prog_dep P x y \<longleftrightarrow> wdep P y x \<noteq> 0"
proof
  assume "prog_dep P x y"
  then obtain s v1 v2 where d: "run P (s(x := v1)) y \<noteq> run P (s(x := v2)) y"
    unfolding prog_dep_def by blast
  show "wdep P y x \<noteq> 0"
  proof
    assume "wdep P y x = 0"
    with d wdep_shift[OF assms, of s x v1 y] wdep_shift[OF assms, of s x v2 y] show False
      by simp
  qed
next
  assume w: "wdep P y x \<noteq> 0"
  have "run P ((\<lambda>_. 0)(x := 1)) y \<noteq> run P ((\<lambda>_. 0)(x := 0)) y"
    using w wdep_shift[OF assms, of "\<lambda>_. 0" x 1 y] by simp
  then show "prog_dep P x y" unfolding prog_dep_def by blast
qed

corollary wdep_le_vdep:
  "affine_prog P \<Longrightarrow> wdep P y x \<noteq> 0 \<Longrightarrow> x \<in> vdep P y"
  using wdep_exact vdep_sound by blast

text \<open>The analysis reads nothing but the coefficient summary of each step.\<close>

fun wdepS :: "(reg \<times> (reg \<Rightarrow> word32)) list \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> word32" where
  "wdepS [] y r = (if r = y then 1 else 0)"
| "wdepS ((z, k) # Q) y r = wdepS Q y z * k r + (if r = z then 0 else wdepS Q y r)"

definition csum :: "prog \<Rightarrow> (reg \<times> (reg \<Rightarrow> word32)) list" where
  "csum P = map (\<lambda>(z, e). (z, coef e)) P"

theorem wdep_summary: "wdep P y r = wdepS (csum P) y r"
proof (induction P arbitrary: r)
  case Nil
  then show ?case by (simp add: csum_def)
next
  case (Cons i Q)
  then show ?case by (cases i) (simp add: csum_def)
qed

theorem wdep_witnesses:
  assumes "x \<noteq> t" "x \<noteq> y" "t \<noteq> y"
  shows "wdep [(t, EBin OAdd (EReg x) (ENum 1)), (y, EBin OSub (EReg t) (EReg x))] y x = 0"
    and "wdep [(t, EBin OAdd (EReg x) (EReg x)), (y, EBin OSub (EReg t) (EReg x))] y x = 1"
    and "affine_prog [(t, EBin OAdd (EReg x) (ENum 1)), (y, EBin OSub (EReg t) (EReg x))]"
    and "affine_prog [(t, EBin OAdd (EReg x) (EReg x)), (y, EBin OSub (EReg t) (EReg x))]"
  using assms by auto

section \<open>Capacity over affine programs: the coefficient vector, and nothing more\<close>

lemma wdep_code:
  assumes "coef e1 = coef e2"
  shows "wdep (pre @ (z, e1) # post) y r = wdep (pre @ (z, e2) # post) y r"
  using assms
proof (induction pre arbitrary: r)
  case Nil
  then show ?case by simp
next
  case (Cons i Q)
  then show ?case by (cases i) simp
qed

theorem affine_ctx_equiv:
  assumes "affine_prog pre" "affine_prog post" "affine e1" "affine e2" "coef e1 = coef e2"
  shows "prog_dep (pre @ (z, e1) # post) a b \<longleftrightarrow> prog_dep (pre @ (z, e2) # post) a b"
  using assms wdep_exact[of "pre @ (z, e1) # post" a b] wdep_exact[of "pre @ (z, e2) # post" a b]
        wdep_code[OF assms(5), of pre z post b a]
  by simp

definition sep_ctx :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> word32 \<Rightarrow> prog" where
  "sep_ctx x t y c = [(y, EBin OSub (EReg t) (EBin OMul (ENum c) (EReg x)))]"

lemma wdep_sep:
  assumes "x \<noteq> t" "x \<noteq> y" "t \<noteq> y"
  shows "wdep ((t, e) # sep_ctx x t y c) y x = coef e x - c"
  using assms by (simp add: sep_ctx_def)

theorem affine_ctx_sep:
  assumes "x \<noteq> t" "x \<noteq> y" "t \<noteq> y" "affine e1" "affine e2" "coef e1 x \<noteq> coef e2 x"
  shows "\<not> prog_dep ((t, e1) # sep_ctx x t y (coef e1 x)) x y"
    and "prog_dep ((t, e2) # sep_ctx x t y (coef e1 x)) x y"
    and "affine_prog (sep_ctx x t y (coef e1 x))"
proof -
  have ac: "affine_prog (sep_ctx x t y (coef e1 x))" by (simp add: sep_ctx_def)
  show "affine_prog (sep_ctx x t y (coef e1 x))" by (rule ac)
  show "\<not> prog_dep ((t, e1) # sep_ctx x t y (coef e1 x)) x y"
    using wdep_exact[of "(t, e1) # sep_ctx x t y (coef e1 x)" x y] wdep_sep[OF assms(1-3)]
          assms(4) ac by simp
  show "prog_dep ((t, e2) # sep_ctx x t y (coef e1 x)) x y"
    using wdep_exact[of "(t, e2) # sep_ctx x t y (coef e1 x)" x y] wdep_sep[OF assms(1-3)]
          assms(5,6) ac by simp
qed

definition aff_exact :: "(expr \<Rightarrow> 'c) \<Rightarrow> bool" where
  "aff_exact c \<longleftrightarrow> (\<exists>f. \<forall>P a b. affine_prog P \<longrightarrow>
                        prog_dep P a b = f (map (\<lambda>(z, e). (z, c e)) P) a b)"

theorem aff_exact_iff:
  "aff_exact c \<longleftrightarrow> (\<forall>e1 e2. affine e1 \<longrightarrow> affine e2 \<longrightarrow> c e1 = c e2 \<longrightarrow> coef e1 = coef e2)"
proof
  assume ex: "aff_exact c"
  then obtain f where f: "\<And>P a b. affine_prog P \<Longrightarrow> prog_dep P a b = f (map (\<lambda>(z, e). (z, c e)) P) a b"
    unfolding aff_exact_def by blast
  show "\<forall>e1 e2. affine e1 \<longrightarrow> affine e2 \<longrightarrow> c e1 = c e2 \<longrightarrow> coef e1 = coef e2"
  proof (intro allI impI)
    fix e1 e2
    assume a1: "affine e1" and a2: "affine e2" and ceq: "c e1 = c e2"
    show "coef e1 = coef e2"
    proof (rule ccontr)
      assume "coef e1 \<noteq> coef e2"
      then obtain x where dx: "coef e1 x \<noteq> coef e2 x" by blast
      define t where "t = x @ ''a''"
      define y where "y = x @ ''b''"
      have d: "x \<noteq> t" "x \<noteq> y" "t \<noteq> y" by (auto simp: t_def y_def)
      note S = affine_ctx_sep[OF d a1 a2 dx]
      let ?K = "sep_ctx x t y (coef e1 x)"
      have "prog_dep ((t, e1) # ?K) x y = f (map (\<lambda>(z, e). (z, c e)) ((t, e1) # ?K)) x y"
        using a1 S(3) by (intro f) simp
      also have "map (\<lambda>(z, e). (z, c e)) ((t, e1) # ?K) = map (\<lambda>(z, e). (z, c e)) ((t, e2) # ?K)"
        using ceq by simp
      also have "f \<dots> x y = prog_dep ((t, e2) # ?K) x y"
        using a2 S(3) by (intro f[symmetric]) simp
      finally show False using S(1,2) by simp
    qed
  qed
next
  assume R: "\<forall>e1 e2. affine e1 \<longrightarrow> affine e2 \<longrightarrow> c e1 = c e2 \<longrightarrow> coef e1 = coef e2"
  define rep where "rep k = (SOME e. affine e \<and> c e = k)" for k
  define f where "f S a b = (wdepS (map (\<lambda>(z, k). (z, coef (rep k))) S) b a \<noteq> 0)" for S a b
  have rep: "coef (rep (c e)) = coef e" if "affine e" for e
  proof -
    have "affine (rep (c e)) \<and> c (rep (c e)) = c e"
      unfolding rep_def by (rule someI[of _ e]) (use that in simp)
    with R that show ?thesis by blast
  qed
  have m: "map (\<lambda>(z, k). (z, coef (rep k))) (map (\<lambda>(z, e). (z, c e)) P) = csum P"
    if "affine_prog P" for P
    using that
  proof (induction P)
    case Nil
    then show ?case by (simp add: csum_def)
  next
    case (Cons i Q)
    obtain z e where i: "i = (z, e)" by (cases i)
    with Cons.prems have ae: "affine e" and aQ: "affine_prog Q" by auto
    have "map (\<lambda>(z, k). (z, coef (rep k))) (map (\<lambda>(z, e). (z, c e)) Q) = csum Q"
      by (rule Cons.IH[OF aQ])
    with i rep[OF ae] show ?case by (simp add: csum_def)
  qed
  have key: "prog_dep P a b = f (map (\<lambda>(z, e). (z, c e)) P) a b" if aP: "affine_prog P" for P a b
  proof -
    have "f (map (\<lambda>(z, e). (z, c e)) P) a b = (wdepS (csum P) b a \<noteq> 0)"
      unfolding f_def m[OF aP] ..
    also have "\<dots> = (wdep P b a \<noteq> 0)" by (simp only: wdep_summary)
    also have "\<dots> = prog_dep P a b" by (rule wdep_exact[OF aP, symmetric])
    finally show ?thesis by (rule sym)
  qed
  have "\<forall>P a b. affine_prog P \<longrightarrow> prog_dep P a b = f (map (\<lambda>(z, e). (z, c e)) P) a b"
    using key by blast
  then show "aff_exact c" unfolding aff_exact_def by (rule exI[of _ f])
qed

text \<open>
  On the single-register affine instructions a*x + b, an exact code over affine programs
  must separate different a, and may identify different b: 2^32 classes, against the
  2^64 that affine_capacity_inj forces over all programs.
\<close>

definition axb :: "word32 \<Rightarrow> reg \<Rightarrow> word32 \<Rightarrow> expr" where
  "axb a x b = EBin OAdd (EBin OMul (ENum a) (EReg x)) (ENum b)"

lemma coef_axb: "coef (axb a x b) r = (if r = x then a else 0)"
  by (simp add: axb_def)

lemma affine_axb: "affine (axb a x b)"
  by (simp add: axb_def)

theorem aff_capacity:
  shows "coef (axb a x b) x = a"
    and "coef (axb a x b1) = coef (axb a x b2)"
    and "aff_exact c \<Longrightarrow> c (axb a1 x b1) = c (axb a2 x b2) \<Longrightarrow> a1 = a2"
proof -
  show "coef (axb a x b) x = a" by (simp add: coef_axb)
  show "coef (axb a x b1) = coef (axb a x b2)" by (rule ext) (simp only: coef_axb)
  assume ex: "aff_exact c" and eq: "c (axb a1 x b1) = c (axb a2 x b2)"
  have R: "\<forall>e1 e2. affine e1 \<longrightarrow> affine e2 \<longrightarrow> c e1 = c e2 \<longrightarrow> coef e1 = coef e2"
    using ex aff_exact_iff[of c] by blast
  have "coef (axb a1 x b1) = coef (axb a2 x b2)"
    using R affine_axb eq by blast
  then have "coef (axb a1 x b1) x = coef (axb a2 x b2) x" by (rule arg_cong[where f = "\<lambda>g. g x"])
  then show "a1 = a2" by (simp only: coef_axb if_True simp_thms)
qed

end
