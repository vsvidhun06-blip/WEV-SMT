# Findings — Task 0b: herd7 X86 SC-vs-TSO test counts

**The paper's numbers 50 and 46 are real and appear in the data, but the stated reason for
the gap is wrong, and "50 parsed X86 tests" is off by one.**

The X86 set in `eval/herd7-results.csv` (and the join in `eval/herd7-symbolic-results.csv`,
whose `herd_sc`/`herd_tso` columns are copied verbatim from it — `SymbolicRfRun.java:193–199`)
contains **51** files, not 50. Every one of those 51 was run by herd7 under *both* models:
`herd7_baseline.md:38` records that all files were run under `sc.cat` and "every **X86** file
additionally under `x86tso.cat`". There is therefore **no such thing as a "TSO litmus
variant"** in this experiment — no test carries or lacks one, and 50-vs-46 is not a
difference in which tests exist. Both numbers are *comparability* counts: the number of X86
files on which **herd7 itself** emitted a usable verdict, which differs between the two model
runs because herd7 errored on a different number of files each time. `herd7_baseline.md:40`
defines the encoding: `Never ⇒ FORBIDDEN`, `Sometimes/Always ⇒ ALLOWED`, and a missing or
negated `Observation` line ⇒ `ERROR`. The breakdown:

| | files | herd7 ERROR | comparable | FORBIDDEN | ALLOWED |
|---|---|---|---|---|---|
| SC (`sc.cat`) | 51 | 1 | **50** | 46 | 4 |
| TSO (`x86tso.cat`) | 51 | 5 | **46** | 22 | 24 |

This matches `herd7_baseline.md:57` and `:68` exactly. The single SC error is
`doc/SB-labelled.litmus` (it errors under both models). The four files that are comparable
under SC but not under TSO — the paper's "SC-only" 4 — are exactly:

```
herd/tests/instructions/X86_64/A000.litmus
herd/tests/instructions/X86_64/A001.litmus
herd/tests/instructions/X86_64/A002.litmus
herd/tests/instructions/X86_64/A008.litmus
```

**What actually distinguishes them:** they are the only four files in the X86 set whose
litmus header declares arch `X86_64` rather than `X86` (the other 47 all begin `X86 <name>`).
They are also single-threaded instruction-coverage tests with `forall` conditions rather
than concurrency litmus tests — A000 is a bare `NOP`, A001 and A002 probe the `rip`/`cs`
registers, A008 is `and %eax,%eax`. herd7 evaluated all four under `sc.cat` and produced no
`Observation` line for any of them under `x86tso.cat`.

**The precise cause of herd7's TSO failure is not determinable from this repository.** No
raw herd7 output for that run is committed (`eval/logs/_herd7.log` and `_herd7.stdout` are
from the unrelated `CorpusValidation` job — 4324 files, five WEV models — not from the
`sc.cat`/`x86tso.cat` run), and `herd7_baseline.md:37` points at an external driver
`~/run_herd7.sh` that is outside the tree. The `X86_64`-vs-`X86` arch split is a perfect
correlate and the obvious candidate explanation, but confirming it requires re-running
herd7. The paper should not assert a mechanism it cannot cite.

Two further facts worth knowing before writing the sentence:

- The four are *also* exactly the four rows with `herd_sc = ALLOWED`; all 46 genuine
  X86-arch tests are `FORBIDDEN` under SC. Since these four are `forall` tests, the
  `Never ⇒ FORBIDDEN` mapping means something different for them than for the `exists`
  tests that make up the rest of the set — `ALLOWED` here reflects an assertion holding,
  not a relaxed outcome being observable. They are not comparable data points with the
  other 46 even under SC, which arguably makes the honest SC denominator 46 as well.
- The corpus holds **13** files under `herd/tests/instructions/X86_64/`; only these 4 reach
  the 697-file filelist at all. The other 9 were dropped upstream (WEV parse failures), so
  the X86_64 material in the experiment is already a non-representative remnant.

---

## Replacement wording

> Of the 51 X86 files in the cross-check set, herd7 produced a usable verdict for 50 under
> `sc.cat` and for 46 under `x86tso.cat`; every file was submitted to both models. The four
> files comparable under SC but not TSO (`X86_64/A000`, `A001`, `A002`, `A008`) are the only
> `X86_64`-arch entries in the set — single-threaded instruction-coverage tests with `forall`
> conditions — and herd7 emitted no `Observation` line for them under `x86tso.cat`.

Do not write "carry a TSO litmus variant" or "SC-only"; both describe a test-selection
mechanism that does not exist here.
