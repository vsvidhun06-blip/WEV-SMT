# Real-corpus validation summary (Day 11)

Sweep of `wev.smt.cli.CorpusValidation` over two real litmus corpora:

- **herd7** — `herd/herdtools7` regression `.litmus` suite (catalogues + tests).
- **Dat3M** — `hernanponcedeleon/Dat3M` `dartagnan` test resources.

Each parseable file is encoded once and checked for consistency under all five
models (SC, TSO, PSO, RA, WEAKEST); the verdict per (file, model) is recorded in
`corpus-validation-herd7.csv` / `corpus-validation-dat3m.csv`. The corpora carry
**no per-model ground truth** in the raw `.litmus` text (only x86_64 ships a clean
`kinds.txt`, and that subset is AT&T-syntax — see findings doc §"match-rate"), so
every validated cell is recorded `OK` rather than `MATCH`/`MISMATCH`.

## Headline metric — hierarchy-soundness: **0 / 2998 violations**

For every fully-validated file we check the two model-relationships that hold by
construction and need no external oracle:

1. **Monotone allowedness** `SC ⊆ TSO ⊆ PSO` — anything SC allows, TSO allows;
   anything TSO allows, PSO allows.
2. **Weakest-inclusion** `{SC,TSO,PSO} ⊨ ALLOWED ⟹ WEAKEST ⊨ ALLOWED` — WEAKEST
   drops the global-order constraints of the stronger models, so it cannot forbid
   what they allow (it only *adds* the thin-air / jf-coherence axiom, which bites
   only on genuine dependency cycles).

| corpus | validated files | hierarchy violations |
|--------|----------------:|---------------------:|
| herd7  |             697 |              **0** |
| Dat3M  |           2 301 |              **0** |
| **total** |        **2 998** |          **0** |

Re-derived independently from the CSVs (`Stats.java`); the run also satisfied the
*stronger* empirical check that even `RA ⊨ ALLOWED ⟹ WEAKEST ⊨ ALLOWED` held for
all 2 998 files (no validated file produced an `FFFAF` vector — see distribution
below). This is **the headline result**: across 2 998 independently-parsed real
litmus tests the encoding never once contradicted the soundness lattice, which is
strong evidence the consistency encoding is internally sound. It is paper §6.2's
primary claim.

## Parse coverage (secondary metric)

| corpus | files processed | validated | parse % | skipped | parse-error |
|--------|----------------:|----------:|--------:|--------:|------------:|
| herd7  |          4 324 |       697 |  16.1 % |   3 341 |         286 |
| Dat3M  | 8 000 (of 24 722, strided sample) | 2 301 | 28.8 % | 4 120 | 1 579 |
| **total** |    **12 324** |   **2 998** | **24.3 %** | **7 461** | **1 865** |

`0` files timed out and `0` crashed in either corpus (per-file 30 s budget; a
64-event size guard converts the handful of huge LKMM/pointer programs to `SKIP`
rather than risking an un-interruptible OOM). Skips are *documented instruction /
syntax coverage boundaries*, not encoding failures (full breakdown in the findings
doc). Run wall-clock: herd7 52.6 s, Dat3M 163.5 s.

## Per-architecture coverage (distinct files)

### herd7
| arch  | seen | validated | skip | parse-err | parse % |
|-------|-----:|----------:|-----:|----------:|--------:|
| ARM   | 3 723 |      570 | 2 873 |       280 |  15.3 % |
| PPC   |   103 |       60 |    42 |         1 |  58.3 % |
| X86   |    89 |       51 |    35 |         3 |  57.3 % |
| RISCV |    24 |        5 |    18 |         1 |  20.8 % |
| C     |    63 |       11 |    51 |         1 |  17.5 % |
| (unrecognised arch) | 322 | 0 | 322 | 0 | 0.0 % |

### Dat3M
| arch  | seen | validated | skip | parse-err | parse % |
|-------|-----:|----------:|-----:|----------:|--------:|
| X86   |   158 |      153 |     5 |         0 |  96.8 % |
| PPC   |   768 |      553 |   214 |         1 |  72.0 % |
| ARM   | 2 548 |      964 |   901 |       683 |  37.8 % |
| C     | 1 779 |      630 |   260 |       889 |  35.4 % |
| RISCV | 2 217 |        1 | 2 210 |         6 |   0.0 % |
| (unrecognised arch) | 530 | 0 | 530 | 0 | 0.0 % |

PPC and X86 are the strongest-covered ISAs (57–97 % parse). RISC-V is the weakest
(≈0 % on Dat3M): a **narrow lexicon gap**, not a structural one — 1 686 of the 2 216
RISC-V skips are the single unrecognised mnemonic `ori`, with `andi`/`xori` and the
`.aq`/`.rl`/`amo*` atomic suffixes accounting for the rest. Adding those tokens
would lift RISC-V coverage sharply; it is logged as future work, not a soundness
concern.

## Solve-time stats (validated model-rows, ms)

| corpus | rows | mean | median | p95 | p99 | max |
|--------|-----:|-----:|-------:|----:|----:|----:|
| herd7  | 3 485 | 11.9 | 13 | 24 | 40 | 287 |
| Dat3M  | 11 505 | 11.0 | 11 | 22 | 32 | 88 |

Every individual consistency check completes in tens of milliseconds; the p99 is
under 40 ms and the single worst case is 287 ms. The procedure is comfortably
real-time on real corpora at these sizes (consistent with the Day-9 polynomial
scalability sweep).

## Verdict-vector distribution (separation classes)

Each validated file yields a 5-character vector over `(SC TSO PSO RA WEAKEST)`,
`A`=allowed / `F`=forbidden. The distribution *is* the empirical separation atlas
the corpus exercises:

| vector | herd7 | Dat3M | total | separation exhibited |
|--------|------:|------:|------:|----------------------|
| `AAAAA` | 605 | 1 440 | 2 045 | none (allowed by all five) |
| `FAAAA` |  43 |   418 |   461 | SC \| TSO |
| `FFAAA` |  10 |   204 |   214 | TSO \| PSO |
| `FFFAA` |  30 |   115 |   145 | PSO \| RA |
| `FFFFF` |   9 |   118 |   127 | none (coherence / uniproc — forbidden by all) |
| `FFFFA` |   0 |     6 |     6 | **RA \| WEAKEST (WEAKEST-only allows)** |

The `FFFFA` class — six Dat3M files only WEAKEST permits — is the rarest and the
one that exercises the project's specific contribution (the jf-coherence model
admitting an execution every classical model forbids). No file produced `FFFAF`
(WEAKEST forbidding what RA allows): thin-air forbidding requires a *semantic*
dependency cycle, and the parser conservatively recognised none as semantic in the
validated subset — which is exactly why the stronger `RA ⟹ WEAKEST` empirical
check also passed 0 violations.

## Stop-and-report triggers

The Day-11 spec armed three halt conditions. Status:

- **(a) match-rate < 70 % on parseable subset** — *non-applicable.* The raw corpora
  carry no per-model ground truth, so there is no match-rate to fall below; the
  result was reframed (with approval) to hierarchy-soundness + parse-coverage.
- **(b) an entire architecture skips with warning** — **FIRED for RISC-V on Dat3M**
  (1 / 2 217 validated). Diagnosed as a narrow lexicon gap (`ori`/`andi`/`xori`,
  `.aq`/`.rl`/`amo*`), documented in the findings doc as future work, not a bug.
- **(c) WEAKEST matches herd7 expected < 80 %** — *non-applicable* for the same
  no-ground-truth reason; superseded by the 0/2998 hierarchy-soundness check.

No encoding-soundness halt condition fired.

---

## Day-12 update: fence + RMW encoding (atlas regrown 150 → 190 cells)

Day 12 promoted fences and read-modify-writes from parser-recognised-but-unencoded to
**first-class events** (`FenceEvent` with kinds `FULL`/`ACQ`/`REL`/`ACQ_REL`, atomic
`RMWEvent` with a coherence-atomicity axiom). The hand-curated `LitmusCorpus.classics()`
grew **32 → 40** with eight fence/RMW tests, and `AtlasReconstruct` re-validated the whole
set:

**190 compared cells, 190 matched, 0 mismatched** (40 tests × 5 models, less 10
`UNKNOWN` cells). The eight new cases and their verdict vectors `(SC TSO PSO RA WEAKEST)`:

| litmus | vector | what it pins down |
|--------|--------|-------------------|
| `SB+mfences` | `FFFAA` | a `FULL` fence restores `W→R`, recovering SC for SC/TSO/PSO |
| `2+2W+sync` | `FFFAA` | `FULL` fence restores `W→W` (the PSO relaxation) |
| `IRIW+sync` | `FFFAA` | fenced independent-reads-of-independent-writes |
| `RMW-as-fence` | `FFFAA` | a full-fence RMW drains program order like `MFENCE` |
| `SB+rmw` | `FFFAA` | atomic (`xchg`) stores act as the SB fence |
| `MP+lwsync` | `FFFFA` | PPC `lwsync`(REL)+`isync`(ACQ) message-passing |
| `LB+ctrlfence` | `FFFFA` | `ACQ_REL` fences — a **new RA \| WEAKEST separator** (rarest class) |
| `CAS-pair` | `FFFFF` | two CAS reading the same write — atomicity violation, forbidden by all |

Why fences stay invisible to RA/WEAKEST (the `··A A` tails): RA orders only through
release/acquire synchronisation, and WEAKEST forbids only genuine thin-air cycles — a
seq-cst *fence* between a store and a load creates neither, so `SB+mfences` and friends
remain allowed there even as SC/TSO/PSO forbid them. `MP+lwsync`/`LB+ctrlfence` add the
`sw` edges RA needs, so RA forbids too, leaving WEAKEST the sole permitter.

**Scalability (`scalability-fences.csv`).** Two new parametric families —
`SBChainMfence(n)` (the SB ring plus an `MFENCE` per thread, `4n` events) and `RMWChain(n)`
(an `n`-thread atomic-increment chain, `n+1` events with an `O(n²)` atomicity check) — were
swept over `n ∈ {2..16}` × 5 models against the fenceless `SBNThread` baseline. **Fence/RMW
overhead stays under 3× on small cases (worst 2.56× at `SBChainMfence/RA, n=4`)**, the
Day-12 stop-trigger; curves track the baseline's shape (SC/TSO flat, PSO/RA polynomial,
WEAKEST near-linear), no exponential blow-up. The Day-9 sweep CSVs are untouched.

The full atlas run output is committed as [`atlas-day12-final.txt`](atlas-day12-final.txt).
The earlier RISC-V lexicon note above is partly closed: Day 12 added the `amoswap`/`amoadd`
`.w`/`.d` RMW forms and `fence pred,succ` fence kinds (the `.aq`/`.rl` suffixes and `ori`
remain open). See [`../docs/litmus-parser-coverage.md`](../docs/litmus-parser-coverage.md)
§"Fence and RMW encoding".
