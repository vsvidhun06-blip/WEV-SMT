(*  Title:      PwT_Expr.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    STEP 1 of the PwT-correspondence mechanisation: the 32-bit branch-free
    expression language over which the QF_BV constancy oracle operates, together
    with its evaluation function.

    The grammar mirrors, constructor for constructor, the recursive-descent
    parser and evaluator in

        src/main/java/wev/smt/QfbvConstancyOracle.java

    specifically the AST nodes Num / Var / Un / Bin / Rel (lines 147-153) and the
    evaluator evalTerm (lines 173-213). The operator set is exactly:

        unary     ~ (bitwise complement)   - (two's-complement negation)
        binary    + - * ^ & |
        relational  ==  !=  <  <=  >  >=          (SIGNED, yielding 0 or 1)

    Signedness matters and is not a modelling choice: JavaSMT's
    bv.lessThan(l, r, true) passes signed = true (QfbvConstancyOracle lines
    191-194), so the relational operators are word_sless / word_sle, not the
    unsigned word order. A C comparison yields int 0/1 and is widened back to a
    32-bit value so that comparisons compose with the arithmetic grammar
    (QfbvConstancyOracle lines 197-199); eval mirrors that with an if-then-else
    into {0, 1}.

    Width. W = 32 is fixed in the oracle (line 51), so word32 = 32 word is
    monomorphic here rather than a length-polymorphic 'a word. Nothing below
    depends on the width being 32 except the concrete witness computations in
    PwT_Correspondence and PwT_Program, which are stated at word32 anyway.

    Registers are modelled as strings, matching the oracle's identifier set
    (LinkedHashSet<String> idents).
*)

theory PwT_Expr
  imports "HOL-Library.Word"
begin

text \<open>
  NOT / AND / OR / XOR are notation for the Bit_Operations constants, and since
  Isabelle2023 that notation lives in a bundle rather than being global. Without
  the unbundle, "NOT w" parses as application of a free variable NOT.
\<close>

unbundle bit_operations_syntax

section \<open>Words, registers, environments\<close>

type_synonym word32 = "32 word"
type_synonym reg = string
type_synonym env = "reg \<Rightarrow> word32"

section \<open>Syntax\<close>

text \<open>
  Operators are collected into three auxiliary datatypes rather than being spread
  over fifteen expr constructors. This is not cosmetic: structural induction on
  expr then has five cases instead of fifteen, and every proof below is an
  induction on expr.
\<close>

datatype unop = BNot | UMinus

datatype binop = OAdd | OSub | OMul | OXor | OAnd | OOr

datatype relop = REq | RNe | RLt | RLe | RGt | RGe

datatype expr =
    ENum word32
  | EReg reg
  | EUn unop expr
  | EBin binop expr expr
  | ERel relop expr expr

section \<open>Semantics\<close>

primrec eval_un :: "unop \<Rightarrow> word32 \<Rightarrow> word32" where
  "eval_un BNot w = NOT w"
| "eval_un UMinus w = - w"

primrec eval_bin :: "binop \<Rightarrow> word32 \<Rightarrow> word32 \<Rightarrow> word32" where
  "eval_bin OAdd a b = a + b"
| "eval_bin OSub a b = a - b"
| "eval_bin OMul a b = a * b"
| "eval_bin OXor a b = a XOR b"
| "eval_bin OAnd a b = a AND b"
| "eval_bin OOr  a b = a OR b"

primrec eval_rel :: "relop \<Rightarrow> word32 \<Rightarrow> word32 \<Rightarrow> bool" where
  "eval_rel REq a b = (a = b)"
| "eval_rel RNe a b = (a \<noteq> b)"
| "eval_rel RLt a b = (a <s b)"
| "eval_rel RLe a b = (a \<le>s b)"
| "eval_rel RGt a b = (b <s a)"
| "eval_rel RGe a b = (b \<le>s a)"

primrec eval :: "expr \<Rightarrow> env \<Rightarrow> word32" where
  "eval (ENum w) s = w"
| "eval (EReg r) s = s r"
| "eval (EUn u e) s = eval_un u (eval e s)"
| "eval (EBin b e1 e2) s = eval_bin b (eval e1 s) (eval e2 s)"
| "eval (ERel p e1 e2) s = (if eval_rel p (eval e1 s) (eval e2 s) then 1 else 0)"

section \<open>Read set and the coincidence lemma\<close>

primrec regs :: "expr \<Rightarrow> reg set" where
  "regs (ENum w) = {}"
| "regs (EReg r) = {r}"
| "regs (EUn u e) = regs e"
| "regs (EBin b e1 e2) = regs e1 \<union> regs e2"
| "regs (ERel p e1 e2) = regs e1 \<union> regs e2"

lemma finite_regs [simp]: "finite (regs e)"
  by (induction e) auto

text \<open>
  Evaluation depends on an environment only through the finitely many registers
  the expression actually mentions. This is the workhorse behind the finite
  hybrid argument in PwT_Program: it is what lets a difference between two
  environments be localised to a finite set of registers even though env is a
  total function on the (infinite) type of identifiers.
\<close>

lemma eval_coincidence:
  assumes "\<And>r. r \<in> regs e \<Longrightarrow> s1 r = s2 r"
  shows "eval e s1 = eval e s2"
  using assms by (induction e) auto

section \<open>Branch-free fragment\<close>

text \<open>
  branch_free_expr marks the fragment with no relational node. This is the
  syntactic counterpart of the branch_free predicate that WEV_SMT_Defs leaves
  abstract: a relational operator is exactly where a source-level branch
  condition enters the value expression.
\<close>

primrec branch_free_expr :: "expr \<Rightarrow> bool" where
  "branch_free_expr (ENum w) = True"
| "branch_free_expr (EReg r) = True"
| "branch_free_expr (EUn u e) = branch_free_expr e"
| "branch_free_expr (EBin b e1 e2) = (branch_free_expr e1 \<and> branch_free_expr e2)"
| "branch_free_expr (ERel p e1 e2) = False"

section \<open>Substitution\<close>

text \<open>
  The oracle's Javadoc states its query as a SUBSTITUTION,

      \<exists> (other regs), r1, r2 .  e[reg := r1] \<noteq> e[reg := r2]

  whereas the implementation builds two environments that differ at reg. The
  substitution lemma below is what makes those two readings the same statement;
  PwT_Correspondence uses it to show the Javadoc form and the implemented form
  coincide.
\<close>

primrec subst :: "expr \<Rightarrow> reg \<Rightarrow> expr \<Rightarrow> expr" where
  "subst (ENum w) r t = ENum w"
| "subst (EReg x) r t = (if x = r then t else EReg x)"
| "subst (EUn u e) r t = EUn u (subst e r t)"
| "subst (EBin b e1 e2) r t = EBin b (subst e1 r t) (subst e2 r t)"
| "subst (ERel p e1 e2) r t = ERel p (subst e1 r t) (subst e2 r t)"

lemma eval_subst: "eval (subst e r t) s = eval e (s(r := eval t s))"
  by (induction e) auto

section \<open>Sanity checks\<close>

text \<open>
  The canonical WEAKEST fake dependency: XOR of a register with itself is the
  constant 0, so r XOR r XOR 1 is the constant 1 whatever r holds. This is the
  pattern the detector must classify as fake, and the one the paper's worked
  example in \<section>4 is built from.
\<close>

lemma xor_self_is_zero:
  "eval (EBin OXor (EReg r) (EReg r)) s = 0"
  by simp

lemma xor_self_xor_one_is_one:
  "eval (EBin OXor (EBin OXor (EReg r) (EReg r)) (ENum 1)) s = 1"
  by simp

lemma sub_self_is_zero:
  "eval (EBin OSub (EReg r) (EReg r)) s = 0"
  by simp

lemma and_zero_is_zero:
  "eval (EBin OAnd (EReg r) (ENum 0)) s = 0"
  by simp

text \<open>Contrast: the real counterparts genuinely move with the register.\<close>

lemma add_one_moves:
  "eval (EBin OAdd (EReg r) (ENum 1)) (s(r := 0)) \<noteq>
   eval (EBin OAdd (EReg r) (ENum 1)) (s(r := 1))"
  by simp

lemma xor_one_neq: "(1::word32) XOR w \<noteq> w"
proof
  assume "(1::word32) XOR w = w"
  then have "((1::word32) XOR w) XOR w = w XOR w" by simp
  then show False by (simp add: xor.assoc)
qed

lemma xor_two_regs_moves:
  assumes "r \<noteq> q"
  shows "eval (EBin OXor (EReg r) (EReg q)) ((\<lambda>_. 0)(r := 0)) \<noteq>
         eval (EBin OXor (EReg r) (EReg q)) ((\<lambda>_. 0)(r := 1))"
  using assms by simp

text \<open>
  Masking: r AND 2 is not constant (bit 1 of r survives), but it is blind to
  bit 0. This single expression drives every divergence result in STEP 3 and
  STEP 4, so it is worth pinning its values down here.
\<close>

lemma mask_two_at_zero: "eval (EBin OAnd (EReg r) (ENum 2)) (s(r := 0)) = 0"
  by simp

lemma mask_one_at_one: "eval (EBin OAnd (EReg r) (ENum 1)) (s(r := 1)) = 1"
  by simp

lemma mask_two_at_two: "eval (EBin OAnd (EReg r) (ENum 2)) (s(r := 2)) = 2"
  by simp

lemma mask_one_then_two_is_zero:
  "eval (EBin OAnd (EBin OAnd (EReg r) (ENum 1)) (ENum 2)) s = 0"
  by (simp add: and.assoc)

text \<open>The relational fragment evaluates into \<open>{0, 1}\<close>, as the C idiom requires.\<close>

lemma rel_is_boolean: "eval (ERel p e1 e2) s = 0 \<or> eval (ERel p e1 e2) s = 1"
  by simp

lemma ne_zero_example:
  "eval (ERel RNe (EReg r) (ENum 0)) (s(r := 5)) = 1"
  by simp

lemma ne_zero_example':
  "eval (ERel RNe (EReg r) (ENum 0)) (s(r := 0)) = 0"
  by simp

lemma signed_lt_is_signed:
  "eval (ERel RLt (EReg r) (ENum 0)) (s(r := 0xFFFFFFFF)) = 1"
  by (simp add: word_sless_alt)

end
