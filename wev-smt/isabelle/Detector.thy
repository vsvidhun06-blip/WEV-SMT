(*  Title:      Detector.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    Lemma 2 (detector soundness) and the branch-free exactness result.

    A note on proof style. These proofs are deliberately written as a definition
    unfolding followed by a single automated step, rather than as structured Isar
    derivations. An earlier structured version destructured the pair with

        obtain p c where "pc = (p, c)" using surj_pair by blast

    which does not terminate here: with surj_pair supplied as a fact, blast's search
    over an uninterpreted event type diverges. Unfolding the set-comprehension
    definitions first lets auto do the pair splitting itself, and each proof then
    closes in well under a second.
*)

theory Detector
  imports WEV_SMT_Defs
begin

section \<open>Containment in the syntactic dependency relation\<close>

text \<open>
  The paper lists this as an assumption. It is not one: both relations are defined
  as subsets of dep, so containment falls out of the definitions. Stating it as a
  lemma rather than an axiom shrinks the trust base, which is the point of
  mechanising in the first place -- an assumption that is actually a theorem should
  be a theorem.
\<close>

lemma dep_containment: "sdep_impl \<subseteq> dep \<and> sdep_true \<subseteq> dep"
  unfolding sdep_impl_def sdep_true_def by blast

lemma sdep_impl_subset_dep: "sdep_impl \<subseteq> dep"
  using dep_containment by simp

lemma sdep_true_subset_dep: "sdep_true \<subseteq> dep"
  using dep_containment by simp

section \<open>Lemma 2: detector soundness\<close>

text \<open>
  The implemented detector never drops a genuine semantic dependency. The argument
  is the contrapositive of oracle soundness: a truly non-constant expression cannot
  have been reported UNSAT, so the pair survives into sdep_impl.

  Note the direction. Soundness of the detector is what Theorem 1 needs, and it
  follows from soundness of the oracle alone -- completeness is not required, so a
  solver that times out or gives up still leaves Theorem 1 intact. It merely
  over-approximates sdep_impl, which is the safe direction.
\<close>

theorem detector_soundness: "sdep_true \<subseteq> sdep_impl"
  unfolding sdep_true_def sdep_impl_def
  using oracle_soundness by auto

section \<open>Detector exactness on the branch-free fragment\<close>

text \<open>
  With completeness available, the over-approximation collapses: on branch-free
  arithmetic the implemented relation coincides with the ideal one. This is the
  mechanised counterpart of the empirical result that sdep_impl = sdep_true on the
  branch-free corpus fragment.
\<close>

theorem detector_exactness:
  "sdep_impl \<inter> branch_free_pairs = sdep_true \<inter> branch_free_pairs"
  unfolding sdep_impl_def sdep_true_def branch_free_pairs_def
  using oracle_soundness oracle_completeness_branch_free by auto

end
