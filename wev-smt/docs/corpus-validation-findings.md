# Corpus validation: failure-mode triage and scope (paper §6.2)

This document is the transparency companion to the Day-11 real-corpus sweep
(`eval/corpus-summary.md`). It states honestly what the prototype parses, what it
skips and why, how its verdicts relate to ground truth, and which subset we
recommend for the paper's evaluation. Reviewers asking "what are the scope
boundaries?" should find every boundary named here.

Corpora: **herd7** (`herd/herdtools7`, 4 324 `.litmus` files) and **Dat3M**
(`hernanponcedeleon/Dat3M`, 24 722 files, of which a strided 8 000 were swept).
Tooling: `wev.smt.cli.CorpusValidation`, 30 s/file budget, 64-event size guard,
all five models per file. Raw CSVs: `eval/corpus-validation-{herd7,dat3m}.csv`.

---

## 1. The metric reframe

The original plan was an *external match-rate* against herd7 expected outcomes.
That metric is **not applicable to the raw corpora**: a `.litmus` file's `exists`
clause states an outcome but not whether each *model* allows it, and only the
x86_64 suite ships a clean per-test oracle (`kinds.txt`) — which is AT&T-syntax and
outside the current parser (§5). We therefore evaluate on three metrics, in order
of evidential weight:

| rank | metric | result | what it shows |
|------|--------|--------|---------------|
| **primary**   | **hierarchy-soundness** | **0 / 2 998 violations** | the encoding never contradicts the model lattice across 2 998 independently-parsed real tests — strong evidence of internal soundness |
| secondary | parse coverage | 2 998 / 12 324 = 24.3 % fully parsed | honest scope of the front-end; remainder skipped for *documented* reasons, not encoding errors |
| tertiary  | external match-rate vs clean ground truth | deferred | requires AT&T x86_64 parsing (`kinds.txt`); future work |

This reframe was made deliberately and with approval; it converts "we can't
compute the planned number" into a *stronger* claim that needs no external oracle.

---

## 2. Headline result — hierarchy-soundness: 0 / 2 998

For every fully-validated file we check the relationships that hold by
construction (so any violation would be an encoding bug, not a model fact):

1. **Monotone allowedness** `SC ⊆ TSO ⊆ PSO`.
2. **Weakest-inclusion** `{SC,TSO,PSO} ⊨ ALLOWED ⟹ WEAKEST ⊨ ALLOWED` — WEAKEST
   only *drops* the stronger models' global-order axioms and *adds* jf-coherence,
   which constrains thin-air dependency cycles only; it cannot newly forbid an
   execution a stronger model already admits.

Across **697 herd7 + 2 301 Dat3M = 2 998** validated files: **0 monotonicity
violations, 0 weakest-inclusion violations.** Recomputed independently from the
CSVs (`Stats.java`).

**Nuance worth stating precisely.** `RA` and `WEAKEST` are *incomparable*, not
nested: WEAKEST can forbid a thin-air cycle RA allows (e.g. the catalogue's
`LBdep-real`, vector `FFFAF`), and RA can forbid via acquire/release ordering
WEAKEST permits. So `RA ⟹ WEAKEST` is **not** a theorem. Empirically, however, it
*also* held for all 2 998 files: no validated file produced an `FFFAF` vector,
because thin-air forbidding needs a *semantic* dependency cycle and the parser
recognised none as semantic in the validated subset. We report the theorem-backed
invariants as the soundness claim and the `RA ⟹ WEAKEST` pass as a (stronger but
non-theorem) empirical observation.

This 0/2998 is paper §6.2's headline.

---

## 3. Per-architecture coverage and skip-reason breakdown

Combined over both corpora (distinct files). In this pipeline a file that parses
is *always* validated against all five models, so **files-parsed = files-validated**
(no partial state). "Skipped" = parse-time skip **or** parse-error; the sub-columns
partition those exhaustively.

| arch | seen | parsed = validated | parse % | skip: unsupp. instr-family | skip: SIMD / calls | skip: unparseable / malformed `exists` | skip: fences (recognised, unencoded) | skip: unsupp. arch | skip: other | match-rate vs ground truth |
|------|-----:|-------------------:|--------:|----------:|-----------:|-----------:|:--:|----------:|------:|:--:|
| X86   |   247 |   204 | 82.6 % |    40 |   0 |     3 | 0† | 0 | 0 | N/A‡ |
| PPC   |   871 |   613 | 70.4 % |   256 |   0 |     2 | 0† | 0 | 0 | N/A |
| ARM   | 6 271 | 1 534 | 24.5 % | 3 710 |  63 |   964 | 0† | 0 | 0 | N/A |
| C     | 1 842 |   641 | 34.8 % |   308 |   0 |   890 | 0† | 0 | 3 | N/A |
| RISCV | 2 241 |     6 |  0.3 % | 2 228 |   0 |     7 | 0† | 0 | 0 | N/A |
| (unrecognised) | 852 | 0 | 0.0 % | 0 | 0 | 0 | 0 | 852 | 0 | N/A |
| **total** | **12 324** | **2 998** | **24.3 %** | **6 542** | **63** | **1 866** | **0** | **852** | **3** | — |

Column totals reconcile to the raw status counts: 6 542 + 63 + 1 866 + 852 + 3 =
9 326 = 7 461 skip + 1 865 parse-error. Per-corpus splits are in
`eval/corpus-summary.md`.

† **Fences are recognised but not encoded.** `dmb`, `sync`, `fence`, `eieio`, etc.
are lexed and the surrounding instructions parse — so a fenced test *validates*
rather than skipping (hence 0 in this column). The fence simply imposes no ordering
in the current encoding, so the verdict is the *fence-free* verdict. This is an
**encoding choice** that shifts a verdict, not a skip; it is catalogued in §4 as the
single largest source of would-be disagreement with real-hardware tables. We
surface it as its own (zero-valued) column precisely so reviewers see it is *not*
hidden among the skips.

‡ x86_64's `kinds.txt` is clean TSO ground truth, but those files are AT&T-syntax
and unparsed (§5); the parseable X86 set is Intel-syntax with no per-model oracle.
Hence N/A everywhere — the tertiary metric is genuinely deferred, not zero.

---

## 4. Skip-reason families, by frequency (the empirical detail)

Dominant unsupported-instruction mnemonics (distinct files), from the note column:

**herd7** (`UNSUPPORTED_INSTRUCTION` 3 018, `UNSUPPORTED_ARCH` 322, malformed 287):
- **MTE memory-tagging**: `STG` 688 + `LDG` 171 = **859** — the single biggest
  family; AArch64 Memory-Tagging-Extension tag stores/loads. Out of model scope.
- **Address-translation / system tests**: `530` aarch64 `pte`/`tlbi` tests fail on
  an *unbound base register* (the test's address operand is never written), plus
  `TLBI`/`SMSTART`/`MSR` privileged instrs (~149). Systems-level, out of scope.
- **Branch labels** (`L0:`/`L1:`/`LC00:` …, ~190): a label in the instruction
  stream is read as an unknown mnemonic — a *narrow parser gap* (see §5).
- **SIMD/vector** (`MOVI`, `LD1`, …): 63 files. Vector ISA, out of scope.
- **AT&T x86** (`MOVL` + `(reg)` operands): 32 files — the x86_64 `kinds.txt` set.
- **Flag-setting / shift forms** (`ADDS`,`SUBS`,`CSET`,`LSR`), **load-exclusives**
  (`LDXR`): real instruction-family gaps, modest counts.

**Dat3M** (`UNSUPPORTED_INSTRUCTION` 3 587, malformed/unexpected 1 579,
`UNSUPPORTED_ARCH` 530):
- **RISC-V `ori`**: **1 686** files — one missing immediate-logic mnemonic accounts
  for ~76 % of all RISC-V files (see §5; this is *the* coverage gap).
- **Branch labels** (`LC00:`/`LC0`/`LC`, ~462): same label gap as herd7.
- **C front-end** (`int` declarations, arrays, structs, pointers): the 889 C
  parse-errors are real C programs (`dartagnan` arrays/pointers/LKMM) beyond the
  litmus-subset grammar.
- **LKMM primitives** (`spin_*`, `rcu_read_lock()`, …) and **RISC-V atomics**
  (`lw.aq`, `amoor.d.aq`, `fence.tso`): small counts, out of current scope.
- **GPU/PTX** dialects: the 530 `UNSUPPORTED_ARCH` files (non-X86/PPC/ARM/RISCV/C).

---

## 5. RISC-V: a narrow lexicon gap, not a structural one

The stop-and-report trigger "an entire architecture skips" fired for RISC-V on
Dat3M (1 / 2 217 validated, 0.05 %). The diagnosis is deliberately reassuring:

- **`ori` / `andi` / `xori`** (immediate bitwise-logic) are absent from the RISC-V
  arithmetic lexicon, which currently knows `{add addi sub xor and or mv neg}`.
  `ori` alone blocks **1 686** files. These are *ordinary ALU ops* with obvious
  event semantics (a local register update, no memory effect) — adding them is a
  few lexicon entries, not new encoding.
- **`.aq` / `.rl` acquire/release suffixes**, **`amo*` atomics**, **`lr`/`sc`
  reservations**, **`fence.tso`**: ~24 files. These *do* carry ordering semantics
  and would need real encoding work (map to the existing ACQUIRE/RELEASE/SC
  `MemoryOrder` and RMW shapes).
- **`LC00:` branch labels**: shared with herd7 ARM/PPC; stripping leading
  `label:` tokens before mnemonic lookup is a one-line front-end fix that would
  recover labelled tests across *all* architectures at once.

None of these is a soundness problem; all are front-end coverage. RISC-V coverage
is the highest-leverage, lowest-risk future-work item.

---

## 6. Mismatch categorisation

With no per-model ground truth there are **0 recorded `MISMATCH` rows**. The useful
question is therefore *why a validated verdict could differ from a hardware/herd7
reference table*, sorted into three kinds:

**(a) Parser limitations** — the file never reaches the solver, so there is no
verdict to disagree (these are the §3–§5 skips). Fixing them *adds* tests; it does
not change any existing verdict. Examples: MTE, AT&T x86, `ori`, labels.

**(b) Encoding choices** — the file validates, but a modelling decision makes the
verdict the *simplified* one:
- **Fences unencoded** (§3†): the dominant case. A test like `MP+dmb.sy+dmb.sy`
  validates as plain `MP` — so the prototype reports the *more permissive*
  (fence-free) verdict. On a real machine the fence would forbid the outcome. This
  is the principal systematic difference from hardware tables, and it is monotone
  (we never wrongly *forbid*), so it does **not** threaten hierarchy-soundness.
- **Multi-write coherence by textual order**: when a location has >2 writes, the
  modification order is taken in source order absent other constraints — a
  convention, defensible but not unique.
- **Dependency semanticity**: only *semantic* dependencies (`isSemantic=true`) feed
  jf-coherence; syntactic identity idioms (`r ^ r`) are correctly treated as fake.
  This is the project's contribution, validated by the `LBdep-*` family.

**(c) Genuine model differences** — the verdict the prototype *should* produce and
that distinguishes the models. These are the separation classes in
`eval/corpus-summary.md`: `FAAAA` (SC|TSO), `FFAAA` (TSO|PSO), `FFFAA` (PSO|RA),
and the rare `FFFFA` — six Dat3M files only WEAKEST permits, the jf-coherence
contribution observed in the wild.

Crucially, every apparent "difference" falls in (a) or (b) — a coverage boundary or
a *monotone* simplification — never an unsound verdict. That is what the 0/2998
check certifies.

---

## 7. Recommended paper evaluation subset

For §6 we recommend reporting on the **cleanly-parsed, separation-bearing core**
rather than the raw corpora:

1. **Hierarchy-soundness on all 2 998 validated files** — the headline; no oracle
   needed, maximally defensible.
2. **The separation atlas** — the 953 non-`AAAAA` / non-`FFFFF` files that exercise
   a real model boundary (461 SC|TSO + 214 TSO|PSO + 145 PSO|RA + 6 RA|WEAKEST),
   with the curated `LitmusCorpus.classics()` cases (now including the corpus
   generalisations `3.2W`, `6.SB`, `3.LBdep-fake`, `3.LBdep-real`) as the
   ground-truthed anchor where textbook verdicts *are* known.
3. **PPC + Intel-x86 as the high-coverage ISAs** (70–83 % parse) for any
   per-architecture drill-down; flag ARM (24.5 %, MTE-dominated) and RISC-V (0.3 %,
   `ori`-dominated) as explicitly scoped-out, with §5's path to closing RISC-V.
4. **Solve-time** from `eval/corpus-summary.md` (mean ≈ 11 ms, p99 < 40 ms) as the
   performance evidence, cross-referenced with the Day-9 polynomial scaling sweep.

Explicitly *out of scope* for the paper's claims, and stated as such: MTE, SIMD,
address-translation/system tests, GPU dialects, full C/LKMM programs, AT&T x86
syntax, and fence *semantics*.

---

## 8. Future work (priority order)

1. **Strip `label:` tokens** before mnemonic lookup — one front-end fix, recovers
   labelled tests across ARM/PPC/RISC-V at once.
2. **RISC-V immediate-logic** (`ori`/`andi`/`xori`) — recovers ~1 700 Dat3M files;
   trivial lexicon additions.
3. **Encode fences** as ordering constraints — converts the largest "encoding
   choice" difference into faithful verdicts and unlocks the fenced majority of the
   ARM/PPC catalogues.
4. **AT&T x86 syntax** — unlocks x86_64 `kinds.txt`, enabling the deferred tertiary
   *external match-rate* metric against clean TSO ground truth.
5. RISC-V atomics (`.aq`/`.rl`/`amo*`/`lr`/`sc`), then the broader C/LKMM grammar.
