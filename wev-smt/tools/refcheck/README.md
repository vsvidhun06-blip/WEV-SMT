# Reference checker for composed-label dependency verdicts

Python tools from `fsen27-41-addon.zip` (archived under `../../../fsen2027-41/`), copied unchanged. They check the dependency decisions that `wev.smt.cli.ComposedLabelRun` (mode C) records, using code that is separate from the Java prototype.

| File | What it does |
|---|---|
| `refcheck.py` | Own C-litmus reader, expression parser, symbolic executor and bitvector evaluator. A "varies" verdict needs a concrete witness re-evaluated without a solver. A "constant" verdict comes from Z3 over the script's own encoding, corroborated by 8-bit exhaustive and 32-bit random evaluation. |
| `expr_equiv.py` | For every data edge both tools parse, checks that the prototype's composed expression and the reference's are the same 32-bit function (Z3 plus 2,000 random evaluations). |
| `affine_mode.py` | Mode L (ring-weighted closure), compared with the reference. |
| `semcheck.py` | Solver-free validator. A CONST verdict counts as proved only when exact rewriting removes the load symbol; otherwise it is corroborated by search, not proved. |
| `test_semcheck.py` | Self-test for `semcheck.py` (fixed cases, a randomised soundness test of the rewriting, mutation sensitivity). |

Requirements: Python 3.11 and the `z3-solver` package (tested with 5.0.0).

## Example

From `wev-smt/`, after `mvn test` has compiled the classes and copied the Z3 natives:

```
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
# Windows uses ';' as the classpath separator, Linux and macOS use ':'
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
  wev.smt.cli.ComposedLabelRun <out>/java <list.txt> eval/examples/fsen27-41
python -I tools/refcheck/refcheck.py <out>/java eval/examples/fsen27-41 <list.txt> <out>/ref
python -I tools/refcheck/expr_equiv.py <out>/java eval/examples/fsen27-41 <list.txt> <out>/eq
python -I tools/refcheck/test_semcheck.py
```

`<list.txt>` holds paths relative to `eval/examples/fsen27-41`, one per line (for example `diag/LB-real.litmus`).

## Limits

- `refcheck.py` parses C-syntax litmus only, and rejects address, control, RMW and page-table inputs instead of deciding them.
- It uses the same Z3 as the prototype for "constant" verdicts and for the equality proofs, so those parts are not solver-independent. The random and exhaustive evaluations are solver-free.
- Agreement between the prototype and these scripts supports the verdicts; it does not prove either one correct.
- The add-on's full driver (`run_all.sh`) and its claim checker (`check_numbers.py`) are not copied here. They verify one complete run against hashed archives and stay in `fsen27-41-addon.zip`.
