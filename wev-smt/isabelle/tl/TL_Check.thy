(* Oracle check for the facts of PwT_TaintLinear (real-code investigation, 2026-11-11).
   None may depend on an oracle (sorry / skip_proof). Writes CHECK-RESULT.txt next to this file. *)
theory TL_Check
  imports PwT_TaintLinear
begin

ML \<open>
  val thms = [@{thm taint_sound(1)}, @{thm taint_sound(2)}, @{thm taint_sound(3)},
    @{thm vdep_imp_tainted}, @{thm tl_exact}, @{thm tl_frag_exact}, @{thm sri_exact_via_tl},
    @{thm tl_needs_linear(1)}, @{thm tl_needs_linear(2)}, @{thm tl_needs_linear(3)}, @{thm tl_needs_linear(4)},
    @{thm tl_needs_full(1)}, @{thm tl_needs_full(2)}, @{thm tl_needs_full(3)}, @{thm tl_needs_full(4)},
    @{thm tl_needs_full(5)}, @{thm tl_needs_full(6)},
    @{thm tl_needs_uniform(1)}, @{thm tl_needs_uniform(2)}, @{thm tl_needs_uniform(3)},
    @{thm tl_needs_uniform(4)}, @{thm tl_needs_uniform(5)},
    @{thm inj_in_add_side}, @{thm inj_in_sub_side}, @{thm inj_in_sub_side'}, @{thm inj_in_xor_side},
    @{thm inj_in_un}, @{thm inj_in_imp_unc}, @{thm unc_and_num}, @{thm unc_mul_num}, @{thm unc_eq_side}];
  val ors = Thm_Deps.all_oracles thms;
  fun stmt th = YXML.content_of (Syntax.string_of_term @{context} (Thm.prop_of th));
  val out = Path.append (Resources.master_directory @{theory}) (Path.basic "CHECK-RESULT.txt");
  val _ = File.write out (cat_lines (
            ["oracles over " ^ string_of_int (length thms) ^ " theorems: " ^ (if null ors then "none" else "PRESENT"), ""]
            @ map (fn th => stmt th ^ "\n") thms));
  val _ = if null ors andalso length thms = 31 then writeln "TL-CHECK OK" else error "TL-CHECK FAILED";
\<close>

end
