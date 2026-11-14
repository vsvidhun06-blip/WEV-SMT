# Isabelle/HOL development

Four sessions, built with Isabelle2024. They depend on HOL and HOL-Library only (no AFP entries).

| Session | Directory | Contents |
|---|---|---|
| `WEV_SMT` | `.` | `WEV_SMT_Defs`, `Detector`, `Soundness`: Lemma 2 (detector soundness), detector exactness on branch-free code, Theorem 1 (WEAKEST consistency rules out thin-air cycles). |
| `WEV_SMT_PwT` | `pwt/` | The 32-bit branch-free expression language of the constancy oracle, the concrete constancy predicate, a model that discharges the axiomatised assumptions of `WEV_SMT_Defs`, per-site R4b results and the program-level lifting. |
| `WEV_SMT_Rev` | `rev/` | FSEN 2027 revision material: version-aware closure, ring-weighted (affine) closure, block summaries, Cousot ordered composition equals `vdep`, the fixed-initial-state bridge, and `Rev_Check`, which checks the 73 facts the FSEN paper cites. |
| `WEV_SMT_TL` | `tl/` | Exactness of the version-aware closure on the taint-linear fragment, with `TL_Check` (31 theorems) and `TL_Ancestry`. |

`ROOT` defines the first two sessions; `ROOTS` adds `rev` and `tl`.

## Build

From this directory:

```
isabelle build -d . WEV_SMT WEV_SMT_PwT WEV_SMT_Rev WEV_SMT_TL
```

Add `-c` to force a clean rebuild and `-v` for progress. On Windows, run it from the Cygwin shell that ships with Isabelle. The `Rev_Check` and `TL_Check`/`TL_Ancestry` theories write `rev/CHECK-RESULT.txt`, `tl/CHECK-RESULT.txt` and `tl/ANCESTRY-RESULT.txt` (ignored by Git). A good run reports `oracles over 73 theorems: none`, `oracles over 31 theorems: none` and `ancestry: OK` for both checks.

## What the axioms mean

`WEV_SMT_Defs` keeps three `axiomatization` blocks on purpose. They state engineering assumptions about the SMT oracle (sound, and complete on branch-free bitvector arithmetic) and about control dependencies being kept conservatively. Lemma 2 and Theorem 1 in `WEV_SMT` are proved relative to those assumptions. `pwt/PwT_Model.thy` then interprets the same signature in a locale with the concrete BV32 predicate, where the two oracle assumptions become theorems. The ancestry checks confirm that the `WEV_SMT_Rev` and `WEV_SMT_TL` facts do not depend on `WEV_SMT_Defs`, `Detector`, `Soundness` or `PwT_Model`.

These theories reason about mathematical models of the dependency relations and of WEAKEST consistency. They are not generated from, or linked to, the Java code. The correspondence between the Java encoder and these definitions is argued on paper and tested, not mechanised.

## Provenance

`ROOT`, `*.thy` and `pwt/` come from the corrected-v1 TACAS 2027 #4549 base artifact; `rev/` from `fsen27-41-addon.zip`; `tl/` from `fsen27-41-tl-realcode.zip` (both archived under `../../fsen2027-41/`). The copies of the base theories inside the later archives are identical to these.
