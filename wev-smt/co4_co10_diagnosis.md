# co4 / co10 diagnosis — same-location coherence disagreement with herd7

**Question (from `herd7_baseline.md` §6 risk note).** WEV-SMT reports **ALLOWED** on PPC
`co4` (CoWR) and `co10` (CoWW) where herd7 reports **FORBIDDEN** under SC. Is this
(i) a PPC register-indirect addressing parse issue, or (ii) a real same-location coherence
under-enforcement in `coherencePerLocation` (a soundness bug in the paper's load-bearing
axiom)?

**Answer: neither.** (i) and (ii) are both ruled out by direct measurement. The cause is a
**third, benign category — a coherence-order *wiring* limitation** in the litmus front-end:
the parser fixes the modification order `co` in the writes' *source order*, not from the
test's `exists`/`~exists` queried final value. For coherence tests (Co**WW**/Co**WR**) the
forbidden outcome requires `co` to *disagree* with program order; that `co` is never wired,
so the checker is handed a genuinely coherent execution and correctly returns SAT. The
`coherencePerLocation` axiom itself is **sound** (proven below). **This is a parser/harness
footnote, not a soundness bug, and does not block submission.**

Diagnostic harness (read-only, no parser/core edits): `wev.smt.proof.ParseDump`.

## 1. The two stores' `loc` fields (Step 1 instrumentation output)

Parsed by the frozen `LitmusParser`; `loc` is each event's resolved `getVariable()`.

**`co4` (CoWR, `~exists (x=1 /\ 0:r2=2)`):**

| id | thread | kind | **loc** | value |
|----|--------|------|---------|-------|
| 0 | P0 | WriteEvent | **x** | W=1  (`stw r1,0(r5)`, r5=x) |
| 1 | init | WriteEvent | **x** | W=0 |
| 2 | P0 | ReadEvent  | **x** | reads 2 (`lwz r2,0(r5)`) |
| 3 | P1 | WriteEvent | **x** | W=2  (`stw r1,0(r5)`, r5=x) |

The two stores (id 0, id 3) both have **loc = x**.

**`co10` (CoWW, `~exists (x=1)`):**

| id | thread | kind | **loc** | value |
|----|--------|------|---------|-------|
| 4 | P0 | WriteEvent | **x** | W=1  (`stw r1,0(r5)`, r5=x) |
| 5 | init | WriteEvent | **x** | W=0 |
| 6 | P0 | WriteEvent | **x** | W=2  (`stw r2,0(r5)`, r5=x) |

The two stores (id 4, id 6) both have **loc = x**.

**⇒ Step 2 decision: the loc fields MATCH.** PPC register-indirect addressing
(`stw rN, 0(r5)` with `r5 = x` from the init block) resolves correctly to location `x`.
**Hypothesis (i) is ruled out.**

## 2. Outcome

**Outcome: (i) ruled out, (ii) ruled out — root cause is coherence-order wiring (a third
category), and the coherence axiom is sound.**

Two pieces of evidence rule out (ii):

**(a) The wired `co` is consistent, so ALLOWED is correct *for the wired execution*.** The
parser wires `co` in source order of the writes:

```
co10:  coherenceOrder = { x = [5, 4, 6] }  = init → W(x=1) → W(x=2)   (co AGREES with po W1→W2)
co4:   coherenceOrder = { x = [1, 0, 3] }  = init → W(x=1) → W(x=2)   (final x = 2)
```

In `co10` the program order is `W(x=1) →po→ W(x=2)` (id 4→6) and the wired `co` is
`W(x=1) →co→ W(x=2)` — **co and po agree**, a perfectly coherent execution, so SAT/ALLOWED
under every model is the correct verdict for *that* execution. The forbidden outcome
`~exists(x=1)` needs the *opposite* co (`W(x=2) →co→ W(x=1)`, final x=1), which the parser
never wires. Likewise `co4` is wired with final x=2 (and the read consistently reads 2),
not the queried `x=1 ∧ r2=2` violation. herd7 answers "is the bad outcome *reachable*?"
(no ⇒ FORBIDDEN); WEV-SMT answers "is *this wired* execution consistent?" (yes ⇒ ALLOWED).
They answer different questions because the wired execution does not realise the query.

**(b) `coherencePerLocation` DOES forbid the violating wiring.** The hand-built
`LitmusCorpus.buildCoWW` uses the *same* two same-location stores with po `W1→W2`, but wires
the **reversed** `co` (`W2 →co→ W1`, against po) — i.e. the actual CoWW violation. Verdict
under all five models (`HierarchyProbe`):

```
CoRR  FORBID  CoRW  FORBID  CoWR  FORBID  CoWW  FORBID   (SC, TSO, PSO, RA, WEAKEST — all FORBID)
```

So when the violating `co` *is* present, `coherencePerLocation` rejects it under WEAKEST and
SC alike (the per-location layer gets `po-loc: layer(W1)<layer(W2)` and
`co: layer(W2)<layer(W1)` → contradiction). The axiom is **sound**; nothing is
under-enforced. **Hypothesis (ii) is ruled out.**

## 3. Minimal reproducer (built per Step 3) — and what it actually shows

C-dialect (supported-syntax) analogue, `eval/examples/diag/CoWW-min.litmus`:

```
C CoWW-min
{ }
P0(int *x) { WRITE_ONCE(*x, 1); WRITE_ONCE(*x, 2); }
P1(int *x) { int r = READ_ONCE(*x); }
exists (x=1)
```

Parsed wiring and verdict:

```
events:  W(x=1) id7  →po→  W(x=2) id9  (P0);  R(x) id10 reads 0 (P1)
coherenceOrder = { x = [8, 7, 9] } = init → W(x=1) → W(x=2)    (co AGREES with po)
verdicts:  SC=allow  TSO=allow  PSO=allow  RA=allow  WEAKEST=allow
```

**Reading this correctly:** the reproducer *does* reproduce ALLOWED in the supported C
fragment — but **not** because of coherence under-enforcement. It reproduces the exact same
**wiring** behaviour: even from an `exists (x=1)` clause, the parser fixes `co` in source
order (`W1` before `W2`, final x=2), handing the checker a coherent execution. Contrast with
§2(b), where the *same shape with the violating co* is FORBIDDEN. So the reproducer confirms
the **wiring** root cause and, together with §2(b), confirms the **encoding is not at fault**.
(If the front-end derived `co` from the `exists` target — wiring `W2 →co→ W1` to realise
`x=1` — this test would be FORBIDDEN, matching herd7.)

## 4. Recommendation for §6

> The two same-location coherence disagreements with herd7 (`co4`, `co10`) are a
> front-end **coherence-order wiring** limitation, not an unsoundness of the coherence
> axiom. Our litmus loader fixes the modification order `co` in the source order of a
> location's writes rather than deriving it from the test's `exists` target; for the CoWW /
> CoWR shapes the forbidden outcome requires `co` to oppose program order, so the loader
> instead presents a coherent execution that the checker correctly accepts (herd7 asks
> whether the bad state is reachable; we check whether the wired execution is consistent).
> The `coherencePerLocation` axiom itself is sound: the curated `CoRR`/`CoRW`/`CoWR`/`CoWW`
> cases, which wire the violating `co` directly, are FORBIDDEN under all five models
> including WEAKEST (the per-location order makes `po-loc` and the reversed `co` mutually
> contradictory). The disagreement therefore reflects which candidate execution is enumerated
> for hardware-style coherence tests, and we note it as a loader limitation; it does not
> affect the WEAKEST soundness results, whose forbidding comes from `jfCoherence` and from
> `coherencePerLocation` on correctly-wired executions.

---

### Artifacts
| File | Contents |
|---|---|
| `src/main/java/wev/smt/proof/ParseDump.java` | read-only parse dump (events/po/co) + per-model verdict |
| `eval/examples/diag/CoWW-min.litmus` | minimal C-dialect CoWW reproducer |

Reproduce:
```
mvn -q compile
java -Djava.library.path=target/native -cp "target/classes;$(cat target/ablation-cp.txt)" \
     wev.smt.proof.ParseDump \
     eval/corpus/herdtools7/catalogue/herding-cats/ppc/tests/illustrative/co4.litmus \
     eval/corpus/herdtools7/catalogue/herding-cats/ppc/tests/illustrative/co10.litmus \
     eval/examples/diag/CoWW-min.litmus
```
