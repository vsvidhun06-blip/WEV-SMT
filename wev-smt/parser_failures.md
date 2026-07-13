# Parser-failure categorization — defusing the 24.3% coverage criticism (risk #4)

Of the **12,324** corpus files processed by the most recent §6.2 run, **2,998** validated
(WEAKEST verdict produced) — that is the **24.3% coverage** the criticism targets
(`2998/12324 = 24.3%`). The complement, **75.7%** (`9,326` files), did not validate:
**7,461** syntax-skips + **1,865** parse-errors. This note categorizes those 9,326 files
and asks whether the missing coverage is orthogonal to the coherence/dependency/
jf-coherence reasoning, or whether it biases the soundness result toward easy tests.

## Method & provenance

The most recent run's per-file reasons live in `eval/corpus-validation-{herd7,dat3m}.csv`
(`status` + `note` columns), which reconcile exactly to the §6.2 numbers
(OK 697+2301=2,998; SKIP 3,341+4,120=7,461; PARSE_ERROR 286+1,579=1,865). The CSV `note`
is clipped to 120 chars, which loses the offending token on long Dat3M paths, so I
**re-ran the frozen `LitmusParser` over every failing file** with a logging-only harness
(`src/main/java/wev/smt/ablation/ParserFailureLog.java` — calls `LitmusParser.parse`
exactly as `CorpusValidation` does, records the untruncated exception kind, message, and
first source line; **the parser is not modified**). Raw output:
`eval/skip-reasons.tsv`, `eval/pe-reasons.tsv`. The harness reproduced the original
verdicts (7,457/7,461 skips and 1,865/1,865 errors; 4 marginal empty-`exists` files now
parse — negligible).

Each file is then tagged with (a) its **failure feature** (from the untruncated offending
instruction / exception) and (b) its **test family** from the litmus name —
`DEP` = `+addr/+data/+ctrl/+dep`, `COH` = `Co**`/`+pos` (SC-per-location), else `other` —
to measure whether the failures are *content-orthogonal* to the coherence / dependency /
jf-coherence reasoning the SMT back-end performs.

### The orthogonality question has two parts

* **Trigger orthogonality** — is the *feature that makes the parser fail* unrelated to the
  coherence/dependency/jf-coherence axioms? For essentially every bucket **yes**: the
  failures are all front-end (lexer / preamble) limitations; the SMT back-end that
  implements those axioms is never reached.
* **Content orthogonality** — are the *failed files* a representative sample, or do they
  over-represent coherence/dependency tests? Here the answer is **partly no**, and that is
  the caveat the soundness result needs (see §"Risk note").

Baseline family mix: validated set is **3.5% DEP / 6.3% COH**; the skip set is
**16.8% DEP / 10.2% COH** and the parse-error set is **0.9% DEP / 28.6% COH**. Dependency
tests are ~5× over-represented among skips and coherence tests are ~4.5× over-represented
among parse-errors — so the buckets are *not* uniformly orthogonal in content.

## Syntax-skips — feature buckets (sum = 7,461)

| Bucket (failure feature) | n | % skips | Example | Orthogonality verdict | Justification (one sentence) |
|---|---|---|---|---|---|
| **ALU / register-setup syntax** (RISC-V `ori/addi…` 2,146; ARM `mov/lsr/adds…` 638) | 2,784 | 37.3% | `RISCV/.../3.LB+addr+ctrl+data.litmus` (skips on `ori x8,x0,1`) | **Trigger-orthogonal but content-BIASED** | The parser models loads/stores/RMW/fences, not general register arithmetic; but 22.7% of these are DEP and 19.2% COH tests whose address/data is *computed* by that arithmetic, so the dependency/coherence content is real. |
| **Control-flow labels & branches** (asm `L0:`/`b/cbz/…` 1,140; C `if/while` 35) | 1,175 | 15.7% | `MP+dmb.sy+[fr-rf]-addr-ctrl-data-rfi.litmus` (skips on label `LC00:`) | **Trigger-orthogonal but content-BIASED** | Branches/labels are unmodelled control flow; but 35.1% are DEP tests — these are exactly the `+ctrl` control-dependency tests the jf-coherence axiom is meant to handle. |
| **Unbound base-register addressing** (`base register … not bound`) | 1,023 | 13.7% | `AArch64/mixed/MP+misaligned2+3+addr.litmus` | **Mostly orthogonal** | Register-indirect / misaligned addressing the binder can't resolve; only 9.5% DEP, 2.8% COH (mostly mixed-size/misaligned addressing, out of the word-sized fragment). |
| **ARM Memory-Tagging (MTE)** (`stg/ldg/st2g/irg`) | 937 | 12.6% | `aarch64-ETS3/tests/ETS3.MP+dmb.sttp.litmus` | **Orthogonal** | Allocation-tag operations are a separate tag-memory mechanism, not data coherence; 89% are `other`-family, the rest incidental MP/coherence skeletons. |
| **Unsupported architecture header** (PTX 242, VULKAN 231, BPF 177, OPENCL 57, ASL 55, MIPS 35, LISA 30, BELL 2, …) | 852 | 11.4% | `Dat3M/litmus/OPENCL/herd/3LB.litmus` | **Orthogonal / out-of-scope** | These are *different memory models* (GPU scoped, eBPF, pseudo-arch) the tool never claims to target; 1.5% DEP / 1.8% COH. |
| **Other system / misc** (`TLBI`, `RET`, PPC `lwzx` indexed, `}`, …) | 317 | 4.2% | `AARCH64/NoRet/MP+popl+amo.swpazrp-po.litmus` | **Mostly orthogonal** | TLB-invalidate, returns, indexed loads, stray tokens; ~8% DEP/COH, none coherence-deciding. |
| **C declarations / types** (`int r0 = …`, typed locals) | 118 | 1.6% | `arrays/error/C-array-invalid-01.litmus` | **Orthogonal** | C typed-declaration / array-test scaffolding the C lexer doesn't accept; 0.8% DEP. |
| **Function-call / lock constructs** (`spin_lock`, `mutex_`, calls) | 107 | 1.4% | `LKMM/auto/C-RR-R.litmus` | **Orthogonal** | Library/lock calls are unmodelled procedural syntax; 0% DEP/COH by name. |
| **RMW / LL-SC atomics** (ARM `ldxr/stxr` 55; C `atomic_*` builtins 9) | 64 | 0.9% | `arrays/ok/C-array-ok-04.litmus` (`atomic_set`) | **Coherence-relevant but small** | These *are* RMW/atomicity tests the model cares about, but only 0.9% of skips and the model already validates RMW atomicity on supported-syntax cases. |
| **SIMD / SVE / SME / FP / sysreg** (`movi/ld1/smstart/mrs…`) | 63 | 0.8% | `AArch64.neon/V01.litmus` | **Orthogonal** | Vector/system-register instruction tests, no shared-memory coherence content. |
| **C array / pointer addressing** (`*(arr+1)`, `**arr`) | 15 | 0.2% | `arrays/ok/C-array-ok-01.litmus` | **Orthogonal** | Pointer-arithmetic addressing outside the scalar-location model. |
| **Fence-syntax variant** (RISC-V `fence.tso`) | 2 | 0.0% | `RISCV/LB+fence.tso+fence.tsoxp.litmus` | **Orthogonal (and near-empty)** | The a-priori "fence-syntax mismatch" worry is essentially unfounded: the parser accepts `dmb/dsb/isb/lwsync/sync/eieio/sfence/lfence`; only `fence.tso` (2 files) is unsupported. |
| *(re-parsed OK now — empty `exists`)* | 4 | 0.1% | — | n/a | Marginal nondeterminism in the empty-`exists` guard; counted for completeness. |
| **Total** | **7,461** | 100% | | | |

> **Mapping to the task's a-priori buckets.** "RISC-V annotations" ≈ the RISC-V share of
> *ALU/register* (2,146) + RISC-V atomics; "architecture metadata" = the *arch-header*
> bucket (852); "function-call syntax" = *function-call/lock* (107) + *C decl/array*
> (133); "fence-syntax mismatches" turned out to be **2 files**, not a real bucket. The
> dominant real causes — ALU/register syntax and control-flow — were not in the a-priori
> list; the data drove them out.

## Parse-errors — exception buckets (sum = 1,865)

| Bucket (exception + trigger) | n | % PE | Example | Orthogonality verdict | Justification |
|---|---|---|---|---|---|
| **`no-init-block`** — MALFORMED "expected `{` to begin the initial-state block" (C 890, AArch64 37, other 8) | 935 | 50.1% | `C11/manual/imm-E3.1.litmus`; 832 are `LKMM/auto/*` | **Trigger-orthogonal; content mixed** | Front-end preamble failure (the init-block locator can't skip herd7 `Prefetch=`/`Com=` directive lines or the LKMM/IMM function-style layout); 99% `other`-family by name, but the files include LKMM/IMM synchronisation tests. |
| **`NumberFormatException`** — oversized hex value (mixed-size byte patterns, e.g. `"202020202020202"`) | 694 | 37.2% | `AARCH64/mixed/2+2W+poq0w0-posw0w0+…` | **Out-of-scope (mixed-size)** | 98.4% are AArch64 **mixed-size** tests whose multi-byte value encodings overflow `Integer.parseInt`; 529 carry `+pos` (coherence) — but mixed-size is outside the word-sized fragment the tool models. |
| **`bad-proc-header`** — MALFORMED "expected a `P0 \| P1 …` proc header" (AArch64 235, X86_64 1) | 236 | 12.7% | `aarch64-GCS/tests/Co+gcsss1+gcsss1.litmus` | **Orthogonal** | Thread-header annotation syntax the parser rejects (`.F` final-filter columns, Guarded-Control-Stack `gcsss`, typed-pointer inits); header-format only, 3.8% DEP/COH. |
| **Total** | **1,865** | 100% | | | |

## Is the failed feature relevant to coherence / dependency / jf-coherence reasoning?

* **Trigger level — orthogonal across the board.** Every failure is a front-end lexer or
  preamble limitation (ALU syntax, branches/labels, MTE, vector/sysreg, GPU arch headers,
  mixed-size value encodings, init-block layout). None of them is the coherence /
  dependency / jf-coherence axiom itself; those run in the SMT back-end, which the failed
  files never reach. In that sense the 24.3% is not "the model getting hard cases wrong."
* **Content level — biased, not orthogonal, for two skip buckets.** `3.LB+addr+ctrl+data`
  (skipped on `ori`) and `MP+…+addr-ctrl-data-rfi` (skipped on a label) are dependency
  tests skipped for incidental syntax. **1,251 dependency-family and 760 coherence-family
  tests are excluded** — dependency ~5× and coherence ~4.5× over their validated-set
  share. The dependency *mechanism* is still directly validated on the 104 supported-syntax
  `+addr/+data/+ctrl` tests (incl. the LBdep family), but it is **under-exercised on
  architectural (RISC-V/ARM) dependency and control-dependency tests**.

## §6.2 draft paragraph (≈185 words)

> **Coverage of the validation corpus.** Of 12,324 litmus files, 2,998 (24.3%) were
> validated; the remaining 9,326 (7,461 syntax-skips and 1,865 parse-errors) were
> categorized by re-running the unmodified parser with verbose logging.
> Every failure is a front-end limitation rather than a back-end one: the dominant skip
> causes are unmodelled register arithmetic (37.3%), control-flow labels and branches
> (15.7%), ARM memory-tagging (12.6%), and non-target architecture headers — PTX, Vulkan,
> BPF, OpenCL, MIPS (11.4%); the parse-errors are AArch64 mixed-size value encodings
> (37.2%) and init-block/header-layout rejections (62.8%). The coherence, dependency, and
> jf-coherence axioms are never reached on these files. We caution, however, that the
> skipped set is not a uniform sample: `+addr/+data/+ctrl` dependency tests are ~5×
> over-represented and `Co*`/`+pos` coherence tests ~4.5×, because RISC-V/ARM dependency
> suites express their dependencies through exactly the register-arithmetic and branch
> syntax the front-end omits. The 0/2,998 hierarchy-soundness result therefore holds for
> the word-sized, supported-syntax fragment, but **under-validates the dependency
> mechanism on architectural dependency and control-dependency tests**; closing that gap
> needs a fuller RISC-V/ARM front-end, not a change to the semantic core.

## Risk note — where a reviewer can push back

1. **ALU/register-setup skips (2,784; 22.7% DEP, 19.2% COH).** The single largest bucket,
   and the orthogonality claim is weakest here: RISC-V dependency tests like
   `3.LB+addr+ctrl+data` are skipped purely because the parser can't read `ori x8,x0,1`,
   yet they are precisely the dependency cycles the jf-coherence axiom targets. A reviewer
   will note the mechanism is validated on the *easy* C-dialect dependency tests and not
   on the architectural ones. **Honest framing required; do not call this orthogonal.**
2. **Control-flow / branch skips (1,175; 35.1% DEP).** Highest dependency fraction of any
   bucket — these include the `+ctrl` control-dependency tests, which the paper's own
   Batty discussion treats as load-bearing. Excluding them while claiming control
   dependencies are handled is a tension a reviewer will spot.
3. **Mixed-size coherence parse-errors (≈529 `+pos`).** Defensible as out-of-scope (the
   model is word-sized), but only if §6 *states* the word-sized restriction; otherwise it
   reads as "we dropped the coherence tests that were hard to parse."
4. **`no-init-block` LKMM/IMM tests (935).** Labeled `other` by filename, but 832 are
   LKMM auto-generated and several are IMM-paper examples (`imm-E3.*`) — i.e. genuine
   synchronisation tests. The family-by-name classifier undercounts their relevance, so
   the "99% other-family" figure is softer than it looks; a reviewer who opens the files
   will find real release/acquire and barrier tests.

Buckets **not** at risk (safely orthogonal / out-of-scope): architecture headers (852,
different memory models), ARM MTE (937), SIMD/SVE/SME (63), function-call/lock and C
declarations (225), and the near-empty fence-variant bucket (2).
