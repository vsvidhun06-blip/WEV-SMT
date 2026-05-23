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

## Validation harness

`wev.smt.cli.CorpusValidation <litmus-dir> [out-dir]` parses a directory, and for every
test × model in {SC, TSO, PSO, RA, WEAKEST} records the full-execution consistency
verdict (`actual`), the minimum-consistent witness size, and the match against any
per-model herd7 expectation parsed from the file's comments. It writes
`corpus-validation.csv` (`file, arch, model, expected, actual, witness_size, solve_ms,
match`) and prints a per-architecture summary (comparable cells, % matched, mean solve
time, outliers). Per-model ground truth is read from comments of the form
`SC=Forbidden  TSO=Allowed …`; a herd `Observation … Never|Sometimes|Always` line is kept
as a coarse model-agnostic fallback.

> Reproduction note: this harness is intended to run against a downloaded herd7 /
> Dat3M test suite, which is **not** vendored in this repository. The parser and harness
> are unit-tested against hand-crafted `.litmus` strings (`wev.smt.parse.LitmusParserTest`)
> that reproduce the `LitmusCorpus` classics shape-for-shape in both the x86 and C
> dialects.
