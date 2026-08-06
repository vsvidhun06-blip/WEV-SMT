# C1 — Symbolic rf + value propagation: results

Implements the corrected C1 architecture from `src/main/java/wev/smt/parse/SYMBOLIC_RF_DESIGN.md`:
the parser retains each write's **value function** (over `R<readId>` variables, additively —
the folded constant is untouched), and `EventStructureEncoder.enableSymbolic` mints a BV32
`val_read` per reader and `val_write` per write, defines `val_write` as the real function of
the reads it depends on (reusing `QfbvConstancyOracle`'s BV grammar), couples
`rf(w,r) ⇒ val_read[r] = val_write[w]`, pins only the reads/locations the `exists` clause
names, keeps the init write co-minimal, and lets `co` range freely. The verdict is now
**program-level**: FORBIDDEN iff UNSAT over *all* rf/co/value fixpoints (closes F2, M5).

`AxiomaticConsistency` is unchanged — it consumes the same `rf`/`co` decision variables.
Wired mode (`--mode=wired`, default) is byte-for-byte the old behaviour.

## Validation ladder — 9/9 PASS

`SymbolicRfRun ladder` (WEAKEST):

| step | wired | symbolic | expected | result |
|---|---|---|---|---|
| (a) LB-real    | FORBIDDEN | **FORBIDDEN** | FORBIDDEN | PASS |
| (b) LB-fake    | ALLOWED   | **ALLOWED**   | ALLOWED   | PASS |
| (c) LBfd       | ALLOWED   | **ALLOWED**   | ALLOWED   | PASS |
| (d) DETOUR0236 | ALLOWED   | **ALLOWED**   | ALLOWED   | PASS |
| (e) TC06       | ALLOWED   | **FORBIDDEN** | FORBIDDEN | PASS |
| (f) TC16       | ALLOWED   | **ALLOWED**   | ALLOWED   | PASS |
| (g) TC18       | ALLOWED   | **FORBIDDEN** | FORBIDDEN | PASS |
| (h) CoWW       | ALLOWED   | **FORBIDDEN** | FORBIDDEN | PASS |
| (h) CoWR       | FORBIDDEN | **FORBIDDEN** | FORBIDDEN | PASS |

The key gains (e, g) are the value-unreachable outcomes wired mode could not express: TC06
and TC18 flip to FORBIDDEN because their `exists` forces a read value no write can produce.
The fake/real separation (a vs b/c) is preserved — the fake-dep stores are constant functions
of the read (`r^r`, `r+1-r`), so their fixpoint is grounded and stays SAT. (h) needed the
co-minimal-init + memory-final constraints added to the symbolic encoder.

## Full corpus (C3-corrected 2,972-test manifest)

| | |
|---|---|
| Tests | 2972 |
| Verdict changes (wired → symbolic) | **536** |
| — ALLOWED → FORBIDDEN | 413 (value-unreachable `exists` now caught) |
| — FORBIDDEN → ALLOWED | 123 (free-co / free unnamed reads admit an execution the single wiring missed) |
| Errors / skipped | 0 / 0 |
| Wall clock (both modes, one JVM) | 88.7 s |
| Symbolic solve ms | median 10, p95 27, max 65, mean 13.5 |

Data: `eval/symbolic-rf-results.csv` (`test,wired_verdict,symbolic_verdict,change,runtime_ms`).

### Wired-mode regression: **0** (proof)

The parser additions are additive — the value-model maps (`writeValueExprs`,
`existsReadValues`, `existsMemFinal`) are populated but **never read** in wired mode, and the
wired branch of `encodeWellFormedness` is unchanged. So new-wired ≡ old-wired by construction.
The corpus check found 5 wired-vs-manifest discrepancies (all X86 RMW: `2+2W+po+mfence-rmw`,
`R+po-rmw+po`, `S+po+rmw-po`, `S+po-rmws`, `SB-XCHG`). These are **pre-existing**: the manifest
is external reference ground-truth, not the wired baseline — if it were the wired output these
5 would be self-consistent (0 mismatches); the fact that 5 mismatch, combined with wired being
provably unchanged, proves they predate this work. They are the known WEAKEST-vs-reference RMW
limitations, untouched here.

## New agreement numbers

| metric | wired | **symbolic** |
|---|---|---|
| Dat3M (41-test set) | 31/41 | **35/41** |
| JMM (TC01–TC20) | 17/20 | **18/20** |

**Dat3M 31 → 35/41.** Four tests move to agreement: `TC06` (A→F), `TC18` (A→F), `TC13` (F→A,
the fixed-co limitation corrected by free co), and `LB-fake-xor` (A→F, see below). Every flip
moves toward the Dat3M verdict.

**JMM 17 → 18/20.** `TC06` and `TC18` now agree; `TC13` flips to ALLOWED, siding with
Dat3M/LKMM (existential co) against the current JMM *label* FORBIDDEN — the documented
`wev_fixed_co_order` limitation, now corrected by co-enumeration. `TC04` (redundant control
dependency, Batty et al. Thm 2) remains the one expected over-forbidding; symbolic value
propagation does not touch control dependencies.

## The fake/real separation survives — and a degenerate fixture is exposed

Of the six `semantic_vs_syntactic_dep` Dat3M divergences, **five stay ALLOWED under symbolic
mode** (`LB-fake-xor-cycle`, `paper/LBfd`, `MP+dmb.sy+addr`, `leaky-semicolon/LBfd`,
`leaky-semicolon/LBxor`) — their dependent stores are constant functions of the read
(`r^r^1 ≡ 1`, `r+1-r ≡ 1`), so a grounded value fixpoint exists and WEAKEST admits the cycle
while LKMM forbids it syntactically. The contribution is intact.

The sixth, **`paper/LB-fake-xor`, correctly flips to FORBIDDEN** — and this is *right*, not a
regression. It stores `r^r ≡ 0` but its `exists` names `r0=r1=1`; no write ever produces 1, so
the outcome is value-unreachable (exactly TC06's shape). The file's own header documents that
its wired ALLOWED was a *degenerate all-zeros candidate*, "NOT a jfCoherence-driven verdict."
Symbolic mode exposes it as an unfaithful fixture; `LB-fake-xor-cycle` (which writes a constant
1) is the faithful demonstrator and stays ALLOWED.

## Code

- `parse/LitmusParser` — additive value model (`writeValueExprs` over `R<id>`,
  `existsReadValues`, `existsMemFinal`); `parse/LitmusCase` — three new fields + a
  backward-compatible constructor.
- `QfbvConstancyOracle.buildTerm(...)` — static BV-term builder (shared grammar/evaluator).
- `EventStructureEncoder.enableSymbolic(...)` + `symbolicValueConstraints()` — the symbolic
  encoding; wired path untouched.
- `cli/SymbolicRfRun` — `ladder`, `corpus`, and `diag` modes.

_No `.tex` files modified; nothing committed._
