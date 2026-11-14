(* Ancestry check for PwT_TaintLinear (FSEN 2027 #41 revision, 2026-11-12).
   PwT_TaintLinear must not have the axiomatised WEV_SMT_Defs, or the theories built on it
   (Detector, Soundness, PwT_Model), as an ancestor; the same predicate as Rev_Check.
   Control: the predicate must flag PwT_Model, which does import WEV_SMT_Defs.
   Writes ANCESTRY-RESULT.txt next to this file; the build fails if either check fails. *)
theory TL_Ancestry
  imports PwT_TaintLinear
begin

ML \<open>
  fun is_defs s = String.isSuffix "WEV_SMT_Defs" s orelse String.isSuffix "PwT_Model" s
                  orelse String.isSuffix "Soundness" s orelse String.isSuffix "Detector" s;
  val tl = the (find_first (fn t => Context.theory_long_name t = "WEV_SMT_TL.PwT_TaintLinear")
                             (Theory.ancestors_of @{theory}));
  val anc = map Context.theory_long_name (Theory.ancestors_of tl);
  val bad = filter is_defs anc;
  val ctrl = Thy_Info.get_theory "WEV_SMT_PwT.PwT_Model";
  val ctrl_hits = filter is_defs (map Context.theory_long_name (Theory.ancestors_of ctrl));
  val out = Path.append (Resources.master_directory @{theory}) (Path.basic "ANCESTRY-RESULT.txt");
  val _ = File.write out (cat_lines
            ["theory: " ^ Context.theory_long_name tl,
             "ancestors (" ^ string_of_int (length anc) ^ "): " ^ commas anc,
             "ancestry: " ^ (if null bad then "OK (no WEV_SMT_Defs/Detector/Soundness/PwT_Model ancestor)"
                             else "FAILED " ^ commas bad),
             "control PwT_Model flagged ancestors: " ^ commas ctrl_hits]);
  val _ = if null bad andalso not (null ctrl_hits) then writeln "TL-ANCESTRY OK"
          else error "TL-ANCESTRY FAILED";
\<close>

end
