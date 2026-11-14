(*  Title:      PwT_Control.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    GATE 1 of the control-dependence validation: mechanisation of the F0 core
    result, plus the precise boundary at which the underlying abstraction fails.

    THE FRAGMENT F0.  One controlling read r, one two-way branch on a QF_BV
    guard g, both arms writing exactly once to the same fixed location, no
    aliasing, no RMW, no address dependence, no nested control, all other reads
    held equal between the two semantic valuations (that last condition is
    `agree_off r`).

    WHAT IS PROVED HERE IS MATHEMATICS, NOT AN IMPLEMENTATION CLAIM.  The
    theorems below say that a certain QF_BV formula is non-constant in r exactly
    when semantic control dependence N4 holds.  They do NOT say that Z3 decides
    that formula, and they do NOT say that the Java front-end can build it.
    Those are separate evidence tiers, reported outside Isabelle.

    THE LIFT IS THE IMPLEMENTABLE ONE.  The oracle's grammar
    (src/main/java/wev/smt/QfbvConstancyOracle.java, BvParser lines 215-296) has
    NO ternary operator.  So the ITE lift is not modelled as a HOL `if`; it is
    modelled as the arithmetic term the oracle can actually parse,

        (g != 0) * e_t  +  (1 - (g != 0)) * e_f

    which is well-formed in that grammar because a relational node already
    evaluates to the bitvector 0 or 1 (QfbvConstancyOracle lines 197-199,
    PwT_Expr.eval ERel).  P1 is therefore a genuine statement about 32-bit
    machine arithmetic, not a definitional unfolding.

    NO sorry.  NO axiomatization.  Everything is a definition or a theorem.
*)

theory PwT_Control
  imports PwT_Expr
begin

section \<open>Valuations agreeing off a register\<close>

text \<open>
  The two semantic valuations of the criterion differ at most at the controlling
  read's register. "Other reads held equal" in the F0 description is exactly
  this predicate: every register other than \<open>r\<close> carries the same word in both
  valuations.
\<close>

definition agree_off :: "reg \<Rightarrow> env \<Rightarrow> env \<Rightarrow> bool" where
  "agree_off r s1 s2 \<longleftrightarrow> (\<forall>q. q \<noteq> r \<longrightarrow> s1 q = s2 q)"

lemma agree_off_refl [simp]: "agree_off r s s"
  by (simp add: agree_off_def)

lemma agree_off_sym: "agree_off r s1 s2 \<Longrightarrow> agree_off r s2 s1"
  by (simp add: agree_off_def)

text \<open>
  Two valuations agree off \<open>r\<close> precisely when they are a common valuation with
  two different words written into \<open>r\<close>. This is the bridge between the
  quantified form of the criterion (two environments) and the substituted form
  the oracle's Javadoc states (one environment, two witness values); the oracle
  implements the former, its documentation describes the latter.
\<close>

lemma agree_off_iff_update:
  "agree_off r s1 s2 \<longleftrightarrow> (\<exists>s v1 v2. s1 = s(r := v1) \<and> s2 = s(r := v2))"
proof
  assume "agree_off r s1 s2"
  then have "s1 = s1(r := s1 r) \<and> s2 = s1(r := s2 r)"
    by (auto simp: agree_off_def fun_eq_iff)
  then show "\<exists>s v1 v2. s1 = s(r := v1) \<and> s2 = s(r := v2)" by blast
qed (auto simp: agree_off_def)

section \<open>The observable write effect of a two-way branch\<close>

text \<open>
  \<open>effect g et ef\<close> is the value the single write of the F0 branch deposits: the
  true arm's expression when the guard is non-zero, the false arm's otherwise.
  The guard convention (non-zero is true) is the C one the front-end uses.
\<close>

definition effect :: "expr \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> env \<Rightarrow> word32" where
  "effect g et ef s = (if eval g s \<noteq> 0 then eval et s else eval ef s)"

section \<open>Semantic control dependence N4\<close>

definition N4 :: "reg \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> bool" where
  "N4 r g et ef \<longleftrightarrow>
     (\<exists>s1 s2. agree_off r s1 s2 \<and> effect g et ef s1 \<noteq> effect g et ef s2)"

text \<open>Non-constancy of an ordinary expression in a register, same shape.\<close>

definition nonconst :: "reg \<Rightarrow> expr \<Rightarrow> bool" where
  "nonconst r e \<longleftrightarrow> (\<exists>s1 s2. agree_off r s1 s2 \<and> eval e s1 \<noteq> eval e s2)"

section \<open>The ITE lift, inside the oracle's grammar\<close>

definition guard_bit :: "expr \<Rightarrow> expr" where
  "guard_bit g = ERel RNe g (ENum 0)"

definition lift :: "expr \<Rightarrow> expr \<Rightarrow> expr \<Rightarrow> expr" where
  "lift g et ef =
     EBin OAdd
       (EBin OMul (guard_bit g) et)
       (EBin OMul (EBin OSub (ENum 1) (guard_bit g)) ef)"

text \<open>
  The lift stays in the fragment the oracle parses: it introduces exactly one
  relational node (the guard bit) on top of \<open>+\<close>, \<open>-\<close>, \<open>*\<close> and the two arm
  expressions.
\<close>

lemma guard_bit_value: "eval (guard_bit g) s = (if eval g s \<noteq> 0 then 1 else 0)"
  by (simp add: guard_bit_def)

subsection \<open>P1 --- lift correctness\<close>

text \<open>
  Evaluating the ITE-lifted expression equals the branch's observable write
  effect. The content is that 32-bit multiplication by the guard bit and by its
  complement selects the arms exactly; no overflow or wrap-around case escapes,
  because the selector is literally 0 or 1.
\<close>

theorem P1_lift_correct: "eval (lift g et ef) s = effect g et ef s"
proof (cases "eval g s \<noteq> 0")
  case True
  then show ?thesis by (simp add: lift_def effect_def guard_bit_def)
next
  case False
  then show ?thesis by (simp add: lift_def effect_def guard_bit_def)
qed

subsection \<open>P2 --- non-constancy of the lift is exactly N4\<close>

theorem P2_nonconst_iff_N4: "nonconst r (lift g et ef) \<longleftrightarrow> N4 r g et ef"
  by (simp add: nonconst_def N4_def P1_lift_correct)

subsection \<open>P3 --- the two-copy criterion is exact for N4 on F0\<close>

text \<open>
  The two-copy (self-composition) criterion is the query the QF_BV oracle
  actually poses: one shared valuation of the other registers, two independent
  witness words for \<open>r\<close>, asking whether the lifted term can differ. Compare
  QfbvConstancyOracle.compute lines 103-116, which builds \<open>shared\<close> once and
  \<open>reg__1\<close>/\<open>reg__2\<close> separately.
\<close>

definition two_copy :: "reg \<Rightarrow> expr \<Rightarrow> bool" where
  "two_copy r e \<longleftrightarrow>
     (\<exists>s v1 v2. eval e (s(r := v1)) \<noteq> eval e (s(r := v2)))"

lemma two_copy_iff_nonconst: "two_copy r e \<longleftrightarrow> nonconst r e"
  unfolding two_copy_def nonconst_def
  by (metis agree_off_iff_update fun_upd_same fun_upd_triv)

text \<open>
  EXACTNESS. Both directions: the criterion never reports a control dependence
  that N4 denies (soundness) and never misses one N4 asserts (completeness).
  This is the F0 core result.
\<close>

theorem P3_exact_control_on_F0:
  "two_copy r (lift g et ef) \<longleftrightarrow> N4 r g et ef"
  by (simp add: two_copy_iff_nonconst P2_nonconst_iff_N4)

text \<open>
  The two halves spelled out separately, so the report can cite them
  individually.
\<close>

corollary F0_criterion_sound:
  "two_copy r (lift g et ef) \<Longrightarrow> N4 r g et ef"
  by (simp add: P3_exact_control_on_F0)

corollary F0_criterion_complete:
  "N4 r g et ef \<Longrightarrow> two_copy r (lift g et ef)"
  by (simp add: P3_exact_control_on_F0)

text \<open>
  Contrapositive, in the form the classifier uses: the lifted term being
  constant in \<open>r\<close> is exactly the absence of semantic control dependence, i.e.
  a FAKE control edge.
\<close>

corollary F0_fake_iff_constant:
  "\<not> two_copy r (lift g et ef) \<longleftrightarrow> \<not> N4 r g et ef"
  by (simp add: P3_exact_control_on_F0)

subsection \<open>Sanity: the named F0 cases\<close>

text \<open>
  Case A of the integration matrix --- both arms write the same literal. The
  effect is the constant 1, so N4 fails: the control edge is fake.
\<close>

lemma caseA_constant_arms_not_N4:
  "\<not> N4 r g (ENum 1) (ENum 1)"
  by (simp add: N4_def effect_def)

text \<open>Case B --- arms write different literals. N4 holds, provided the guard
  really can be moved by \<open>r\<close>; with guard \<open>r\<close> itself that is immediate.\<close>

lemma caseB_distinct_arms_is_N4:
  "N4 r (EReg r) (ENum 1) (ENum 0)"
  unfolding N4_def effect_def
  by (rule exI[of _ "(\<lambda>_. 0)(r := 1)"], rule exI[of _ "(\<lambda>_. 0)(r := 0)"])
     (simp add: agree_off_def)

text \<open>
  Case D --- semantically equivalent but syntactically nontrivial arms:
  \<open>r XOR r\<close> against the literal 0. Constant, hence fake.
\<close>

lemma caseD_xor_self_arm_not_N4:
  "\<not> N4 r g (EBin OXor (EReg r) (EReg r)) (ENum 0)"
  by (simp add: N4_def effect_def)

text \<open>
  Case E --- both arms write another register \<open>s\<close>. Fake with respect to \<open>r\<close>
  whatever the guard is, because the effect never mentions \<open>r\<close>.
\<close>

lemma caseE_same_arm_not_N4:
  assumes "q \<noteq> r"
  shows "\<not> N4 r g (EReg q) (EReg q)"
  unfolding N4_def effect_def agree_off_def
  using assms by auto

text \<open>
  Case F --- correlated temporary. After substituting \<open>t := r + 1\<close>, the true arm
  writes \<open>(r + 1) - r\<close> and the false arm writes 1. Both are the constant 1, so
  the effect is constant in \<open>r\<close> and the edge is fake. This is where the
  criterion beats a purely syntactic detector, which sees \<open>r\<close> in the true arm.
\<close>

lemma caseF_correlated_temp_not_N4:
  "\<not> N4 r g (EBin OSub (EBin OAdd (EReg r) (ENum 1)) (EReg r)) (ENum 1)"
  by (simp add: N4_def effect_def)

section \<open>The boundary: what the single-value effect abstraction throws away\<close>

text \<open>
  Everything above abstracts a branch by ONE word: the value its single write
  deposits. Gate 3 asks whether that abstraction is strong enough to justify
  deleting a control edge from the jf-coherence relation
  (AxiomaticConsistency.jfCoherence, which is acyclic(sdep \<union> jf)).

  jf edges point from a WRITE EVENT to a read that is justified from it. So what
  jf-coherence can see is not the final value at a location, but the SET OF
  WRITE EVENTS a branch contributes --- any one of which some other thread may
  read from. The rest of this section makes that distinction formal and locates
  exactly where the single-value abstraction stops being faithful.
\<close>

type_synonym loc = string

type_synonym wtrace = "(loc \<times> word32) list"

definition branch_trace :: "expr \<Rightarrow> wtrace \<Rightarrow> wtrace \<Rightarrow> env \<Rightarrow> wtrace" where
  "branch_trace g tt tf s = (if eval g s \<noteq> 0 then tt else tf)"

text \<open>The last value written to a location, or \<open>None\<close> if the arm never writes it.\<close>

definition final_at :: "loc \<Rightarrow> wtrace \<Rightarrow> word32 option" where
  "final_at l t =
     (case filter (\<lambda>p. fst p = l) t of [] \<Rightarrow> None | ps \<Rightarrow> Some (snd (last ps)))"

text \<open>
  Every value the arm makes readable at a location --- i.e. every write event it
  contributes there, which is what jf can target.
\<close>

definition observable_at :: "loc \<Rightarrow> wtrace \<Rightarrow> word32 set" where
  "observable_at l t = {v. (l, v) \<in> set t}"

subsection \<open>Inside F0 the two abstractions coincide\<close>

text \<open>
  An F0 arm is a single write to the fixed location \<open>l\<close>. Under that shape the
  observable set is the singleton of the final value, so nothing is lost: the
  one-word effect abstraction is faithful, and P3 transfers.
\<close>

lemma F0_final_at: "final_at l [(l, v)] = Some v"
  by (simp add: final_at_def)

lemma F0_observable_at: "observable_at l [(l, v)] = {v}"
  by (simp add: observable_at_def)

theorem F0_abstraction_faithful:
  "observable_at l [(l, v)] = {v} \<and> final_at l [(l, v)] = Some v"
  by (simp add: F0_final_at F0_observable_at)

text \<open>
  Consequently, on F0 the two arms expose the same write events exactly when
  their effect values agree --- so constancy of the effect really does mean the
  branch is invisible to jf.
\<close>

theorem F0_observable_iff_effect:
  "observable_at l [(l, vt)] = observable_at l [(l, vf)] \<longleftrightarrow> vt = vf"
  by (simp add: observable_at_def)

subsection \<open>F2 --- multiple writes per arm REFUTES the abstraction\<close>

text \<open>
  The separator. Both arms leave location \<open>x\<close> holding 2, so the FINAL-VALUE
  abstraction reports the branch as having a constant effect --- fake. But the
  true arm additionally makes the value 1 readable at \<open>x\<close>, and the false arm
  does not. A reader in another thread can be justified from that write only
  when the guard is true, i.e. only depending on \<open>r\<close>.

  This is a mechanised refutation, with an explicit witness, of the claim that
  constancy of the observable write effect suffices once an arm may write more
  than once.
\<close>

definition F2_true_arm :: wtrace where
  "F2_true_arm = [(''x'', 1), (''x'', 2)]"

definition F2_false_arm :: wtrace where
  "F2_false_arm = [(''x'', 2)]"

lemma F2_same_final_value:
  "final_at ''x'' F2_true_arm = final_at ''x'' F2_false_arm"
  by (simp add: final_at_def F2_true_arm_def F2_false_arm_def)

lemma F2_observable_true_arm: "observable_at ''x'' F2_true_arm = {1, 2}"
  by (auto simp: observable_at_def F2_true_arm_def)

lemma F2_observable_false_arm: "observable_at ''x'' F2_false_arm = {2}"
  by (auto simp: observable_at_def F2_false_arm_def)

text \<open>\<open>1 \<noteq> 2\<close> at 32 bits, so the two observable sets genuinely differ.\<close>

lemma one_neq_two_word32: "(1::word32) \<noteq> 2"
  by simp

theorem F2_refutes_final_value_abstraction:
  "final_at ''x'' F2_true_arm = final_at ''x'' F2_false_arm
   \<and> observable_at ''x'' F2_true_arm \<noteq> observable_at ''x'' F2_false_arm"
proof -
  have "(1::word32) \<in> observable_at ''x'' F2_true_arm"
    by (simp add: F2_observable_true_arm)
  moreover have "(1::word32) \<notin> observable_at ''x'' F2_false_arm"
    by (simp add: F2_observable_false_arm)
  ultimately have "observable_at ''x'' F2_true_arm \<noteq> observable_at ''x'' F2_false_arm"
    by blast
  then show ?thesis by (simp add: F2_same_final_value)
qed

text \<open>
  Stated as the failure of the implication the integration claim needs: constant
  FINAL value does not imply the branch is unobservable to jf.
\<close>

theorem F2_final_value_constancy_insufficient:
  "\<not> (\<forall>tt tf l. final_at l tt = final_at l tf \<longrightarrow> observable_at l tt = observable_at l tf)"
proof -
  have "final_at ''x'' F2_true_arm = final_at ''x'' F2_false_arm"
    and "observable_at ''x'' F2_true_arm \<noteq> observable_at ''x'' F2_false_arm"
    using F2_refutes_final_value_abstraction by simp_all
  then show ?thesis by blast
qed

subsection \<open>F6 --- an omitted write also escapes the abstraction\<close>

text \<open>
  \<open>if (r) W(y,1) else skip\<close>. The arms differ in whether the write EXISTS at all,
  which the one-word effect abstraction cannot express: there is no word to
  compare against. \<open>final_at\<close> records this as \<open>None\<close>, and the observable sets
  differ, so the edge must be kept --- confirming case C's expected verdict REAL
  on structural, not value, grounds.
\<close>

theorem F6_omitted_write_observable:
  "observable_at ''y'' [(''y'', 1)] \<noteq> observable_at ''y'' []"
  by (auto simp: observable_at_def)

theorem F6_omitted_write_final_differs:
  "final_at ''y'' [(''y'', 1)] \<noteq> final_at ''y'' []"
  by (simp add: final_at_def)

subsection \<open>F7 --- different locations escape it too\<close>

text \<open>
  \<open>if (r) W(x,1) else W(y,1)\<close>. The written VALUE is the same in both arms, so a
  location-blind value abstraction calls it fake; but which location becomes
  readable depends on \<open>r\<close>.
\<close>

theorem F7_different_locations_observable:
  "observable_at ''x'' [(''x'', 1)] \<noteq> observable_at ''x'' [(''y'', 1)]"
  by (auto simp: observable_at_def)

section \<open>Summary of what is and is not established\<close>

text \<open>
  PROVED, with no assumptions beyond the definitions in this file and PwT_Expr:

    P1_lift_correct              the arithmetic ITE lift computes the effect
    P2_nonconst_iff_N4           non-constancy of the lift is exactly N4
    P3_exact_control_on_F0       the two-copy criterion is exact for N4 on F0
    F0_abstraction_faithful      on F0, one write per arm, the value abstraction
                                 loses nothing
    F2_refutes_final_value_abstraction
                                 with two writes in an arm it loses the extra
                                 readable write --- explicit witness
    F6_/F7_ theorems             omitted writes and differing locations likewise
                                 escape a single-value abstraction

  NOT established here, and deliberately not axiomatised:

    * that Z3 decides the QF_BV query (an implementation fact, checked outside);
    * that the Java front-end can BUILD the lift for a given litmus test
      (it cannot, in general --- see the integration report);
    * any statement about jfCoherence as encoded in Java. The F0/F2/F6/F7
      theorems above are about an abstraction of write effects, and they bound
      what such an encoding could possibly justify; they are not a proof about
      the encoder.
\<close>

end
