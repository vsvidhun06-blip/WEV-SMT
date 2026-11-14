(*  Title:      PwT_Capacity.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    HOW MUCH SEMANTIC INFORMATION MUST A PER-SITE DEPENDENCY SUMMARY RETAIN?

    This theory answers that question exactly, for the site-local abstraction
    interface of PwT_ReadRestricted.

    THE SETTING.  A *pointwise site abstraction* is a function code : expr => 'c
    applied to each instruction independently, keeping the target register and
    the order of the list:

        abstracted code P = map (%(x,e). (x, code e)) P

    It is *exact* when some arbitrary combining function f -- seeing the whole
    abstracted program and both registers, with no computability restriction --
    recovers program-level dependence on every program.

    THE RESULTS.

      1. ctx_equiv_iff_sem_equiv  (contextual collapse)
         Contextual equivalence with respect to the prog_dep observable
         coincides with extensional equality of expression denotations.  The
         coarse Boolean observable "does y move when x moves" is, under
         contexts, as discriminating as the full input-output function.

      2. exact_code_iff_refines_sem  (exactness characterization)
         exact_code code  <->  code refines denotational equality.
         So the minimal exact per-site code is the denotation itself.

      3. affine_capacity  (quantitative lower bound)
         The 2^64 affine expressions alpha*x+beta over one register are
         pairwise denotationally distinct, so any exact code is injective on
         them: at least 2^64 codes, i.e. >= 64 bits, for a ONE-register site.
         This is a LOWER bound only; it is nowhere near sufficient.

      4. A_read_insufficient_via_capacity
         The read-restricted impossibility of PwT_ReadRestricted re-derived as
         the one-bit corollary.  The original direct proof is left untouched,
         so the two proofs are an independent consistency check.

      5. unrestricted_beta_exact
         Because reg = string is infinite, an UNRESTRICTED per-site predicate
         can encode expression identity in unread register labels and IS
         exact at 32 bits.  Hence no unrestricted impossibility theorem of the
         A_read shape can hold, and some capacity restriction is necessary.

    WHAT IS NOT CLAIMED AS NEW.  Semantic (expression-level) dependency is due
    to Mastroeni and Zanardini; the cancellation imprecision witness to
    Podgurski and Clarke and to Cousot's Example 7; the general theory of
    minimal complete abstractions to Giacobazzi, Ranzato and Scozzari;
    contextual equivalence and full abstraction are standard.  Result 2 is the
    site-interface instantiation of ordinary factorization, not a new generic
    completeness theorem.  What is proved here is the collapse for THIS
    observable (1), and its quantitative consequences (3)-(5).

    NO sorry.  NO oops.  NO axiomatization.  Imports only the assumption-free
    expression/program chain -- never PwT_Model or WEV_SMT_Defs -- so every
    theorem below is free of the oracle axioms.
*)

theory PwT_Capacity
  imports PwT_ReadRestricted "HOL-Library.Countable"
begin

section \<open>Semantic and contextual equivalence of sites\<close>

definition sem_equiv :: "expr \<Rightarrow> expr \<Rightarrow> bool" where
  "sem_equiv e1 e2 \<longleftrightarrow> (\<forall>s. eval e1 s = eval e2 s)"

lemma sem_equiv_refl [simp]: "sem_equiv e e"
  by (simp add: sem_equiv_def)

lemma sem_equiv_sym: "sem_equiv e1 e2 \<Longrightarrow> sem_equiv e2 e1"
  by (simp add: sem_equiv_def)

text \<open>
  A single-hole site context is a prefix, the hole's target register, and a
  suffix.  This is exactly the shape of the witnesses used elsewhere in the
  development (@{term "[(t, e), (y, g)]"} is the empty-prefix case).
\<close>

definition plug :: "prog \<Rightarrow> reg \<Rightarrow> prog \<Rightarrow> expr \<Rightarrow> prog" where
  "plug pre z post e = pre @ (z, e) # post"

definition ctx_equiv :: "expr \<Rightarrow> expr \<Rightarrow> bool" where
  "ctx_equiv e1 e2 \<longleftrightarrow>
     (\<forall>pre z post a b. prog_dep (plug pre z post e1) a b
                     = prog_dep (plug pre z post e2) a b)"

section \<open>Pointwise site abstractions and exactness\<close>

definition abstracted :: "(expr \<Rightarrow> 'c) \<Rightarrow> prog \<Rightarrow> (reg \<times> 'c) list" where
  "abstracted code P = map (\<lambda>i. (fst i, code (snd i))) P"

definition exact_code :: "(expr \<Rightarrow> 'c) \<Rightarrow> bool" where
  "exact_code code \<longleftrightarrow>
     (\<exists>f. \<forall>P a b. prog_dep P a b = f (abstracted code P) a b)"

text \<open>
  The abstraction quantified over in PwT_ReadRestricted is the special case in
  which the code is a set of register labels.  This identification is what
  makes the corollary at the end of this theory a statement about the very
  same object as the original theorem.
\<close>

lemma alpha_beta_as_abstracted:
  "alpha_beta \<beta> P = abstracted (\<lambda>e. {r. \<beta> e r}) P"
  by (simp add: alpha_beta_def abstracted_def)

section \<open>Easy direction: denotational equality is a dependence congruence\<close>

lemma run_cong_sem:
  "list_all2 (\<lambda>i j. fst i = fst j \<and> sem_equiv (snd i) (snd j)) P Q
   \<Longrightarrow> run P s = run Q s"
proof (induction P arbitrary: Q s)
  case Nil
  then show ?case by simp
next
  case (Cons i P)
  from Cons.prems obtain j Q' where
    Q: "Q = j # Q'"
    and ij: "fst i = fst j" "sem_equiv (snd i) (snd j)"
    and tl: "list_all2 (\<lambda>i j. fst i = fst j \<and> sem_equiv (snd i) (snd j)) P Q'"
    by (auto simp: list_all2_Cons1)
  have "run (i # P) s = run P (s(fst i := eval (snd i) s))"
    by (cases i) simp
  also have "\<dots> = run Q' (s(fst i := eval (snd i) s))"
    by (rule Cons.IH[OF tl])
  also have "\<dots> = run (j # Q') s"
    using ij by (cases j) (simp add: sem_equiv_def)
  finally show ?case using Q by simp
qed

lemma list_all2_sem_refl:
  "list_all2 (\<lambda>i j. fst i = fst j \<and> sem_equiv (snd i) (snd j)) P P"
  by (induction P) auto

theorem sem_equiv_imp_ctx_equiv:
  assumes "sem_equiv e1 e2"
  shows "ctx_equiv e1 e2"
proof -
  have "run (plug pre z post e1) s = run (plug pre z post e2) s"
    for pre z post s
  proof (rule run_cong_sem)
    show "list_all2 (\<lambda>i j. fst i = fst j \<and> sem_equiv (snd i) (snd j))
            (plug pre z post e1) (plug pre z post e2)"
      unfolding plug_def
      using assms list_all2_sem_refl
      by (auto intro!: list_all2_appendI)
  qed
  then show ?thesis by (simp add: ctx_equiv_def prog_dep_def)
qed

section \<open>The separating context\<close>

text \<open>
  The continuation that separates two denotationally distinct sites.  It
  contains a syntactic copy of @{term e1}; this is legal because the SAME
  context is plugged with both expressions, so an abstraction assigning them
  equal codes abstracts the two programs identically.

  The guard bit is multiplied, never the difference itself: at BV32 a non-zero
  difference can still annihilate a non-zero multiplier, so multiplying by the
  0/1 indicator is what makes the construction robust.
\<close>

definition sep_guard :: "reg \<Rightarrow> expr \<Rightarrow> reg \<Rightarrow> expr" where
  "sep_guard t e1 x =
     EBin OMul (ERel RNe (EBin OSub (EReg t) e1) (ENum 0))
               (EBin OAdd (EReg x) (ENum 1))"

lemma eval_sep_guard:
  "eval (sep_guard t e1 x) s =
     (if s t - eval e1 s \<noteq> 0 then 1 else 0) * (s x + 1)"
  by (simp add: sep_guard_def)

definition sep_prog :: "reg \<Rightarrow> reg \<Rightarrow> reg \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> prog" where
  "sep_prog t y x e1 e = [(t, e), (y, sep_guard t e1 x)]"

lemma sep_prog_as_plug:
  "sep_prog t y x e1 e = plug [] t [(y, sep_guard t e1 x)] e"
  by (simp add: sep_prog_def plug_def)

lemma run_sep_prog_y:
  assumes t1: "t \<notin> regs e1" and xt: "x \<noteq> t"
  shows "run (sep_prog t y x e1 e) s y
         = (if eval e s - eval e1 s \<noteq> 0 then 1 else 0) * (s x + 1)"
proof -
  have e1_same: "eval e1 (s(t := eval e s)) = eval e1 s"
    by (rule eval_coincidence) (use t1 in auto)
  have "run (sep_prog t y x e1 e) s y
        = eval (sep_guard t e1 x) (s(t := eval e s))"
    by (simp add: sep_prog_def)
  also have "\<dots> = (if eval e s - eval e1 (s(t := eval e s)) \<noteq> 0 then 1 else 0)
                   * ((s(t := eval e s)) x + 1)"
    by (simp add: eval_sep_guard)
  also have "\<dots> = (if eval e s - eval e1 s \<noteq> 0 then 1 else 0) * (s x + 1)"
    using e1_same xt by simp
  finally show ?thesis .
qed

lemma sep_prog_e1_const:
  assumes "t \<notin> regs e1" and "x \<noteq> t"
  shows "\<not> prog_dep (sep_prog t y x e1 e1) x y"
proof -
  have z: "run (sep_prog t y x e1 e1) s y = 0" for s
    using run_sep_prog_y[OF assms, where e = e1 and s = s] by simp
  show ?thesis unfolding prog_dep_def using z by simp
qed

lemma sep_prog_e2_dep:
  assumes t1: "t \<notin> regs e1" and xt: "x \<noteq> t"
      and x1: "x \<notin> regs e1" and x2: "x \<notin> regs e2"
      and diff: "eval e2 s0 \<noteq> eval e1 s0"
  shows "prog_dep (sep_prog t y x e1 e2) x y"
proof -
  have nz: "eval e2 (s0(x := v)) - eval e1 (s0(x := v)) \<noteq> 0" for v
  proof -
    have "eval e2 (s0(x := v)) = eval e2 s0"
      by (rule eval_coincidence) (use x2 in auto)
    moreover have "eval e1 (s0(x := v)) = eval e1 s0"
      by (rule eval_coincidence) (use x1 in auto)
    ultimately show ?thesis using diff by simp
  qed
  have val: "run (sep_prog t y x e1 e2) (s0(x := v)) y = v + 1" for v
    using run_sep_prog_y[OF t1 xt, where e = e2 and s = "s0(x := v)"] nz[of v] by simp
  have "run (sep_prog t y x e1 e2) (s0(x := 0)) y
          \<noteq> run (sep_prog t y x e1 e2) (s0(x := 1)) y"
    using val[of 0] val[of 1] by simp
  then show ?thesis unfolding prog_dep_def by blast
qed

section \<open>The contextual collapse theorem\<close>

text \<open>
  Choosing the source register @{term x} FRESH makes the difference constant in
  @{term x}, which removes the second case the construction would otherwise
  need.  Freshness is available because @{typ reg} is @{typ string}, an
  infinite type, while @{term regs} is always finite.
\<close>

theorem ctx_equiv_imp_sem_equiv:
  assumes "ctx_equiv e1 e2"
  shows "sem_equiv e1 e2"
proof (rule ccontr)
  assume "\<not> sem_equiv e1 e2"
  then obtain s0 where diff: "eval e2 s0 \<noteq> eval e1 s0"
    by (metis sem_equiv_def)

  have fin: "finite (regs e1 \<union> regs e2)" by simp
  obtain x where xf: "x \<notin> regs e1 \<union> regs e2"
    using ex_new_if_finite[OF infinite_UNIV_listI fin] by blast
  have fin2: "finite (insert x (regs e1 \<union> regs e2))" using fin by simp
  obtain t where tf: "t \<notin> insert x (regs e1 \<union> regs e2)"
    using ex_new_if_finite[OF infinite_UNIV_listI fin2] by blast

  from tf have t1: "t \<notin> regs e1" and xt: "x \<noteq> t" by auto
  from xf have x1: "x \<notin> regs e1" and x2: "x \<notin> regs e2" by auto

  have "prog_dep (sep_prog t x x e1 e1) x x
        = prog_dep (sep_prog t x x e1 e2) x x"
    using assms unfolding ctx_equiv_def sep_prog_as_plug by blast
  moreover have "\<not> prog_dep (sep_prog t x x e1 e1) x x"
    by (rule sep_prog_e1_const[OF t1 xt])
  moreover have "prog_dep (sep_prog t x x e1 e2) x x"
    by (rule sep_prog_e2_dep[OF t1 xt x1 x2 diff])
  ultimately show False by simp
qed

theorem ctx_equiv_iff_sem_equiv: "ctx_equiv e1 e2 \<longleftrightarrow> sem_equiv e1 e2"
  using ctx_equiv_imp_sem_equiv sem_equiv_imp_ctx_equiv by blast

section \<open>Separation and the exactness characterization\<close>

lemma abstracted_plug_cong:
  assumes "code e1 = code e2"
  shows "abstracted code (plug pre z post e1) = abstracted code (plug pre z post e2)"
  using assms by (simp add: abstracted_def plug_def)

theorem separation:
  assumes same: "code e1 = code e2" and ne: "\<not> sem_equiv e1 e2"
  shows "\<not> exact_code code"
proof
  assume "exact_code code"
  then obtain f where f: "\<And>P a b. prog_dep P a b = f (abstracted code P) a b"
    by (auto simp: exact_code_def)
  from ne have "\<not> ctx_equiv e1 e2" using ctx_equiv_imp_sem_equiv by blast
  then obtain pre z post a b where
    d: "prog_dep (plug pre z post e1) a b \<noteq> prog_dep (plug pre z post e2) a b"
    by (unfold ctx_equiv_def) blast
  have "prog_dep (plug pre z post e1) a b
        = f (abstracted code (plug pre z post e1)) a b" by (rule f)
  also have "\<dots> = f (abstracted code (plug pre z post e2)) a b"
    using abstracted_plug_cong[OF same] by simp
  also have "\<dots> = prog_dep (plug pre z post e2) a b" using f by simp
  finally show False using d by simp
qed

text \<open>
  For the converse, a representative is chosen for each code that occurs.  No
  quotient type is needed: injectivity is stated as an implication.
\<close>

definition rep :: "(expr \<Rightarrow> 'c) \<Rightarrow> 'c \<Rightarrow> expr" where
  "rep code c = (SOME e. code e = c)"

lemma rep_code [simp]: "code (rep code (code e)) = code e"
  unfolding rep_def by (rule someI) (rule refl)

fun arun :: "(expr \<Rightarrow> 'c) \<Rightarrow> (reg \<times> 'c) list \<Rightarrow> env \<Rightarrow> env" where
  "arun code [] s = s"
| "arun code ((z, c) # L) s = arun code L (s(z := eval (rep code c) s))"

lemma arun_eq_run:
  assumes inj: "\<And>e1 e2. code e1 = code e2 \<Longrightarrow> sem_equiv e1 e2"
  shows "arun code (abstracted code P) s = run P s"
proof (induction P arbitrary: s)
  case Nil
  then show ?case by (simp add: abstracted_def)
next
  case (Cons i P)
  obtain z e where ie: "i = (z, e)" by (cases i) auto
  have "sem_equiv (rep code (code e)) e" using inj rep_code by blast
  then have "eval (rep code (code e)) s = eval e s" by (simp add: sem_equiv_def)
  then show ?case using ie Cons.IH by (simp add: abstracted_def fun_upd_def)
qed

theorem injective_imp_exact:
  assumes inj: "\<And>e1 e2. code e1 = code e2 \<Longrightarrow> sem_equiv e1 e2"
  shows "exact_code code"
proof -
  let ?f = "\<lambda>L a b. \<exists>s v1 v2. arun code L (s(a := v1)) b \<noteq> arun code L (s(a := v2)) b"
  show ?thesis unfolding exact_code_def
    by (rule exI[where x = ?f]) (simp add: prog_dep_def arun_eq_run[OF inj])
qed

theorem exact_code_iff_refines_sem:
  "exact_code code \<longleftrightarrow> (\<forall>e1 e2. code e1 = code e2 \<longrightarrow> sem_equiv e1 e2)"
  using separation injective_imp_exact by blast

text \<open>The denotation itself is an exact code -- the minimal one.\<close>

corollary denotation_code_exact: "exact_code eval"
  by (rule injective_imp_exact) (simp add: sem_equiv_def)

section \<open>Quantitative capacity: the affine family at BV32\<close>

definition affine :: "reg \<Rightarrow> word32 \<Rightarrow> word32 \<Rightarrow> expr" where
  "affine x a b = EBin OAdd (EBin OMul (ENum a) (EReg x)) (ENum b)"

lemma eval_affine [simp]: "eval (affine x a b) s = a * s x + b"
  by (simp add: affine_def)

lemma regs_affine [simp]: "regs (affine x a b) = {x}"
  by (simp add: affine_def)

lemma affine_distinct:
  assumes "(a, b) \<noteq> (a', b')"
  shows "\<not> sem_equiv (affine x a b) (affine x a' b')"
proof (cases "b = b'")
  case True
  with assms have ne: "a \<noteq> a'" by simp
  have "eval (affine x a b) (\<lambda>_. 1) \<noteq> eval (affine x a' b') (\<lambda>_. 1)"
    using True ne by simp
  then show ?thesis unfolding sem_equiv_def by blast
next
  case False
  have "eval (affine x a b) (\<lambda>_. 0) \<noteq> eval (affine x a' b') (\<lambda>_. 0)"
    using False by simp
  then show ?thesis unfolding sem_equiv_def by blast
qed

text \<open>
  Any exact code is injective on the affine family.  Since that family is
  indexed by @{typ "word32 \<times> word32"}, an exact pointwise site code needs at
  least \<open>2^64\<close> distinct codes for a site reading a single 32-bit register.
  This is a LOWER bound; it is not sufficient.
\<close>

theorem affine_capacity:
  assumes ex: "exact_code code"
      and eq: "code (affine x a b) = code (affine x a' b')"
  shows "(a, b) = (a', b')"
proof (rule ccontr)
  assume "(a, b) \<noteq> (a', b')"
  then have "\<not> sem_equiv (affine x a b) (affine x a' b')"
    by (rule affine_distinct)
  with eq have "\<not> exact_code code" by (rule separation)
  with ex show False by simp
qed

corollary affine_capacity_inj:
  assumes "exact_code code"
  shows "inj (\<lambda>ab. code (affine x (fst ab) (snd ab)))"
proof (rule injI)
  fix p q :: "word32 \<times> word32"
  assume "code (affine x (fst p) (snd p)) = code (affine x (fst q) (snd q))"
  from affine_capacity[OF assms this] show "p = q" by simp
qed

section \<open>The read-restricted impossibility as the one-bit corollary\<close>

text \<open>
  Under @{term A_read} a site reading only @{term x} can emit at most the two
  codes @{term "{}"} and @{term "{x}"}, while the affine family already
  supplies three pairwise denotationally distinct such sites.  Pigeonhole and
  separation then give the conclusion of
  @{thm [source] A_read_insufficient} by an independent route.
\<close>

theorem A_read_insufficient_via_capacity:
  assumes rd: "A_read \<beta>"
  shows "\<not> exact_code (\<lambda>e. {r. \<beta> e r})"
proof -
  define X :: reg where "X = ''x''"
  define e0 e1 e2 where
    "e0 = affine X 0 0" and "e1 = affine X 1 0" and "e2 = affine X 0 1"
  have codes: "{r. \<beta> e r} = (if \<beta> e X then {X} else {})" if "regs e = {X}" for e
    using capacity_two[OF rd that] .
  have r0: "regs e0 = {X}" and r1: "regs e1 = {X}" and r2: "regs e2 = {X}"
    by (simp_all add: e0_def e1_def e2_def)
  consider "\<beta> e0 X = \<beta> e1 X" | "\<beta> e0 X = \<beta> e2 X" | "\<beta> e1 X = \<beta> e2 X"
    by blast
  then show ?thesis
  proof cases
    case 1
    have "{r. \<beta> e0 r} = {r. \<beta> e1 r}"
      using codes[OF r0] codes[OF r1] 1 by simp
    moreover have "\<not> sem_equiv e0 e1"
      unfolding e0_def e1_def by (rule affine_distinct) simp
    ultimately show ?thesis by (rule separation)
  next
    case 2
    have "{r. \<beta> e0 r} = {r. \<beta> e2 r}"
      using codes[OF r0] codes[OF r2] 2 by simp
    moreover have "\<not> sem_equiv e0 e2"
      unfolding e0_def e2_def by (rule affine_distinct) simp
    ultimately show ?thesis by (rule separation)
  next
    case 3
    have "{r. \<beta> e1 r} = {r. \<beta> e2 r}"
      using codes[OF r1] codes[OF r2] 3 by simp
    moreover have "\<not> sem_equiv e1 e2"
      unfolding e1_def e2_def by (rule affine_distinct) simp
    ultimately show ?thesis by (rule separation)
  qed
qed

corollary A_read_insufficient_restated:
  assumes "A_read \<beta>"
  shows "\<not> (\<exists>f. \<forall>P a b. prog_dep P a b = f (alpha_beta \<beta> P) a b)"
  using A_read_insufficient_via_capacity[OF assms]
  by (simp add: exact_code_def alpha_beta_as_abstracted)

section \<open>Unrestricted abstractions are exact at BV32\<close>

text \<open>
  Dropping @{term A_read} makes the per-site code space unbounded, because an
  unrestricted predicate may mark registers the expression does not read and
  @{typ reg} is infinite.  Expression identity then fits in a single register
  label, and the resulting abstraction is exact.  So no unrestricted
  impossibility theorem of the @{term A_read} shape can hold: some capacity
  restriction is NECESSARY, not merely convenient.
\<close>

instance word :: (len) countable
  by (rule countable_classI[of unat]) (simp add: word_unat_eq_iff)

instance unop :: countable
  by countable_datatype

instance binop :: countable
  by countable_datatype

instance relop :: countable
  by countable_datatype

instance expr :: countable
  by countable_datatype

definition enc :: "expr \<Rightarrow> reg" where
  "enc e = replicate (to_nat e) (CHR ''a'')"

lemma inj_enc: "inj enc"
proof (rule injI)
  fix e1 e2 :: expr
  assume "enc e1 = enc e2"
  then have "to_nat e1 = to_nat e2"
    by (metis enc_def length_replicate)
  then show "e1 = e2" by simp
qed

theorem enc_code_exact: "exact_code (\<lambda>e. {enc e})"
proof (rule injective_imp_exact)
  fix e1 e2 :: expr
  assume "{enc e1} = {enc e2}"
  then have "enc e1 = enc e2" by simp
  then have "e1 = e2" using inj_enc by (simp add: inj_eq)
  then show "sem_equiv e1 e2" by simp
qed

corollary unrestricted_beta_exact:
  "\<exists>\<beta> :: expr \<Rightarrow> reg \<Rightarrow> bool. exact_code (\<lambda>e. {r. \<beta> e r})"
proof -
  have "(\<lambda>e. {r. r = enc e}) = (\<lambda>e. {enc e})" by auto
  then have "exact_code (\<lambda>e. {r. r = enc e})" using enc_code_exact by simp
  then show ?thesis by (rule exI[of _ "\<lambda>e r. r = enc e"])
qed

section \<open>Summary of what is and is not established\<close>

text \<open>
  PROVED here, with no assumptions beyond the definitions of this file and the
  expression/program theories it imports:

    ctx_equiv_iff_sem_equiv        contextual collapse for the prog_dep
                                   observable
    exact_code_iff_refines_sem     exactness = refinement of denotational
                                   equality (the site-interface instantiation
                                   of ordinary factorization)
    denotation_code_exact          the denotation is an exact code
    affine_capacity(_inj)          >= 2^64 codes for a one-register BV32 site
    A_read_insufficient_via_capacity   the one-bit corollary
    A_read_insufficient_restated   literally the conclusion of the original
                                   theorem, re-derived
    unrestricted_beta_exact        unrestricted exactness at BV32

  NOT established here:

    * any upper bound matching the 2^64 lower bound.  64 bits is NOT shown
      sufficient, and the minimal exact code space for one-register sites lies
      somewhere between 2^64 and the number of realized denotations.
    * anything about control dependence, loops or RMW.
    * anything about the shipped Java classifier, the SMT encoder, or any
      consistency axiom.  These are statements about expressions, programs and
      per-site codes only.
\<close>

end
