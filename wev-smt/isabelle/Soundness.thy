(*  Title:      Soundness.thy
    Author:     WEV-SMT artifact (TACAS 2027)

    Theorem 1: WEAKEST consistency rules out thin-air cycles.
*)

theory Soundness
  imports Detector
begin

section \<open>Theorem 1: no thin-air cycle in a WEAKEST-consistent execution\<close>

text \<open>
  The shape of the argument: WEAKEST consistency gives acyclicity of
  sdep_impl Un jf. A thin-air cycle is a cycle in sdep_true Un jf. Since Lemma 2
  gives sdep_true <= sdep_impl, that smaller relation embeds in the larger one, and
  transitive closure is monotone -- so the thin-air cycle would also be a cycle in
  sdep_impl Un jf, contradicting acyclicity.

  The direction of Lemma 2 is exactly what makes this work. Checking against the
  over-approximation sdep_impl is sound for ruling out cycles in the ideal
  sdep_true; had the detector under-approximated, a genuine thin-air cycle could
  hide in the gap and the theorem would be false.
\<close>

theorem soundness:
  assumes wc: "weakest_consistent jf"
  shows "\<not> thin_air_cycle jf"
proof
  assume tac: "thin_air_cycle jf"

  \<comment> \<open>From WEAKEST consistency, extract the jf-coherence conjunct.\<close>
  from wc have "jfcoherence_consistent jf"
    by (simp add: weakest_consistent_def)
  then have acy: "acyclic (sdep_impl \<union> jf)"
    by (simp add: jfcoherence_consistent_def)

  \<comment> \<open>A thin-air cycle is a self-loop in the transitive closure of sdep_true Un jf.\<close>
  from tac obtain x where cyc: "(x, x) \<in> trancl (sdep_true \<union> jf)"
    by (auto simp: thin_air_cycle_def)

  \<comment> \<open>Lemma 2 lifts that cycle into the relation the checker actually constrains.
      Both steps apply their rule directly rather than going through a search tactic:
      given trancl_mono as an intro rule, blast will hunt for an arbitrary relation r
      with r <= sdep_impl Un jf and (x, x) in r^+, which does not terminate here.\<close>
  have sub: "sdep_true \<union> jf \<subseteq> sdep_impl \<union> jf"
    by (rule Un_mono[OF detector_soundness subset_refl])
  have in_impl: "(x, x) \<in> trancl (sdep_impl \<union> jf)"
    by (rule trancl_mono[OF cyc sub])

  \<comment> \<open>Which contradicts acyclicity.\<close>
  from acy have "(x, x) \<notin> trancl (sdep_impl \<union> jf)"
    by (simp add: acyclic_def)
  with in_impl show False by simp
qed

text \<open>
  Corollary: the contrapositive, which is how the checker is actually used --
  exhibiting a thin-air cycle witnesses inconsistency.
\<close>

corollary thin_air_cycle_implies_inconsistent:
  "thin_air_cycle jf \<Longrightarrow> \<not> weakest_consistent jf"
  using soundness by blast

text \<open>
  Theorem 1 uses only the jf-coherence conjunct of weakest_consistent, so it holds
  for the bare jf-coherence predicate too. Recording this makes the stubbed
  coherence_per_location and rmw_atomicity provably irrelevant to the result rather
  than merely unused.
\<close>

lemma soundness_from_jfcoherence_only:
  assumes "jfcoherence_consistent jf"
  shows "\<not> thin_air_cycle jf"
proof -
  from assms have "weakest_consistent jf"
    by (simp add: weakest_consistent_def coherence_per_location_def rmw_atomicity_def)
  then show ?thesis by (rule soundness)
qed

end
