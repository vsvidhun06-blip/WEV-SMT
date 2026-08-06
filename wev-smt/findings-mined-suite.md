# findings-mined-suite.md — mining a discriminating suite from real compiler output

**Goal.** Replace the 2-of-34 external anchor of `findings-anchor.md` with genuine,
independently-sourced fake-dependency load-buffering shapes mined from real compiler
output, rather than hand-authored litmus tests.

**Headline: the mined suite contributes 0 discriminators, and the reason is worth more
than the number would have been.**

| suite | tests | fake edges the detector saw | discriminating |
|---|---:|---:|---:|
| mined, exactly as the compiler emitted it | 24 | 0 | **0** |
| same tests + copy propagation (counterfactual) | 24 | 8 | **4** |

Two findings come out of this, both negative for the original plan and both, I think,
more useful than a larger anchor number would have been:

1. **At `-O2` a fake dependency is not a thing compilers emit.** Removing it is what the
   optimiser is *for*. Across 14 lock-free kernels × 4 (compiler, arch) configurations,
   every arithmetic-cancellation idiom was folded to a constant before code generation,
   and the load's value was left dead. Fake dependencies survive to machine code only
   when an optimisation barrier prevents the compiler from proving the cancellation.
2. **The four fake dependencies that do survive are ones the shipped detector cannot
   see** — and, critically, *upgrading to the exact QF_BV oracle does not fix it*. The
   gap is not decision-procedure strength; it is that classification runs per-expression
   without substituting the register environment. This is a precise, actionable W2 item.

Everything below is evaluation-only. The encoder, `AxiomaticConsistency`, the parser and
the detector were not modified.

---

## Phase A — mining

### Corpus

14 kernel source files in `eval/mined/src/`, each carrying a `PROVENANCE` header naming
origin, form and licence. They are faithful transcriptions of published algorithms into
C11 `<stdatomic.h>`, not copies of any one implementation's source text — that
distinction is stated in every header and matters for the honesty of the claim.

| file | algorithms | origin |
|---|---|---|
| `spsc_ring.c` | SPSC circular buffer | Lamport, TOPLAS 5(2), 1983 |
| `mpmc_ring.c` | bounded MPMC queue | Vyukov, 1024cores.net, 2010 |
| `treiber_stack.c` | Treiber stack (+ tagged) | Treiber, IBM RJ 5118, 1986 |
| `ms_queue.c` | Michael–Scott queue | Michael & Scott, PODC 1996 |
| `seqlock.c` | seqlock | Linux `seqlock.h`; Boehm, MSPC 2012 |
| `rcu_publish.c` | RCU publish/consume + 4 documented dependency-killers | Linux `rcu_dereference.rst`; McKenney |
| `hazard_ptr.c` | hazard pointers | Michael, IEEE TPDS 15(6), 2004 |
| `chase_lev.c` | work-stealing deque | Chase & Lev, SPAA 2005; Lê et al., PPoPP 2013 |
| `locks.c` | Dekker, Peterson, ticket, MCS | Dijkstra EWD123; Peterson IPL 1981; MCS TOCS 1991 |
| `dclp_barrier.c` | DCLP, sense barrier, refcount | Schmidt & Harrison PLoP 1996; Hensgen et al. IJPP 1988 |
| `rwlock_bitops.c` | RW spinlock + Hacker's Delight bit idioms | MCS PPoPP 1991; Warren, 2nd ed. 2012 |
| `lkmm_shapes.c` | MP, LB(data/ctrl/addr), SB, WRC, ISA2, R, S | LKMM litmus set; Alglave et al., ASPLOS 2018 |
| `lkmm_fakedep.c` | 12-idiom probe on an LB skeleton | LKMM docs; Jeffrey et al. POPL 2022; Chakraborty & Vafeiadis POPL 2019 |
| `opaque_fakedep.c` | 6 barrier-hidden cancellations | Linux `OPTIMIZER_HIDE_VAR` / `barrier()` semantics |

### Toolchains

All four are real, installed toolchains; exact versions in `eval/mined/asm/TOOLCHAIN.txt`.

```
clang 18.1.3                 -O2 -S                            (x86_64)
clang 18.1.3                 -O2 -S --target=aarch64-linux-gnu (aarch64)
gcc 13.3.0                   -O2 -S                            (x86_64)
aarch64-linux-gnu-gcc 13.3.0 -O2 -S                            (aarch64)
```

Flags throughout: `-O2 -S -std=c11 -fno-asynchronous-unwind-tables -fomit-frame-pointer`.
Driver: `eval/mined/tools/compile_all.sh`. 56 assembly files.

### What `-O2` actually does to a fake dependency

This is the central measurement, and it is unanimous across all four toolchains.
`lkmm_fakedep.c` writes twelve idioms on an identical LB skeleton
(`r = load(x); store(y, f(r))`). At `-O2`:

| idiom | clang aarch64 | gcc aarch64 | verdict |
|---|---|---|---|
| `r ^ r ^ 1` | `ldr wzr,[x0]` / `str w8` (w8=1) | `ldr w0` / `mov w0,1` / `str w0` | dependency **erased** |
| `r + 1 - r` | same | same | erased |
| `(r - r) + 1` | same | same | erased |
| `(r & 0) \| 1` | same | same | erased |
| `r * 0 + 1` | same | same | erased |
| `(r % 1) + 1` | same | same | erased |
| `(r \| ~r) & 1` | same | same | erased |
| `r & r` | `ldr w8` / `str w8` | `ldr w0` / `str w0` | **preserved** (genuine) |
| `t = r; store t` | `ldr w8` / `str w8` | same | **preserved** (genuine) |
| `if (r) y=1 else y=1` | `ldr wzr` / `str` const | `mov w0,1` | erased |
| `if (r) y=1 else y=2` | `cmp`/`cinc` | `cmp`/`beq` | **preserved** (genuine) |

`ldr wzr, [x0]` is the point: clang keeps the atomic load (it is observable) but sends
its result to the **zero register**. The value is dead. There is no dependency edge left
in the machine code for any classifier to reason about.

The same holds inside the real algorithms. `spsc_pop`'s genuine index dependency survives
as `and x9,x8,#0x3f` → `add x9,x0,x9,lsl #2` → `ldr w9,[x9,#8]`. The cancelling variant
`spsc_pop_recycle` (`(t + CAP - t) & mask`) compiles to a direct `ldr w9,[x0,#8]` with no
dependence on the loaded index at all. `treiber_push_tagged`'s `(tag+1-tag)&0xffff`
becomes `ldr xzr,[x0]` plus a constant.

A systematic instruction scan over all 56 files for `eor r,r,r` / `sub r,r,r` /
`and r,r,#0` / `mul r,r,zr` returns **zero matches**. The only `xorl %eax,%eax`
occurrences are the x86 register-zeroing idiom, which takes no input register and is not
a dependency.

**Optimisation-level sweep** (`eval/mined/tools/opt_sweep.sh`, output in
`eval/mined/asm/optsweep/`): the idiom survives only at `clang -O0`, and there only as a
stack-spill round trip (`eor w8,w8,w10` with both operands reloaded from the same slot).
`gcc` folds `r^r` even at `-O0`. By `-O1` both compilers have erased it.

### Where a fake dependency does survive `-O2`

`opaque_fakedep.c` hides the cancellation behind the constructs real systems code uses —
`volatile`, an inline-asm optimisation barrier (Linux `OPTIMIZER_HIDE_VAR`), and an
un-inlinable extern call. Two of the six survive, on all four toolchains:

```
ofd_hidden_sub, clang aarch64 -O2       ofd_hidden_xor, gcc aarch64 -O2
    ldr  w8, [x0]                           ldr  w2, [x0]
    mov  w9, w8                             mov  w0, w2
    sub  w8, w8, w9                         eor  w0, w0, w2
    add  w8, w8, #1                         orr  w0, w0, 1
    str  w8, [x1]                           str  w0, [x1]
```

Both are genuine machine-level fake data dependencies: the loaded value reaches the store
through the register dataflow, and the stored value is the constant 1 for every possible
loaded value. Note the shape — **the cancellation is routed through an SSA copy**. That
is not an accident of these examples; it is what register allocation produces.

The other four do not survive for principled reasons, recorded rather than hidden:
`ofd_volatile_xor` is folded anyway (clang proves `a^a==0` even for volatile reads);
`ofd_hidden_andzero` masks with a runtime-opaque register that is only zero at the call
site, so it is genuinely real in the block; `ofd_opaque_cancel` subtracts two independent
call results, which are not provably equal; `ofd_hidden_real` is the negative control and
correctly keeps `add w8,w8,#1`.

### Mining totals

The five categories below partition all 288 blocks (124 + 48 + 54 + 54 + 8 = 288).

| quantity | count |
|---|---:|
| function blocks scanned | 288 |
| — excluded: contain a call or branch (screen unsound) | 124 |
| — excluded: block has no load, or no store | 48 |
| — dependency **erased** by the compiler (no load→store path) | 54 |
| — load→store flow present, screened REAL | 54 |
| — load→store flow present, screened **FAKE** | **8** |
| blocks with a load→store register flow (54 real + 8 fake) | 62 |
| litmus tests emitted | 24 (4 fake-carrying, 20 real controls) |

All 8 fake-screened blocks are `ofd_hidden_sub` / `ofd_hidden_xor` across the four
configurations. 4 are emitted (aarch64); the 4 x86 ones are excluded because LB is
forbidden by TSO irrespective of any dependency, so an x86 fake-dependency LB test cannot
discriminate by construction.

### Provenance discipline in the emitted tests

Tests are generated by `eval/mined/tools/emit.py`; none was written by hand. Each
`.litmus` embeds the mined instruction sequence verbatim in its header, plus the source
file, function, compiler and flags. Exactly two mechanical transformations are applied,
both documented in the generator:

1. **SSA alpha-renaming** of registers into litmus form, so the loaded value stays live
   for the `exists` clause. Opcodes, operand order, immediates and instruction count are
   the compiler's own.
2. **Immediates emitted bare** (`ADD W3,W2,1`, not `ADD W3,W2,#1`) — forced by a front-end
   defect, see below.

---

## Phase B — classification by the frozen detector

Driver: `wev.smt.ablation.AnchorDiscriminatingRun`, unmodified (frozen encoder +
`AxiomaticConsistency`, one fresh `SolverContext` per solve, `maxEvents=64`,
`timeoutSec=30`).

```
mvn -o compile -DskipTests
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.ablation.AnchorDiscriminatingRun dirs=eval/mined/litmus:mined \
     out=eval/mined-discriminating.csv
```

**Result: 0 of 24 discriminating. `dataFake = 0` on every test** — the detector
classified every mined dependency edge as real, *including the four it should not have*.

The four fake-carrying tests are verdict-for-verdict indistinguishable from the genuine
negative control `ofd_hidden_real`:

| test | screen | deps | fake | `none` | `sdep=dep` | `sdep`=detector | disc |
|---|---|---:|---:|---|---|---|---|
| `ofd_hidden_real_{clang,gcc}` | real | 2 | 0 | ALLOWED | FORBIDDEN | FORBIDDEN | no |
| `ofd_hidden_sub_{clang,gcc}` | **fake** | 2 | **0** | ALLOWED | FORBIDDEN | FORBIDDEN | **no** |
| `ofd_hidden_xor_{clang,gcc}` | **fake** | 2 | **0** | ALLOWED | FORBIDDEN | FORBIDDEN | **no** |

Full per-test table: `eval/mined-summary.md`. Raw data: `eval/mined-discriminating.csv`.

### Why: the detector matches idioms on register *names*, not on values

`LitmusParser.Job.classify(expr, reg)` folds `r^r`, `r-r`, `r&0`, … by regular expression
over a *single expression*, and the linear-cancellation fallback nets `reg`'s coefficient
in that same expression. Neither consults `env`, where the fact `W1 = W0` lives. The
compiler's output is therefore invisible to it:

```
mined  SUB W2,W0,W1  (after MOV W1,W0)  ->  deps=2  fake=0   [W0-W1 wrt W0 -> REAL, wrt W1 -> REAL]
direct SUB W2,W0,W0                     ->  deps=2  fake=2   [W0-W0 wrt W0 -> FAKE: r-r]
mined  EOR W2,W0,W1  (after MOV W1,W0)  ->  deps=2  fake=0
direct EOR W2,W0,W0                     ->  deps=2  fake=2   [W0^W0 wrt W0 -> FAKE: r^r]
```

Same semantics; the only difference is the copy every real compiler inserts.

**This is not fixed by a stronger decision procedure.** Querying the repo's exact
QF_BV oracle directly:

| expression | wrt | `QfbvConstancyOracle.isConstant` |
|---|---|---|
| `W0-W0` | `W0` | CONSTANT (fake) |
| `W0^W0` | `W0` | CONSTANT (fake) |
| **`W0-W1`** (what the compiler emits) | `W0` | **VARIES (real)** |
| **`W0-W1`** | `W1` | **VARIES (real)** |
| **`W0^W1`** | `W0` | **VARIES (real)** |
| `W0+1` / `W0&W0` / `W0` (controls) | `W0` | VARIES (real) |

The oracle is *right*: over two free variables `W0-W1` genuinely varies. The missing step
is substitution / value numbering **before** classification, not a better solver. That is
a sharper W2 statement than "improve the detector".

### Counterfactual: add copy propagation

`emit.py --copyprop` re-emits the same mined blocks with register-to-register `MOV`s
copy-propagated away — a semantics-preserving rewrite that changes no computed value. On
the resulting suite (`eval/mined/litmus-cp/`):

**4 of 24 discriminating**, and the structural criterion agrees exactly (4 fake DATA edges
on `dep ∪ rf` cycles, 4 behavioural flips, same four tests):

```
mined_ofd_hidden_sub_clang_aarch64   sdep=dep FORBIDDEN  vs  sdep=detector ALLOWED
mined_ofd_hidden_sub_gcc_aarch64     sdep=dep FORBIDDEN  vs  sdep=detector ALLOWED
mined_ofd_hidden_xor_clang_aarch64   sdep=dep FORBIDDEN  vs  sdep=detector ALLOWED
mined_ofd_hidden_xor_gcc_aarch64     sdep=dep FORBIDDEN  vs  sdep=detector ALLOWED
```

These four are **not** part of the mined anchor — they are not what the compiler emitted.
They quantify the W2 gap: copy propagation alone converts every surviving mined fake
dependency into a discriminator.

### Splits

Over the 4 fake-carrying mined tests (all `data`, all `aarch64`, 2 `clang` + 2 `gcc`;
idioms `sub-cancel+copy-through` ×2, `xor-cancel+or-set+copy-through` ×2): 0 discriminating
as mined, 4 discriminating with copy propagation. There are **no fake `ctrl` mined tests
at all** — every both-arms-equal branch in the corpus was flattened to an unconditional
constant store by the compiler, so no fake control dependency reaches the front end.

---

## Phase C — honesty checks

### 6. Hand-verification of the discriminators

For `mined_ofd_hidden_sub_clang_aarch64` (copy-propagated form
`LDR W0,[X1]; SUB W1,W0,W0; ADD W2,W1,1; STR W2,[X2]`):

- **Genuinely fake?** `W1 = W0 - W0 = 0` for every `W0` in BV32 including under
  two's-complement wraparound; `W2 = 0 + 1 = 1`. The stored value is the program constant
  1. Confirmed independently by `QfbvConstancyOracle` (table above) — an SMT proof, not my
  sampling screen. Same for the `xor` form: `W0^W0 = 0`, `0|1 = 1`.
- **Genuinely on a cycle?** P0 reads `x` and writes `y`; P1 reads `y` and writes `x`; both
  stores write the constant 1, so `rf` wires each read to the other thread's write and
  `dep ∪ rf` closes. Confirmed by the tool's own Kosaraju SCC check, which reports
  `fake_data_on_lb_cycle = true` for exactly these four and no others.
- **Not a false discriminator?** `none = ALLOWED`, `sdep=dep = FORBIDDEN`,
  `sdep=detector = ALLOWED` — the canonical LBfd/LBxor signature. The negative control
  `ofd_hidden_real` (`ADD W1,W0,1`, genuinely value-carrying) is FORBIDDEN under both
  `sdep=dep` and `sdep=detector`, i.e. correctly non-discriminating. No control flipped.

### 7. Screen false positives, found and eliminated

The extractor's constancy screen initially reported **11** fake blocks. Three were
artefacts of the straight-line interpreter, caught by inspection:

- `opaque_fakedep.c::ofd_opaque_cancel` — `sub w8, w21, w0` where `w21` and `w0` hold the
  results of two *separate* `bl opaque_identity` calls. The walker skipped the calls, left
  both registers holding the pre-call value, and saw a cancellation. In reality the two
  results are not provably equal: this is a **genuine** dependency.
- `mpmc_ring.c::mpmc_pop` (clang x86_64) — a two-deep CAS retry loop; the walker followed
  a path the processor need not take, so neither the dataflow nor the store it landed on
  was meaningful.

`extract.py` now rejects any block containing a call, branch or predicated instruction
(`block_is_straightline`), returning `nonlinear` rather than a screen it cannot justify.
That drops the fake count 11 → 8 and moves 124 blocks into an explicit "not straight-line"
bucket. **The emitted suite was unchanged** (24 tests, 4 fake-carrying) — both false
positives had already been excluded for independent reasons, so they never contaminated
the results. I am reporting them because the earlier 11 appears in intermediate data.

The remaining 8 are all `ofd_hidden_sub` / `ofd_hidden_xor`, each verified by the QF_BV
oracle above.

### An unrelated front-end defect found on the way

`LitmusParser.lexRegisterAsm` strips `#` on the load-immediate path (`MOV W3,#1` parses)
but **not** on the arithmetic path: `isArith` builds `ops.get(1) + opSym(op) + ops.get(2)`
with no `stripImm()`, so `ADD W1,W0,#1` yields the expression `W0+#1`. The `#` then
triggers comment handling and swallows the rest of the row. Measured effect on a
two-thread AArch64 LB test:

```
 ADD W1,W0,#1   ->  events=5  deps=0   expression parsed as 'W0+STR W1'
 ADD W1,W0,1    ->  events=6  deps=2   expression parsed as 'W0+1'
```

Every dependency edge on the affected thread disappears silently. This is why the
generator emits bare immediates. It is a real bug worth fixing independently of this
exercise, and it is consistent with the residual `#`-immediate issue already noted in
`addr-dep-coverage-gap`. I have not touched the parser — the encoder stays frozen.

---

## What this means for the paper's §6 anchor

**The mined suite does not replace the 2-of-34 anchor. It explains why no such anchor
exists to be mined,** which is a defensible contribution in its own right and is the
honest thing to report.

Suggested replacement for the §6 anchor paragraph:

> Of the 34 published examples, 2 (`LBfd`, `LBxor`, both from Jeffrey et al.) are
> discriminating for the semantic-dependency analysis: their verdict changes if `sdep` is
> replaced by `dep`. To test whether that small anchor is an artefact of the literature's
> taste in examples, we mined for the same shapes in real compiler output: 14 lock-free
> kernels (Treiber, Michael–Scott, Chase–Lev, MCS, seqlock, hazard pointers, RCU
> publish/consume, SPSC and MPMC rings, and the LKMM litmus shapes) compiled with clang
> 18.1.3 and GCC 13.3.0 for x86-64 and AArch64 at `-O2`, yielding 288 basic blocks.
>
> We found no arithmetic-cancellation fake dependency in optimised output at all. Of the
> 116 straight-line blocks containing both a load and a store, 54 had every path from the
> load to the store *erased* by the compiler and 54 of the remaining 62 carried a
> genuine, value-varying dependency — folding a vacuous dependency to a constant is
> precisely what `-O2` does, and
> AArch64 clang makes this explicit by directing such loads to the zero register
> (`ldr wzr`). Genuine dependencies are preserved in the same blocks, so the effect is
> selective rather than a loss of fidelity. Fake dependencies survive optimisation only
> behind an optimisation barrier (`OPTIMIZER_HIDE_VAR`, `volatile`, an opaque call); the
> corpus yields 8 such blocks, 4 of them on AArch64 where load buffering is
> architecturally allowed.
>
> All 4 are classified *real* by our detector, so the mined suite adds 0 discriminators.
> The cause is structural rather than a matter of precision: register allocation routes
> the cancellation through a copy (`MOV W1,W0 ; SUB W2,W0,W1`), while our idiom matcher —
> and our exact QF_BV constancy oracle alike — reasons about a single expression over
> free variables, in which `W0-W1` genuinely varies. Copy-propagating the mined blocks
> first, a semantics-preserving rewrite, turns all 4 into discriminators. Adding value
> numbering ahead of classification is therefore the concrete next step, and the
> literature's 2 examples remain the external anchor.

The rarity claim of `findings-anchor.md` is reinforced, not weakened, and now rests on
three independent populations: 2 of 34 published examples, 2 of 231 fake-carrying corpus
tests, and 8 of 288 mined compiler basic blocks — of which 0 are visible to the shipped
detector.

---

## Artifacts

| path | contents |
|---|---|
| `eval/mined/src/*.c` | 14 kernels, each with a PROVENANCE header |
| `eval/mined/asm/*.s` | 56 assembly files + `TOOLCHAIN.txt` |
| `eval/mined/asm/optsweep/` | `-O0/-O1/-O2/-O3/-Os` sweep of the probe kernel |
| `eval/mined/litmus/*.litmus` | 24 mined tests (as emitted by the compiler) |
| `eval/mined/litmus-cp/*.litmus` | 24 copy-propagated counterfactual tests |
| `eval/mined/manifest.csv` | test, source, arch, compiler, idiom, fake_edge_kind, on_lb_cycle |
| `eval/mined/candidates.csv` | all 288 blocks with screen + exclusion reason |
| `eval/mined-discriminating.csv` | Phase B, mined suite (**0/24**) |
| `eval/mined-discriminating-copyprop.csv` | Phase B, counterfactual (**4/24**) |
| `eval/mined-summary.md` | generated roll-up; authority for the numbers here |
| `eval/mined/tools/` | `compile_all.sh`, `opt_sweep.sh`, `extract.py`, `emit.py`, `summarize.py` |

### Reproducing

```bash
bash   eval/mined/tools/compile_all.sh      # needs clang, gcc, aarch64-linux-gnu-gcc
bash   eval/mined/tools/opt_sweep.sh
python eval/mined/tools/emit.py
python eval/mined/tools/emit.py --copyprop
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.ablation.AnchorDiscriminatingRun dirs=eval/mined/litmus:mined \
     out=eval/mined-discriminating.csv
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.ablation.AnchorDiscriminatingRun dirs=eval/mined/litmus-cp:mined-cp \
     out=eval/mined-discriminating-copyprop.csv
python eval/mined/tools/summarize.py
```
