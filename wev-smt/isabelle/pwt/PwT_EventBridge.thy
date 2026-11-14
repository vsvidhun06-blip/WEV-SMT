(*  Title:      PwT_EventBridge.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    EVENT-LEVEL LIFTING of the site-summary results, for a concrete loop-free
    single-thread language with load and store events.

    WHAT IS PROVED
      T1  composed expression labels recover event-level value dependence
          exactly (esdep_iff_label_non_constant), via a symbolic execution
          proved to agree with concrete execution (sexec_cexec, label_store_val)
          and a realisability lemma (env_of_val_of, loads_of_val_of);
      T2  no combining function over instruction-local, read-restricted Boolean
          summaries computes event-level dependence for every legal thread and
          load/store pair (event_summary_insufficient), with explicit embedded
          witnesses (event_witnesses);
      T3  non-vacuity controls (control_copy, control_const,
          control_overwrite_first, control_overwrite_second,
          lift_addP, lift_dblP, lift_summ_agree, label_addP).

    MODELLING CHOICES (all definitions below; no axioms, no locales, no sorry)
      * A thread is a finite instruction list over Load r | Asgn x e | Store r,
        with e from PwT_Expr (BV32, the oracle grammar).  No loops, branches,
        RMW, fences or address computation; locations are not modelled.
      * One event per Load and per Store instruction, identified by its index
        in the list.  Asgn produces no event.
      * Loads receive externally supplied BV32 values, one per load event
        (L :: nat => word32, indexed by instruction index).
      * Registers start from an arbitrary initial environment s.
      * esdep T i j (event-level value dependence) is defined only for a legal
        pair: i a Load index, j a Store index, i < j.  It holds iff for SOME
        initial environment s and SOME values of all other loads, two values of
        load i make store j write different values.  The other inputs are held
        fixed between the two runs, exactly as non_constant fixes the other
        registers.
      * Symbolic inputs are DISJOINT and INJECTIVE: isym r for the initial value
        of register r, lsym n for load event n.  A load's input is never
        identified with its destination register name: re-loading a register
        produces a fresh symbol (see the two control_overwrite lemmas).
      * The interface of T2 retains instruction kinds and order, load/store
        register operands, assignment targets, and the set of registers beta
        marks at each assignment.  It does not see expressions, observed
        values, other candidates or any richer per-site code.

    WHAT IS NOT PROVED
      Nothing about multi-thread consistency, rf/jf cycles, loops, RMW,
      control, memory aliasing, compilation correctness, or the actual IMM or
      CAT encodings.  T2 bounds one restricted interface; it does not show that
      expression labels are the only repair (PwT_Capacity: any code refining
      the denotation is exact at site level).

    IMPORTS
      Only the assumption-free chain PwT_Expr - PwT_Constancy - PwT_R4b -
      PwT_Program - PwT_Granularity - PwT_ReadRestricted.  Neither PwT_Model
      nor WEV_SMT_Defs (the oracle axiomatisation) is imported.
*)

theory PwT_EventBridge
  imports PwT_ReadRestricted
begin

section \<open>Threads, events and concrete execution\<close>

datatype tinstr = Load reg | Asgn reg expr | Store reg

type_synonym thread = "tinstr list"
type_synonym loadvals = "nat \<Rightarrow> word32"

text \<open>
  cexec n T L s: execute T, whose first instruction has index n, from register
  environment s, with load event m receiving the value L m.
\<close>

fun cexec :: "nat \<Rightarrow> thread \<Rightarrow> loadvals \<Rightarrow> env \<Rightarrow> env" where
  "cexec n [] L s = s"
| "cexec n (Load r # T) L s = cexec (Suc n) T L (s(r := L n))"
| "cexec n (Asgn x e # T) L s = cexec (Suc n) T L (s(x := eval e s))"
| "cexec n (Store r # T) L s = cexec (Suc n) T L s"

definition is_load :: "thread \<Rightarrow> nat \<Rightarrow> bool" where
  "is_load T i \<longleftrightarrow> i < length T \<and> (\<exists>r. T ! i = Load r)"

definition is_store :: "thread \<Rightarrow> nat \<Rightarrow> bool" where
  "is_store T j \<longleftrightarrow> j < length T \<and> (\<exists>r. T ! j = Store r)"

definition legal_pair :: "thread \<Rightarrow> nat \<Rightarrow> nat \<Rightarrow> bool" where
  "legal_pair T i j \<longleftrightarrow> is_load T i \<and> is_store T j \<and> i < j"

text \<open>The register operand of an instruction; only used at Store indices.\<close>

fun operand :: "tinstr \<Rightarrow> reg" where
  "operand (Load r) = r"
| "operand (Asgn x e) = x"
| "operand (Store r) = r"

text \<open>The value written by the store at index j: its register after the prefix.\<close>

definition store_val :: "thread \<Rightarrow> nat \<Rightarrow> loadvals \<Rightarrow> env \<Rightarrow> word32" where
  "store_val T j L s = cexec 0 (take j T) L s (operand (T ! j))"

section \<open>Event-level value dependence\<close>

definition esdep :: "thread \<Rightarrow> nat \<Rightarrow> nat \<Rightarrow> bool" where
  "esdep T i j \<longleftrightarrow> legal_pair T i j \<and>
     (\<exists>s L v1 v2. store_val T j (L(i := v1)) s \<noteq> store_val T j (L(i := v2)) s)"

section \<open>Disjoint, injective symbolic inputs\<close>

definition isym :: "reg \<Rightarrow> reg" where
  "isym r = CHR ''I'' # r"

definition lsym :: "nat \<Rightarrow> reg" where
  "lsym n = CHR ''L'' # replicate n CHR ''#''"

lemma inj_isym: "inj isym"
  by (auto simp: inj_def isym_def)

lemma inj_lsym: "inj lsym"
proof (rule injI)
  fix m n assume "lsym m = lsym n"
  then have "length (replicate m CHR ''#'') = length (replicate n CHR ''#'')"
    by (simp add: lsym_def)
  then show "m = n" by simp
qed

lemma lsym_eq_iff [simp]: "lsym m = lsym n \<longleftrightarrow> m = n"
  using inj_lsym by (auto dest: injD)

lemma isym_neq_lsym [simp]: "isym r \<noteq> lsym n" "lsym n \<noteq> isym r"
  by (simp_all add: isym_def lsym_def)

section \<open>Simultaneous substitution\<close>

primrec ssubst :: "(reg \<Rightarrow> expr) \<Rightarrow> expr \<Rightarrow> expr" where
  "ssubst \<sigma> (ENum w) = ENum w"
| "ssubst \<sigma> (EReg x) = \<sigma> x"
| "ssubst \<sigma> (EUn u e) = EUn u (ssubst \<sigma> e)"
| "ssubst \<sigma> (EBin b e1 e2) = EBin b (ssubst \<sigma> e1) (ssubst \<sigma> e2)"
| "ssubst \<sigma> (ERel p e1 e2) = ERel p (ssubst \<sigma> e1) (ssubst \<sigma> e2)"

lemma eval_ssubst: "eval (ssubst \<sigma> e) \<nu> = eval e (\<lambda>r. eval (\<sigma> r) \<nu>)"
  by (induction e) auto

text \<open>The existing single-register substitution is the special case.\<close>

lemma subst_as_ssubst: "subst e r t = ssubst (EReg(r := t)) e"
  by (induction e) auto

section \<open>Symbolic execution and composed labels\<close>

text \<open>
  sexec n T sigma: the symbolic register environment after T.  Initially each
  register denotes its initial-value symbol (sym0); Load r replaces r by the
  load event's fresh symbol; Asgn x e substitutes the current symbolic
  environment into e; Store leaves it unchanged.
\<close>

fun sexec :: "nat \<Rightarrow> thread \<Rightarrow> (reg \<Rightarrow> expr) \<Rightarrow> (reg \<Rightarrow> expr)" where
  "sexec n [] \<sigma> = \<sigma>"
| "sexec n (Load r # T) \<sigma> = sexec (Suc n) T (\<sigma>(r := EReg (lsym n)))"
| "sexec n (Asgn x e # T) \<sigma> = sexec (Suc n) T (\<sigma>(x := ssubst \<sigma> e))"
| "sexec n (Store r # T) \<sigma> = sexec (Suc n) T \<sigma>"

definition sym0 :: "reg \<Rightarrow> expr" where
  "sym0 r = EReg (isym r)"

text \<open>The composed label of the store at j: an expression over symbolic inputs.\<close>

definition label :: "thread \<Rightarrow> nat \<Rightarrow> expr" where
  "label T j = sexec 0 (take j T) sym0 (operand (T ! j))"

subsection \<open>Symbolic execution agrees with concrete execution\<close>

lemma sexec_cexec:
  assumes "\<And>r. eval (\<sigma> r) \<nu> = s r" and "\<And>m. \<nu> (lsym m) = L m"
  shows "eval (sexec n T \<sigma> r) \<nu> = cexec n T L s r"
  using assms
proof (induction n T \<sigma> arbitrary: s r rule: sexec.induct)
  case (1 n \<sigma>)
  then show ?case by simp
next
  case (2 n r' T \<sigma>)
  have h: "\<And>q. eval ((\<sigma>(r' := EReg (lsym n))) q) \<nu> = (s(r' := L n)) q"
    using "2.prems" by simp
  have "eval (sexec (Suc n) T (\<sigma>(r' := EReg (lsym n))) r) \<nu> = cexec (Suc n) T L (s(r' := L n)) r"
    by (rule "2.IH"[OF h "2.prems"(2)])
  then show ?case by simp
next
  case (3 n x e T \<sigma>)
  have env: "(\<lambda>q. eval (\<sigma> q) \<nu>) = s" using "3.prems"(1) by (simp add: fun_eq_iff)
  have h: "\<And>q. eval ((\<sigma>(x := ssubst \<sigma> e)) q) \<nu> = (s(x := eval e s)) q"
    using "3.prems"(1) env by (simp add: eval_ssubst)
  have "eval (sexec (Suc n) T (\<sigma>(x := ssubst \<sigma> e)) r) \<nu> = cexec (Suc n) T L (s(x := eval e s)) r"
    by (rule "3.IH"[OF h "3.prems"(2)])
  then show ?case by simp
next
  case (4 n r' T \<sigma>)
  show ?case using "4.IH"[OF "4.prems"] by simp
qed

definition env_of :: "(reg \<Rightarrow> word32) \<Rightarrow> env" where
  "env_of \<nu> r = \<nu> (isym r)"

definition loads_of :: "(reg \<Rightarrow> word32) \<Rightarrow> loadvals" where
  "loads_of \<nu> m = \<nu> (lsym m)"

lemma sexec_sym0_eval:
  "eval (sexec n T sym0 r) \<nu> = cexec n T (loads_of \<nu>) (env_of \<nu>) r"
  by (rule sexec_cexec) (simp_all add: sym0_def env_of_def loads_of_def)

theorem label_store_val:
  "eval (label T j) \<nu> = store_val T j (loads_of \<nu>) (env_of \<nu>)"
  by (simp add: label_def store_val_def sexec_sym0_eval)

subsection \<open>Every concrete input is realised by some symbolic valuation\<close>

definition val_of :: "env \<Rightarrow> loadvals \<Rightarrow> reg \<Rightarrow> word32" where
  "val_of s L x = (case x of [] \<Rightarrow> 0
                   | c # rest \<Rightarrow> if c = CHR ''I'' then s rest
                                 else if c = CHR ''L'' then L (length rest) else 0)"

lemma env_of_val_of [simp]: "env_of (val_of s L) = s"
  by (simp add: fun_eq_iff env_of_def val_of_def isym_def)

lemma loads_of_val_of [simp]: "loads_of (val_of s L) = L"
  by (simp add: fun_eq_iff loads_of_def val_of_def lsym_def)

lemma loads_of_upd [simp]: "loads_of (\<nu>(lsym i := v)) = (loads_of \<nu>)(i := v)"
  by (auto simp: loads_of_def)

lemma env_of_upd [simp]: "env_of (\<nu>(lsym i := v)) = env_of \<nu>"
  by (auto simp: env_of_def)

section \<open>T1: composed-label exactness\<close>

theorem esdep_iff_label_non_constant:
  assumes "legal_pair T i j"
  shows "esdep T i j \<longleftrightarrow> non_constant (label T j) (lsym i)"
proof
  assume "esdep T i j"
  then obtain s L v1 v2
    where d: "store_val T j (L(i := v1)) s \<noteq> store_val T j (L(i := v2)) s"
    by (auto simp: esdep_def)
  let ?\<nu> = "val_of s L"
  have "eval (label T j) (?\<nu>(lsym i := v1)) \<noteq> eval (label T j) (?\<nu>(lsym i := v2))"
    using d by (simp add: label_store_val)
  then show "non_constant (label T j) (lsym i)"
    unfolding non_constant_def by blast
next
  assume "non_constant (label T j) (lsym i)"
  then obtain \<nu> v1 v2
    where d: "eval (label T j) (\<nu>(lsym i := v1)) \<noteq> eval (label T j) (\<nu>(lsym i := v2))"
    by (auto simp: non_constant_def)
  then have "store_val T j ((loads_of \<nu>)(i := v1)) (env_of \<nu>)
           \<noteq> store_val T j ((loads_of \<nu>)(i := v2)) (env_of \<nu>)"
    by (simp add: label_store_val)
  then show "esdep T i j"
    using assms unfolding esdep_def by blast
qed

section \<open>Embedding straight-line programs as threads\<close>

definition embed :: "reg \<Rightarrow> prog \<Rightarrow> reg \<Rightarrow> thread" where
  "embed a P b = Load a # map (\<lambda>(x, e). Asgn x e) P @ [Store b]"

lemma cexec_map_Asgn: "cexec n (map (\<lambda>(x, e). Asgn x e) P) L s = run P s"
proof (induction P arbitrary: n s)
  case Nil
  then show ?case by simp
next
  case (Cons p P)
  then show ?case by (cases p) (simp add: fun_upd_def)
qed

lemma embed_legal: "legal_pair (embed a P b) 0 (Suc (length P))"
  by (simp add: legal_pair_def is_load_def is_store_def embed_def nth_append)

lemma embed_store_val:
  "store_val (embed a P b) (Suc (length P)) L s = run P (s(a := L 0)) b"
  by (simp add: store_val_def embed_def nth_append cexec_map_Asgn)

theorem esdep_embed_iff_prog_dep:
  "esdep (embed a P b) 0 (Suc (length P)) \<longleftrightarrow> prog_dep P a b"
  using embed_legal[of a P b]
  by (simp add: esdep_def embed_store_val prog_dep_def)

section \<open>T2: instruction-summary impossibility\<close>

datatype isum = SLoad reg | SAsgn reg "reg set" | SStore reg

text \<open>
  The interface: kinds and order of instructions, load/store register operands,
  assignment targets, and beta's marked set at each assignment.
\<close>

definition sum_instr :: "(expr \<Rightarrow> reg \<Rightarrow> bool) \<Rightarrow> tinstr \<Rightarrow> isum" where
  "sum_instr \<beta> \<iota> = (case \<iota> of Load r \<Rightarrow> SLoad r
                             | Asgn x e \<Rightarrow> SAsgn x {r. \<beta> e r}
                             | Store r \<Rightarrow> SStore r)"

definition summ :: "(expr \<Rightarrow> reg \<Rightarrow> bool) \<Rightarrow> thread \<Rightarrow> isum list" where
  "summ \<beta> T = map (sum_instr \<beta>) T"

lemma summ_embed:
  "summ \<beta> (embed a P b)
     = SLoad a # map (\<lambda>(x, M). SAsgn x M) (alpha_beta \<beta> P) @ [SStore b]"
proof -
  have "map (sum_instr \<beta>) (map (\<lambda>(x, e). Asgn x e) P)
          = map (\<lambda>(x, M). SAsgn x M) (alpha_beta \<beta> P)"
    by (induction P) (auto simp: sum_instr_def alpha_beta_def)
  then show ?thesis by (simp add: summ_def embed_def sum_instr_def)
qed

theorem event_summary_insufficient:
  assumes rd: "A_read \<beta>"
  shows "\<not> (\<exists>F. \<forall>T i j. legal_pair T i j \<longrightarrow> esdep T i j = F (summ \<beta> T) i j)"
proof
  assume "\<exists>F. \<forall>T i j. legal_pair T i j \<longrightarrow> esdep T i j = F (summ \<beta> T) i j"
  then obtain F where F: "\<And>T i j. legal_pair T i j \<Longrightarrow> esdep T i j = F (summ \<beta> T) i j"
    by blast
  define f where
    "f = (\<lambda>A a b. F (SLoad a # map (\<lambda>(x, M). SAsgn x M) A @ [SStore b]) 0 (Suc (length A)))"
  have exact: "prog_dep P a b = f (alpha_beta \<beta> P) a b" for P a b
  proof -
    have "prog_dep P a b = esdep (embed a P b) 0 (Suc (length P))"
      by (simp add: esdep_embed_iff_prog_dep)
    also have "\<dots> = F (summ \<beta> (embed a P b)) 0 (Suc (length P))"
      using F[OF embed_legal] .
    also have "\<dots> = f (alpha_beta \<beta> P) a b"
      by (simp add: f_def summ_embed alpha_beta_def)
    finally show ?thesis .
  qed
  have "\<not> (\<exists>f. \<forall>P a b. prog_dep P a b = f (alpha_beta \<beta> P) a b)"
    using A_read_insufficient[where t = "''t''" and y = "''y''", OF rd] by simp
  then show False using exact by blast
qed

subsection \<open>The embedded witnesses, explicitly\<close>

lemma length_twoP [simp]: "length (twoP t y e g) = 2"
  by (simp add: twoP_def)

lemma embed_twoP_legal: "legal_pair (embed a (twoP t y e g) b) 0 3"
  using embed_legal[of a "twoP t y e g" b] by (simp add: numeral_3_eq_3)

lemma esdep_embed_twoP:
  "esdep (embed a (twoP t y e g) b) 0 3 \<longleftrightarrow> prog_dep (twoP t y e g) a b"
  using esdep_embed_iff_prog_dep[of a "twoP t y e g" b] by (simp add: numeral_3_eq_3)

theorem event_witnesses:
  assumes rd: "A_read \<beta>" and ne: "t \<noteq> y"
  shows "\<exists>e1 e2 g.
           legal_pair (embed x (twoP t y e1 g) y) 0 3
         \<and> legal_pair (embed x (twoP t y e2 g) y) 0 3
         \<and> summ \<beta> (embed x (twoP t y e1 g) y) = summ \<beta> (embed x (twoP t y e2 g) y)
         \<and> esdep (embed x (twoP t y e1 g) y) 0 3 \<noteq> esdep (embed x (twoP t y e2 g) y) 0 3"
proof -
  have emptyA: "{r. \<beta> (eA x) r} = {}"
    by (rule marks_empty_of_no_regs[OF rd]) simp
  show ?thesis
  proof (cases "\<beta> (eB x) x")
    case False
    have "{r. \<beta> (eB x) r} = {}" using capacity_two[OF rd, of "eB x" x] False by simp
    then have a: "alpha_beta \<beta> (twoP t y (eA x) (g1 t)) = alpha_beta \<beta> (twoP t y (eB x) (g1 t))"
      by (simp add: alpha_beta_def twoP_def emptyA)
    have "summ \<beta> (embed x (twoP t y (eA x) (g1 t)) y) = summ \<beta> (embed x (twoP t y (eB x) (g1 t)) y)"
      by (simp only: summ_embed a)
    moreover have "esdep (embed x (twoP t y (eA x) (g1 t)) y) 0 3
                 \<noteq> esdep (embed x (twoP t y (eB x) (g1 t)) y) 0 3"
      using dep_A_g1[OF ne] dep_B_g1[OF ne] by (simp add: esdep_embed_twoP)
    ultimately show ?thesis using embed_twoP_legal by blast
  next
    case True
    show ?thesis
    proof (cases "\<beta> (eC x) x")
      case False
      have "{r. \<beta> (eC x) r} = {}" using capacity_two[OF rd, of "eC x" x] False by simp
      then have a: "alpha_beta \<beta> (twoP t y (eA x) (g1 t)) = alpha_beta \<beta> (twoP t y (eC x) (g1 t))"
        by (simp add: alpha_beta_def twoP_def emptyA)
      have "summ \<beta> (embed x (twoP t y (eA x) (g1 t)) y) = summ \<beta> (embed x (twoP t y (eC x) (g1 t)) y)"
        by (simp only: summ_embed a)
      moreover have "esdep (embed x (twoP t y (eA x) (g1 t)) y) 0 3
                   \<noteq> esdep (embed x (twoP t y (eC x) (g1 t)) y) 0 3"
        using dep_A_g1[OF ne] dep_C_g1[OF ne] by (simp add: esdep_embed_twoP)
      ultimately show ?thesis using embed_twoP_legal by blast
    next
      case True2: True
      have "{r. \<beta> (eB x) r} = {x}" using capacity_two[OF rd, of "eB x" x] True by simp
      moreover have "{r. \<beta> (eC x) r} = {x}" using capacity_two[OF rd, of "eC x" x] True2 by simp
      ultimately have a:
        "alpha_beta \<beta> (twoP t y (eB x) (g2 t)) = alpha_beta \<beta> (twoP t y (eC x) (g2 t))"
        by (simp add: alpha_beta_def twoP_def)
      have "summ \<beta> (embed x (twoP t y (eB x) (g2 t)) y) = summ \<beta> (embed x (twoP t y (eC x) (g2 t)) y)"
        by (simp only: summ_embed a)
      moreover have "esdep (embed x (twoP t y (eB x) (g2 t)) y) 0 3
                   \<noteq> esdep (embed x (twoP t y (eC x) (g2 t)) y) 0 3"
        using dep_B_g2[OF ne] dep_C_g2[OF ne] by (simp add: esdep_embed_twoP)
      ultimately show ?thesis using embed_twoP_legal by blast
    qed
  qed
qed

section \<open>T3: non-vacuity controls\<close>

text \<open>A load whose value is stored directly is a dependency.\<close>

lemma control_copy: "esdep [Load r, Store r] 0 1"
  unfolding esdep_def
proof (intro conjI exI)
  show "legal_pair [Load r, Store r] 0 1"
    by (simp add: legal_pair_def is_load_def is_store_def)
  show "store_val [Load r, Store r] 1 ((\<lambda>_. 0)(0 := 0)) (\<lambda>_. 0)
      \<noteq> store_val [Load r, Store r] 1 ((\<lambda>_. 0)(0 := 1)) (\<lambda>_. 0)"
    by (simp add: store_val_def)
qed

text \<open>A load whose register is overwritten by a constant before the store is not.\<close>

lemma control_const: "\<not> esdep [Load r, Asgn r (ENum 5), Store r] 0 2"
  by (simp add: esdep_def store_val_def numeral_2_eq_2)

text \<open>
  Re-loading the same register: the store depends on the second load event and
  not on the first.  This is the case a scheme identifying a load's input with
  its destination register name would get wrong.
\<close>

lemma control_overwrite_first: "\<not> esdep [Load r, Load r, Store r] 0 2"
  by (simp add: esdep_def store_val_def numeral_2_eq_2)

lemma control_overwrite_second: "esdep [Load r, Load r, Store r] 1 2"
  unfolding esdep_def
proof (intro conjI exI)
  show "legal_pair [Load r, Load r, Store r] 1 2"
    by (simp add: legal_pair_def is_load_def is_store_def numeral_2_eq_2)
  show "store_val [Load r, Load r, Store r] 2 ((\<lambda>_. 0)(1 := 0)) (\<lambda>_. 0)
      \<noteq> store_val [Load r, Load r, Store r] 2 ((\<lambda>_. 0)(1 := 1)) (\<lambda>_. 0)"
    by (simp add: store_val_def numeral_2_eq_2)
qed

text \<open>The P_plus / P_times witnesses of PwT_Granularity, lifted to events.\<close>

lemma length_addP [simp]: "length (addP x t y) = 2"
  by (simp add: addP_def)

lemma length_dblP [simp]: "length (dblP x t y) = 2"
  by (simp add: dblP_def)

lemma lift_addP:
  assumes "x \<noteq> t"
  shows "\<not> esdep (embed x (addP x t y) y) 0 3"
  using esdep_embed_iff_prog_dep[of x "addP x t y" y] addP_no_prog_dep[OF assms]
  by (simp add: numeral_3_eq_3)

lemma lift_dblP:
  assumes "x \<noteq> t" and "x \<noteq> y"
  shows "esdep (embed x (dblP x t y) y) 0 3"
  using esdep_embed_iff_prog_dep[of x "dblP x t y" y] dblP_prog_dep[OF assms]
  by (simp add: numeral_3_eq_3)

lemma lift_summ_agree:
  "summ non_constant (embed x (addP x t y) y) = summ non_constant (embed x (dblP x t y) y)"
  by (simp only: summ_embed alpha_beta_non_constant alpha_addP_eq_dblP)

text \<open>
  The composed label of the P_plus store is the cancellation x+1-x over the
  load symbol: T1 then classifies it exactly, whereas the summaries above
  coincide with those of P_times.
\<close>

lemma label_addP:
  assumes "x \<noteq> t"
  shows "label (embed x (addP x t y) y) 3
           = EBin OSub (EBin OAdd (EReg (lsym 0)) (ENum 1)) (EReg (lsym 0))"
  using assms by (simp add: label_def embed_def addP_def numeral_3_eq_3)

end
