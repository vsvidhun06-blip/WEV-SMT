# Reproducing the results that sit outside `eval/run_all.py`

`eval/run_all.py` reproduces nineteen steps (corpus, comparisons, audits, scalability,
hierarchy) and writes `eval/results.json`. Three results reported in the paper are
**not** among those nineteen steps and are driven by their own entry points. This file
records exactly how to reproduce them, and exactly what is and is not shipped.

All Java commands assume the pipeline's own build state — run `mvn -q test-compile`
once, then use the same invocation shape `run_all.py` uses:

```
java -Djava.library.path=target/native -cp <deps>:target/classes <MainClass> <args>
```

`run_all.py` builds `<deps>` via `mvn dependency:build-classpath`; see `classpath()`
and `run_java()` in that script.

---

## 1. Hard-expression benchmark — Table 2, §6.2

| | |
|---|---|
| entry point | `wev.smt.cli.HardExprBenchRun` |
| source | `src/main/java/wev/smt/cli/HardExprBenchRun.java` |
| input | `eval/generated-hard-candidates.json` (1,828 retained expressions) |
| output | `eval/generated-hard-pairs.csv` (3,656 rows) |

```
java -Djava.library.path=target/native -cp <deps>:target/classes \
     wev.smt.cli.HardExprBenchRun \
     eval/generated-hard-candidates.json eval/generated-hard-pairs.csv 2 3
```

Arguments are `<candidates.json> <out.csv> <nMin> <nMax>`; the paper uses ring sizes
`n in {2,3}`, giving 1,828 x 2 = 3,656 rows.

Every cell of Table 2 is derived from the output CSV: `required` vs. each of `weakest`,
`folding`, `syntactic`, over all 3,656 rows. Ground truth (`oracle_constant`) is the
QF_BV oracle queried directly on the expression, so neither criterion under test
contributes to its own label.

### Provenance caveat — the candidate list is an input, not a generated artifact

`eval/generated-hard-candidates.json` is **shipped as a committed input**. The
enumerate-and-filter step that produced it — enumerating the two- and three-operator
grammar over atoms `{r, ~r}`, constants `{0,1,2,3,7,255}` and operators `{+,-,*,&,^}`
to 13,880 forms, then keeping the 1,828 whose value at `r=0` is 1 — was run separately
and **is not in this tree**. The paper says so (App. A.4, *Enumeration*). Consequently:

* the **13,880** figure is reported from that run and is not re-derivable here;
* everything downstream of the candidate list *is* re-derivable, from the committed
  JSON and the command above.

Do not describe this benchmark as end-to-end reproducible from the artifact alone.

## 2. Matched-pair ring sweep — App. A.4, *Earlier sweeps*

| | |
|---|---|
| entry point | `wev.smt.cli.GeneratedPairsRun` |
| source | `src/main/java/wev/smt/cli/GeneratedPairsRun.java` |
| outputs | `eval/generated-pairs.csv`, `eval/generated-pairs-folding.csv`, `eval/generated/*.litmus` |
| summary | `eval/generated-pairs-summary.md` |

```
java -Djava.library.path=target/native -cp <deps>:target/classes \
     wev.smt.cli.GeneratedPairsRun \
     eval/generated-pairs.csv eval/generated 2 6
```

Arguments are `<out.csv> <dumpDir> <nMin> <nMax>`. This sweep generates its own
programs, so it *is* end-to-end reproducible. 1,600 generated / 1,440 valid / 1,015
discriminating; 160 excluded as oracle-undecided.

## 3. Unrestricted-abstraction synthesis — §3.3 and App. A.5

| | |
|---|---|
| scripts | `research/claimD/claimD_synth.py`, `claimD_restricted.py`, `claimD_probe.py` |
| outputs | `research/claimD/claimD-synthesis.json`, `claimD-counts.json`, `claimD-restricted.json` |

```
python research/claimD/claimD_synth.py
python research/claimD/claimD_restricted.py
python research/claimD/claimD_probe.py
```

Pure Python, no solver. `claimD_synth.py` sweeps `w in {1,2,3}` x `depth in {2,3}` x
`|Reg| in {1,2,3,4,8,12}` (36 configurations) and is the source of every number in
App. A.5:

* every `w=1` configuration succeeds, `|Reg|=1` included;
* at `w=2` the smallest success is `|Reg|=4` (1, 2, 3 fail by pigeonhole);
* at `w=3` the smallest success is `|Reg|=8` at depth 2 and `|Reg|=12` at depth 3;
* at `w=3`, depth 3, `|Reg|=8`: 171 signatures fit inside `2^8 = 256`, but synthesis
  still fails — `"g-slot needs 305 states > capacity 256"`.

This sweep *is* end-to-end reproducible.

## 4. Dat3M/LKMM external dependency baseline — §6.2 and App. A.4

| | |
|---|---|
| entry point | `python eval/dat3m_lkmm_hardbench.py` |
| source | `eval/dat3m_lkmm_hardbench.py` |
| input | `eval/generated-hard-pairs.csv` (the 3,656 rows of §1) + a built Dat3M under `eval/corpus/Dat3M` |
| outputs | `eval/dat3m-lkmm-hardbench.csv` (1,350), `eval/dat3m-lkmm-grounded-rings.csv` (390), `eval/dat3m-lkmm-excluded.csv` (2,306) |
| controls | `eval/dat3m-lkmm-controls.txt` |
| provenance | `eval/dat3m-lkmm-environment.txt` |
| full guide | **`eval/dat3m-lkmm-README.md`** |

```
cd eval/corpus/Dat3M && mvn clean install -DskipTests -pl dartagnan -am   # once
python eval/dat3m_lkmm_hardbench.py                                       # ~10 min
```

Backs the sentence "Dat3M v4.4.1 reproduces that row's verdicts exactly (1,350/1,350) and
forbids all 390 grounded rings WEAKEST admits". The driver re-renders each ring into Dat3M's
function-body C litmus syntax, runs five calibration controls that fix the PASS/FAIL reading,
then scores all 3,656 instances — 1,350 representable, 2,306 rejected by the LKMM parser for
using `~`. It **aborts without writing** if a control disagrees or any expected count fails to
reproduce. This run *is* end-to-end reproducible from the shipped tree, given a built Dat3M.
