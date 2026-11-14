(* Oracle and ancestry check for every Isabelle fact the paper cites (73 facts):
   18 from session WEV_SMT_PwT, 11 from PwT_Versioned, 17 from PwT_Affine, 6 from PwT_Block, 10 from PwT_Correspondence
   and 11 from PwT_Remarks. None may depend on an oracle
   (sorry / skip_proof), and no theory involved may have the axiomatised WEV_SMT_Defs,
   or the theories built on it, as an ancestor. *)
theory Rev_Check
  imports PwT_Versioned PwT_Affine PwT_Block PwT_Correspondence PwT_Remarks WEV_SMT_PwT.PwT_Capacity WEV_SMT_PwT.PwT_EventBridge WEV_SMT_PwT.PwT_Control
begin

ML \<open>
  val names = ["WEV_SMT_PwT.PwT_Expr", "WEV_SMT_PwT.PwT_Constancy", "WEV_SMT_PwT.PwT_R4b",
               "WEV_SMT_PwT.PwT_Program", "WEV_SMT_PwT.PwT_Granularity", "WEV_SMT_PwT.PwT_ReadRestricted",
               "WEV_SMT_PwT.PwT_Capacity", "WEV_SMT_PwT.PwT_EventBridge", "WEV_SMT_PwT.PwT_Control",
               "WEV_SMT_Rev.PwT_Versioned", "WEV_SMT_Rev.PwT_Affine", "WEV_SMT_Rev.PwT_Block", "WEV_SMT_Rev.PwT_Correspondence",
               "WEV_SMT_Rev.PwT_Remarks"];
  fun is_defs s = String.isSuffix "WEV_SMT_Defs" s orelse String.isSuffix "PwT_Model" s
                  orelse String.isSuffix "Soundness" s orelse String.isSuffix "Detector" s;
  val thys = Theory.ancestors_of @{theory} |> filter (fn t => member (op =) names (Context.theory_long_name t));
  val found = map Context.theory_long_name thys;
  val bad = filter (fn t => exists (is_defs o Context.theory_long_name) (Theory.ancestors_of t)) thys
            |> map Context.theory_long_name;
  val _ = if length found = length names then () else error ("theories not found: " ^ commas (subtract (op =) found names));
  val thms = [
    @{thm PwT_Granularity.boolean_abstraction_too_coarse},
    @{thm PwT_Granularity.no_function_of_alpha_computes_prog_dep},
    @{thm PwT_ReadRestricted.A_read_insufficient},
    @{thm PwT_Capacity.ctx_equiv_iff_sem_equiv},
    @{thm PwT_Capacity.exact_code_iff_refines_sem},
    @{thm PwT_Capacity.affine_capacity_inj},
    @{thm PwT_Capacity.unrestricted_beta_exact},
    @{thm PwT_Capacity.denotation_code_exact},
    @{thm PwT_Capacity.A_read_insufficient_via_capacity},
    @{thm PwT_Program.prog_dep_sound},
    @{thm PwT_Program.sitewise_not_complete},
    @{thm PwT_Program.sitewise_not_complete_single_read},
    @{thm PwT_Program.pipeline_exact},
    @{thm PwT_EventBridge.esdep_iff_label_non_constant},
    @{thm PwT_EventBridge.esdep_embed_iff_prog_dep},
    @{thm PwT_EventBridge.event_summary_insufficient},
    @{thm PwT_EventBridge.event_witnesses},
    @{thm PwT_Control.P3_exact_control_on_F0},
    @{thm vdep_sound}, @{thm vdep_le_sites}, @{thm vdep_exact_sri},
    @{thm sri_needed(1)}, @{thm sri_needed(2)}, @{thm sri_needed(3)}, @{thm sri_needed(4)},
    @{thm sites_not_exact_sri(1)}, @{thm sites_not_exact_sri(2)},
    @{thm sites_not_exact_sri(3)}, @{thm sites_not_exact_sri(4)},
    @{thm affine_nc}, @{thm wdep_shift}, @{thm wdep_exact}, @{thm wdep_le_vdep}, @{thm wdep_summary},
    @{thm wdep_witnesses(1)}, @{thm wdep_witnesses(2)}, @{thm wdep_witnesses(3)}, @{thm wdep_witnesses(4)},
    @{thm affine_ctx_equiv}, @{thm affine_ctx_sep(1)}, @{thm affine_ctx_sep(2)}, @{thm affine_ctx_sep(3)},
    @{thm aff_exact_iff}, @{thm aff_capacity(1)}, @{thm aff_capacity(2)}, @{thm aff_capacity(3)},
    @{thm block_equiv_iff}, @{thm exact_block_refines_run}, @{thm run_exact_block},
    @{thm dep_relation_not_exact(1)}, @{thm dep_relation_not_exact(2)}, @{thm dep_relation_not_exact(3)},
    @{thm crel_iff_vdep}, @{thm overwrite_cousot_kills(1)}, @{thm overwrite_cousot_kills(2)}, @{thm overwrite_cousot_kills(3)},
    @{thm esdep_iff_ex_esdep_at}, @{thm label_at_store_val}, @{thm esdep_at_iff_label_at_non_constant},
    @{thm kprog_regression(1)}, @{thm kprog_regression(2)}, @{thm kprog_regression(3)},
    @{thm affine_capacity_card}, @{thm aff_capacity_classes(1)}, @{thm aff_capacity_classes(2)}, @{thm aff_capacity_classes(3)},
    @{thm vdep_exact_single}, @{thm sri_not_necessary(1)}, @{thm sri_not_necessary(2)}, @{thm sri_not_necessary(3)},
    @{thm vdep_masking(1)}, @{thm vdep_masking(2)}, @{thm vdep_masking(3)}];
  val ors = Thm_Deps.all_oracles thms;
  fun stmt th = YXML.content_of (Syntax.string_of_term @{context} (Thm.prop_of th));
  val out = Path.append (Resources.master_directory @{theory}) (Path.basic "CHECK-RESULT.txt");
  val _ = File.write out (cat_lines (
            ["theories checked (" ^ string_of_int (length found) ^ "): " ^ commas found,
             "ancestry: " ^ (if null bad then "OK (no WEV_SMT_Defs/Detector/Soundness/PwT_Model ancestor)" else "FAILED " ^ commas bad),
             "oracles over " ^ string_of_int (length thms) ^ " theorems: " ^ (if null ors then "none" else "PRESENT"), ""]
            @ map (fn th => stmt th ^ "\n") thms));
  val _ = if null bad andalso null ors andalso length thms = 73 then writeln "REV-CHECK OK"
          else error "REV-CHECK FAILED";
\<close>

end
