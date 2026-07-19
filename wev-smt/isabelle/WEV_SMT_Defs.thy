(*  Title:      WEV_SMT_Defs.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    Abstract model underlying Lemma 2 (detector soundness) and Theorem 1
    (WEAKEST consistency rules out thin-air cycles).

    Events are an abstract type. We use typedecl rather than a type variable:
    the oracle predicates below are introduced by axiomatization, and axioms
    over polymorphic constants are easy to get subtly wrong, whereas a
    monomorphic abstract type carries exactly the same proof strength here.
    Nothing in either proof inspects the structure of an event.

    Relations are sets of pairs, as in the paper. We reuse the HOL library's
    acyclic (from Relation.thy), which is already defined as

        acyclic r  <-->  (ALL x. (x, x) ~: r^+)

    that is, literally the definition requested. Redefining it would only risk
    shadowing the library constant and losing the lemmas that come with it.

    Transitive closure is written as the applied constant "trancl R" rather
    than with the superscript-plus notation. It is the same constant, so all
    library lemmas apply; the applied form just avoids any notation or
    character-encoding ambiguity across Isabelle versions.
*)

theory WEV_SMT_Defs
  imports Main
begin

section \<open>Events\<close>

typedecl event

section \<open>The oracle (Z3) and the dependency relation\<close>

text \<open>
  dep is the syntactic dependency relation extracted by the parser: a pair (p, c)
  in dep means event c consumes a value produced by event p.

  expression_constant c p is the semantic ground truth that c's value expression
  does not vary with p's value, i.e. the apparent dependency is fake. The
  implementation cannot observe this directly.

  oracle_unsat c p is what the implementation actually observes: Z3 returned UNSAT
  for the QF_BV constancy query on that pair.

  branch_free c p marks pairs lying in the branch-free arithmetic fragment.

  Engineering assumption 1 (oracle soundness) is Z3's own correctness guarantee,
  and is deliberately not proved here: it is the trust boundary between the
  mechanised argument and the solver. If Z3 reports UNSAT for the constancy query,
  the expression really is constant in the producer's value. An unsound solver
  invalidates Lemma 2 and hence Theorem 1.

  Engineering assumption 2 (completeness on the branch-free fragment): on
  branch-free bitvector arithmetic the constancy query is decidable and Z3
  terminates, so a genuinely constant expression is always detected. This does not
  hold in general -- with branches the detector is deliberately conservative --
  which is why the exactness result below is confined to branch_free.

  The two assumptions are mutually consistent: interpreting both
  expression_constant and oracle_unsat as universally true satisfies each of them,
  so this axiomatization does not collapse into False.
\<close>

axiomatization
  dep :: "(event \<times> event) set" and
  expression_constant :: "event \<Rightarrow> event \<Rightarrow> bool" and
  oracle_unsat :: "event \<Rightarrow> event \<Rightarrow> bool" and
  branch_free :: "event \<Rightarrow> event \<Rightarrow> bool"
where
  oracle_soundness:
    "oracle_unsat c p \<Longrightarrow> expression_constant c p"
and
  oracle_completeness_branch_free:
    "branch_free c p \<Longrightarrow> expression_constant c p \<Longrightarrow> oracle_unsat c p"

section \<open>The two semantic-dependency relations\<close>

text \<open>
  sdep_true is the ideal relation: a syntactic dependency that is genuinely
  semantic. sdep_impl is what WEV-SMT computes: a syntactic dependency the oracle
  did not prove constant. Both are defined rather than axiomatized, which is what
  makes Lemma 2 a theorem instead of a further assumption.
\<close>

definition sdep_true :: "(event \<times> event) set" where
  "sdep_true = {(p, c). (p, c) \<in> dep \<and> \<not> expression_constant c p}"

definition sdep_impl :: "(event \<times> event) set" where
  "sdep_impl = {(p, c). (p, c) \<in> dep \<and> \<not> oracle_unsat c p}"

text \<open>
  Engineering assumption 3: control dependencies are conservatively retained. The
  implementation never asks the oracle about a control dependency; it keeps every
  one of them in sdep_impl unconditionally. This cannot be derived from the
  definitions above, so it stays an axiom. It is consistent (dep_ctrl = {}
  satisfies it) and is used by neither Lemma 2 nor Theorem 1 -- it is recorded
  because the paper states it, and because it is what licenses treating control
  dependencies as semantic without a solver call.
\<close>

axiomatization dep_ctrl :: "(event \<times> event) set" where
  control_conservative: "dep_ctrl \<subseteq> sdep_impl"

definition branch_free_pairs :: "(event \<times> event) set" where
  "branch_free_pairs = {(p, c). branch_free c p}"

section \<open>Consistency predicates\<close>

text \<open>
  An execution is represented by its justified-from relation jf. Theorem 1 depends
  on the execution only through jf, so carrying a richer record would add structure
  that no proof step inspects.

  Coherence-per-location and RMW atomicity are modelled as True. Theorem 1 needs
  only the jf-coherence conjunct, and stubbing the other two keeps that visible:
  the theorem is proved from jf-coherence alone, so it remains valid under any
  later strengthening of the two stubs. Soundness.thy records this explicitly.
\<close>

definition thin_air_cycle :: "(event \<times> event) set \<Rightarrow> bool" where
  "thin_air_cycle jf \<longleftrightarrow> (\<exists>x. (x, x) \<in> trancl (sdep_true \<union> jf))"

definition jfcoherence_consistent :: "(event \<times> event) set \<Rightarrow> bool" where
  "jfcoherence_consistent jf \<longleftrightarrow> acyclic (sdep_impl \<union> jf)"

definition coherence_per_location :: "(event \<times> event) set \<Rightarrow> bool" where
  "coherence_per_location jf \<longleftrightarrow> True"

definition rmw_atomicity :: "(event \<times> event) set \<Rightarrow> bool" where
  "rmw_atomicity jf \<longleftrightarrow> True"

definition weakest_consistent :: "(event \<times> event) set \<Rightarrow> bool" where
  "weakest_consistent jf \<longleftrightarrow>
     jfcoherence_consistent jf \<and> coherence_per_location jf \<and> rmw_atomicity jf"

end
