# Example inputs

| Directory | Source |
|---|---|
| `diag/`, `jmm/`, `leaky-semicolon/`, `paper/`, `weakestmo/` | `inputs/examples/` of the corrected-v1 TACAS 2027 #4549 base artifact. Example programs transcribed from the cited publications (Weakestmo, PwT / "The Leaky Semicolon", the Java Memory Model causality tests). `paper/LB-real`, `LB-fake-xor` and `LB-fake-xor-cycle` are the project's own worked example. |
| `fsen27-41/diag/` | Seven LB programs and fourteen unit cases from `fsen27-41-addon.zip`. `LB-copy.litmus` is a diagnostic input added during the 2026-10 correctness investigation, not original evidence. |
| `fsen27-41/regress/` | Three initial-register regressions (`k` declared 0, declared 1, undeclared). |
| `fsen27-41/memhop/`, `fsen27-41/memhop-probe/` | The memory-hop contrast and its coherence probe. Kept out of every corpus count in the add-on. |

`LitmusParserTest` and `PaperExampleTest` read `paper/`. Everything here is copied unchanged.
