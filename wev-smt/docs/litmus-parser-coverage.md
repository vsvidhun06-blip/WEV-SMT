# `.litmus` parser coverage (Day 10)

The `wev.smt.parse.LitmusParser` ingests the standard herd7 `.litmus` test format and
emits a `wev.smt.parse.LitmusCase` — a WEV `EventStructure` with the `exists`-named
candidate execution wired in, a syntactic `DependencyInfo` sidecar, and the herd7
ground-truth metadata. It is the bridge from the public benchmark suites (herd7
`litmus-tests`, Dat3M) to WEV-SMT, expanding the evaluation surface from the 28
hand-curated `LitmusCorpus` cases to thousands of published tests. **This document is
paper §6.1 ("Experimental Setup").**

## What "parsing" means here

A `.litmus` file is a *program* plus a target *outcome* (`exists` clause). WEV's
`EventStructure` carries a *candidate execution* — a fixed `rf`/`co` — and the validated
question is whether that execution is consistent under a model (exactly the
`AtlasReconstruct` / `LitmusCorpus` methodology). The parser therefore:

1. builds one read/write event per memory access, with program order linking
   consecutive accesses in each thread;
2. adds one initial `WriteEvent` (value `0`, thread `0`) per shared location;
3. **wires `rf` from the `exists` clause**: each read's required value selects the write
   of that value at its location (the initial write preferred for value `0`);
4. lists per-location coherence as the initial write followed by the program writes in
   textual order;
5. records syntactic dependencies (below).

The candidate execution being already wired in is *why* the `exists` clause is otherwise
kept only as metadata: minimum-witness extraction over the wired structure subsumes the
final-state query, so we never re-run a separate existence checker.

The parser is **deterministic**: identical input yields identical event composition,
program order, `rf`/`co` cardinalities and dependency edges (absolute event ids come
from the shared global counter and are intentionally not reset, so they accumulate
across a corpus but never change the *shape*).

**Header metadata is skipped (Day-11 fix).** A quoted test description and any
`KEY=value` *diy* annotations sitting between the architecture header and the `{…}`
initial-state block are now ignored rather than mis-read. These are pervasive in the
herd7 `herding-cats` catalogue and previously produced spurious `expected '{'`
parse-errors; the fix lifted herd7 parse coverage substantially (see
[`corpus-validation-findings.md`](corpus-validation-findings.md), the paper §6.2
companion).

## Supported instructions per architecture

The WEV event vocabulary is only `READ`/`WRITE` accesses with a `MemoryOrder` ∈
{`RELAXED`, `ACQUIRE`, `RELEASE`, `SC`}. Everything below maps onto that vocabulary.

### C dialect (closest to the model — the primary target)

| source | event |
|--------|-------|
| `*x = v` , `WRITE_ONCE(*x, v)` | `WRITE` `RELAXED` |
| `atomic_store_explicit(&x, v, mo)` , `atomic_store(&x, v)` | `WRITE` `mo` (default `SC`) |
| `smp_store_release(x, v)` | `WRITE` `RELEASE` |
| `r = *x` , `r = READ_ONCE(*x)` | `READ` `RELAXED` |
| `r = atomic_load_explicit(&x, mo)` , `atomic_load(&x)` | `READ` `mo` (default `SC`) |
| `r = smp_load_acquire(x)` | `READ` `ACQUIRE` |
| `atomic_thread_fence(mo)` , `smp_mb()` / `smp_rmb()` / `smp_wmb()` | fence (see limitations) |
| `r = atomic_exchange/fetch_add/compare_exchange_*(…)` | RMW (decomposed, see below) |
| `r = <arith expr>` | register assignment (dataflow only, no event) |

Both layouts are accepted: the column/table layout (`P0 | P1 ;` proc header, one
instruction per thread per `;`-terminated row) and the C function-body layout
(`Pn(args){ stmt; stmt; }`).

### x86

| source | event |
|--------|-------|
| `MOV [x], $v` | `WRITE x = v` `RELAXED` |
| `MOV [x], reg` | `WRITE x` `RELAXED`, value from `reg` |
| `MOV reg, [x]` | `READ x → reg` `RELAXED` |
| `MOV reg, $v` | register immediate (no event) |
| `MFENCE` / `LFENCE` / `SFENCE` | fence (see limitations) |
| `XCHG` , `(LOCK) XADD` , `(LOCK) CMPXCHG` | RMW (decomposed) |
| `ADD/SUB/XOR/AND/OR/INC/DEC reg, …` | register arithmetic (dataflow only) |
| `CMP` / `TEST` (+ `Jcc`) | branch → control dependency source |

memory order is `RELAXED` for all plain `MOV` (x86-TSO ordering is supplied by the TSO
*consistency layer*, not by per-access annotations).

### PPC / ARM / RISC-V (common subset)

These share one register-addressed load/store/fence mechanism. The memory base register
is resolved against the **initial-state address bindings** (`{ 0:r2=x; }`); a base
register not bound to a variable makes the file *skipped* rather than misread.

| dialect | loads | stores | fences | imm | RMW | acquire / release |
|---------|-------|--------|--------|-----|-----|-------------------|
| PPC | `lwz ld lbz lhz lwa` | `stw std stb sth` | `sync lwsync hwsync isync eieio` | `li lis` | `lwarx/stwcx. ldarx/stdcx.` | — |
| ARM | `ldr ldar ldapr ldrb …` | `str stlr strb …` | `dmb dsb isb` | `mov movz movw` | `swp ldadd cas casa casal` | `ldar/ldapr/casa…` → `ACQUIRE`, `stlr` → `RELEASE` |
| RISC-V | `lw ld lb lh lr.w lr.d` | `sw sd sb sh sc.w sc.d` | `fence fence.i` | `li lui` | `amoswap.* amoadd.*` | — |

Register arithmetic (`add addi sub xor and or mr/mv neg eor orr`) and branches
(`cmpw/beq/bne/cbz/cbnz/beq/bne/…`) are recognised for dataflow and control-dependency
purposes; they create no events.

## Dependency detection heuristics

Dependencies are derived **syntactically** from instruction text and default to
`isSemantic = true` (Chakraborty & Vafeiadis, POPL 2019, §3 — only *semantic* `sdep`
participates in the WEAKEST jf-coherence axiom):

- **data** — a store whose value expression mentions a register previously loaded in the
  same thread depends (data) on that load. Composes through register assignments
  (`r2 = r1 ^ r1; *y = r2` propagates `r1`'s flow into the store on `y`).
- **addr** — a memory access whose *address* uses a loaded register (an index register
  in `[Xn, Xm]` / `disp(rB)` forms) depends (addr) on that load.
- **ctrl** — a store program-order-after a branch whose condition mentions a loaded
  register depends (ctrl) on that load.

**Identity / fake dependencies.** An expression that cancels the register it mentions —
`r ^ r`, `r - r`, `r & ~r`, `r | ~r`, `r * 0`, `r & 0` — carries no real value flow. Such
edges are recorded with `isSemantic = false`, so the jf-coherence axiom excludes them
and they cannot close a thin-air cycle. This is the syntactic vs. semantic-dependency
distinction the LB-fake / LB-real corpus pair is built around: the fold to a constant is
applied first, and if the register no longer appears the dependency is fake.

## What we skip (and why)

A file that triggers any of the following is **logged to stderr and skipped** —
`LitmusParser.parseDirectory` never aborts a corpus run on one bad file:

| condition | `ParseException.Kind` | rationale |
|-----------|-----------------------|-----------|
| unknown architecture (`MIPS`, `LISA`, …) | `UNSUPPORTED_ARCH` | no event mapping defined |
| instruction outside the modelled subset (SIMD, FP, calls, syscalls, indirect/computed addressing with an unbound base) | `UNSUPPORTED_INSTRUCTION` | out of scope for an axiomatic WMM over scalar memory accesses |
| structural parse error (unterminated `{…}`, missing proc header, malformed body) | `MALFORMED` | logged with `source:line` |
| empty / comments-only file | `EMPTY` | nothing to model |

## Known limitations

These are the honest caveats for §6.1; none crash the parser, but they bound which
published tests are validatable as-is:

1. **Fences are recognised but not encoded.** The `EventStructure` model has no fence
   event, and `com.weakest.*` is read-only here, so `MFENCE` / `DMB` / `sync` /
   `atomic_thread_fence` / `smp_mb()` are parsed (and do not break program order) but
   contribute *no ordering constraint*. A test whose outcome depends on a fence may be
   classified more weakly than herd7. This is the single largest source of expected
   mismatch and is flagged per-row in `corpus-validation.csv`.
2. **RMW atomicity is approximate.** A read-modify-write is decomposed into a
   program-order read→write pair on the same location with a data dependency; the
   *atomicity* axiom (no write to the location between the RMW's read and write) is not
   separately enforced.
3. **Control dependencies are approximate.** We attach `ctrl` from a branch's loaded
   registers to *all* program-order-later stores in the thread, without modelling
   precise control-flow reconvergence or branch-taken/​not-taken paths.
4. **`exists` ↔ `rf` requires per-read final values.** A read whose register is absent
   from the `exists` clause defaults to reading the initial write; when several writes
   of a location share the required value, the lowest-id one is chosen (the initial
   write preferred). Both are deterministic and exact for the canonical 1-write-per-
   location tests, but are heuristics for richer programs.
5. **`acq_rel` / `seq_cst` / `consume` collapse.** `memory_order_acq_rel` and
   `memory_order_seq_cst` both map to the strongest order we model (`SC`);
   `memory_order_consume` maps to `ACQUIRE`.
6. **Coherence beyond one write per location follows textual order.** Per-location `co`
   lists the initial write then program writes as they appear in the source, rather than
   deriving the order from a final-memory clause.
7. **AT&T x86 syntax is unparsed.** Only Intel-syntax x86 (`MOV [x], $v`) is handled;
   AT&T forms (`movl $1, (x)`, `%reg` operands) are not, which is precisely the
   x86_64 `kinds.txt` suite — the one corpus with clean per-model TSO ground truth.
   Closing this unlocks the deferred external match-rate metric (findings §1, §8).
8. **Branch labels are read as instructions.** A leading `label:` token (`L0:`,
   `LC00:`) in the instruction stream is treated as an unknown mnemonic and the file
   is skipped; stripping leading labels before lookup is a one-line front-end fix
   that would recover labelled tests across all ISAs.
9. **RISC-V coverage is lexicon-thin.** Immediate-logic `ori`/`andi`/`xori` are absent
   from the arithmetic set (`ori` alone blocks ~1 700 Dat3M files), as are the
   acquire/release atomic suffixes `.aq`/`.rl`, the `amo*` family and `fence.tso`.
   These are the highest-leverage front-end gaps (findings §5).

### Day-11 corpus results (empirical)

The Day-11 sweep over real herd7 (4 324 files) + Dat3M (8 000 of 24 722 sampled)
exercised the boundaries above end-to-end: **2 998 files fully parsed and validated
across all five models with 0/2998 hierarchy-soundness violations**, 0 timeouts,
0 crashes. The dominant *parser* boundaries seen in the wild were AArch64 MTE
(`STG`/`LDG`, 859 files), RISC-V `ori` (1 686 files), branch labels, AT&T x86, and
full C/LKMM programs — all coverage limits, none soundness bugs. Full breakdown,
per-architecture coverage table, and metric reframe:
[`corpus-validation-findings.md`](corpus-validation-findings.md) and
[`../eval/corpus-summary.md`](../eval/corpus-summary.md).

## Validation harness

`wev.smt.cli.CorpusValidation <litmus-dir> <out-dir> [csv=name] [budgetMin=60]
[perFileSec=30] [maxFiles=0] [maxEvents=64] [minwit=off] [archs=X86,PPC,ARM,RISCV,C]`
walks a directory recursively and, for every parseable test × model in {SC, TSO, PSO,
RA, WEAKEST}, records the full-execution consistency verdict (`actual`), an optional
minimum-consistent witness size, the per-file solve time, and a `status`. It writes one
CSV per corpus — `file, arch, model, expected, actual, witness_size, solve_ms, status,
note` — and prints a per-architecture roll-up.

Day-11 robustness additions (so a multi-thousand-file sweep cannot lose its work):

- **Per-file isolation.** Each file gets a fresh `SolverContext` + `ShutdownManager`
  and a scheduled `perFileSec` interrupt, so one slow/pathological file yields
  `TIMEOUT` without poisoning the shared solver or aborting the run.
- **Size guard.** Files whose event structure exceeds `maxEvents` (default 64) are
  `SKIP`ped (`too-large:N-events`) — an un-interruptible `OutOfMemoryError` in the
  O(n²) axiom encoding is otherwise unrecoverable on the largest LKMM/pointer programs.
- **Incremental, crash-safe CSV.** The header and every row are flushed as the sweep
  proceeds (every 200 files), so a crash mid-corpus still leaves a complete partial CSV.
- **Status vocabulary.** `MATCH`/`MISMATCH` (only when per-model ground truth exists),
  `OK` (validated, no ground truth — the common case on raw corpora), `SKIP`,
  `PARSE_ERROR`, `TIMEOUT`; the `note` column carries the skip/parse-error detail.
- **Budget.** A total `budgetMin` wall-clock cap; `minwit` (minimum-witness extraction)
  is gated to the first half of the budget when enabled.

Per-model ground truth, when present, is read from comments of the form
`SC=Forbidden  TSO=Allowed …`; raw herd7/Dat3M `.litmus` files carry none (only x86_64
`kinds.txt`, separately, and AT&T-syntax — see Known limitations §7), so the Day-11
sweep recorded every validated cell as `OK` and evaluated soundness via the
model-hierarchy invariants instead (findings §1–§2).

> Reproduction note: this harness is intended to run against a downloaded herd7 /
> Dat3M test suite, which is **not** vendored in this repository. The parser and harness
> are unit-tested against hand-crafted `.litmus` strings (`wev.smt.parse.LitmusParserTest`)
> that reproduce the `LitmusCorpus` classics shape-for-shape in both the x86 and C
> dialects.
