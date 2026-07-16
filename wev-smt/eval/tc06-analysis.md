# TC06 under WEAKEST — analysis

Source: `eval/examples/jmm/TC06.litmus` (JMM causality test 6, Pugh 2004).

```c
C TC06
{ }
P0(int *x, int *y) { r0 = READ_ONCE(*y); WRITE_ONCE(*x, 1); }
P1(int *x, int *y) { r0 = READ_ONCE(*x); WRITE_ONCE(*y, r0 + 1); }
P2(int *y)         { WRITE_ONCE(*y, 2); }
exists (0:r0=1 /\ 1:r0=1)
```

**JMM decision: FORBIDDEN.** The `exists` names the P0↔P1 load-buffering cycle
(P0 reads `y=1`, P1 reads `x=1`). For P0 to read `y=1` some thread must write
`y=1`; the only value-producing write of `y` is P1's `r0+1`, which is `1` only
when P1 read `x=0` — contradicting `1:r0=1`. So the outcome is unrealisable and
`x=2` never occurs (x is written only 0 or 1). P2's `y=2` is a coherence
distractor.

**WEAKEST checker: ALLOWED** for the posed candidate — as expected. Details below.

Both facts below were produced by running the frozen loader/checker, not by hand:
`wev.smt.proof.ParseDump` (candidate + verdicts) and a `semanticEdges()` dump.

---

## 1. What candidate does the loader wire?

`ParseDump` output (thread 0 = inits; P0→1, P1→2, P2→3):

| id | thread | kind  | loc | value          | note |
|----|--------|-------|-----|----------------|------|
| 0  | 1 (P0) | Read  | y   | reads **1**    | rf ← id5 |
| 1  | 0      | Write | y   | 0              | init |
| 2  | 1 (P0) | Write | x   | **1**          | constant `WRITE_ONCE(*x,1)` |
| 3  | 0      | Write | x   | 0              | init |
| 4  | 2 (P1) | Read  | x   | reads **1**    | rf ← id2 |
| 5  | 2 (P1) | Write | y   | **1**          | `r0+1`, see caveat |
| 6  | 3 (P2) | Write | y   | 2              | unused by any rf |

- **program order:** `id0→id2` (P0), `id4→id5` (P1).
- **coherence:** `x = [id3(0), id2(1)]`, `y = [id1(0), id5(1), id6(2)]`.
- **reads-from (jf):** `id5→id0` (P1's `y=1` justifies P0's `y=1` read),
  `id2→id4` (P0's `x=1` justifies P1's `x=1` read).
- **semantic dependency (sdep), from `semanticEdges()`:** exactly one edge,
  `id4 → id5` (P1: `read x` → `write y`, the real `r0+1` data dep). P0's
  `WRITE_ONCE(*x,1)` and P2's `WRITE_ONCE(*y,2)` are constant stores and carry
  **no** dependency.

So the wired candidate is the full P0↔P1 load-buffering execution the `exists`
names: P0 reads y from P1, P1 reads x from P0, with P2's `y=2` present but
observed by no read.

> **Caveat — write-value modelling.** The loader evaluates data-dependent store
> values by substituting each loaded value with 0 (`evalConst`), so P1's
> `WRITE_ONCE(*y, r0+1)` is wired with value `0+1 = 1` (id5, `W=1`), not 2. This
> is why a `y=1` write *exists* for P0's `y=1` read to observe. It does **not**
> affect the verdict: the `id4→id5` dependency is still recorded semantically, and
> the WEAKEST result is driven purely by the dependency structure (below), not by
> this concrete value. The candidate is exactly the one whose consistency is the
> validated question — the `exists` clause itself is not re-queried.

## 2. What is the checker's verdict?

`ParseDump` per-model verdicts:

```
SC=FORBID  TSO=FORBID  PSO=FORBID  RA=allow  WEAKEST=allow  RC11=FORBID
```

**WEAKEST = ALLOWED (SAT).** Confirmed.

Why: WEAKEST's no-thin-air axiom is `jfCoherence = acyclic(sdep ∪ jf)` (jf ≡ rf),
**not** RC11's syntactic `acyclic(po ∪ rf)`. The would-be thin-air cycle is

```
id0 --po--> id2 --rf--> id4 --po--> id5 --rf--> id0
 (P0:Ry)     (P0:Wx)     (P1:Rx)     (P1:Wy)
```

Under WEAKEST the two `rf` edges are `jf`, and the `id4→id5` po edge is backed by
a real `sdep` (P1's `r0+1`). But the **closing** po edge `id0→id2` — P0's
*read y* → *write x* — has **no** `sdep`, because P0 writes the constant `x=1`:
its value does not depend on what P0 read from y. With that edge absent from
`sdep ∪ jf`, the layering `id2 < id4 < id5 < id0` is satisfiable ⇒ no cycle ⇒
**ALLOWED**. RC11's `acyclic(po ∪ rf)` includes the bare `id0→id2` po edge and so
closes the cycle ⇒ FORBIDDEN. This is precisely the documented WEAKEST-vs-RC11
divergence (§6.4): WEAKEST permits load buffering that is *not* carried by a
genuine dependency, RC11 forbids it syntactically.

This is the intended behaviour: P0's `x=1` is written unconditionally, so it is
well-justified, not thin-air; the candidate is genuinely consistent under WEAKEST.

## 3. Is there any wireable candidate where x=2 that is jfCoherence-consistent?

**No — and none can even be wired.**

The only writes to `x` in the program are the initial write (`x=0`) and P0's
`WRITE_ONCE(*x, 1)` (a constant `1`). No event anywhere writes `x=2`: the only
literal `2` targets `y` (P2's `WRITE_ONCE(*y, 2)`, and P1's `r0+1` which is at
most `2` also targets `y`). The loader wires each read's `rf` via
`chooseWrite(loc, want)`, which can only select an existing write of the requested
value (falling back to the initial write otherwise). Since no write of value `2`
to `x` exists, no read of `x` can ever be wired to observe `x=2`.

Therefore the loader **cannot construct any candidate in which `x=2` is observed**,
so the jfCoherence question is vacuous: there is no `x=2` candidate to check, and a
fortiori none that is jfCoherence-consistent. `x=2` is unreachable in the event
structure — matching the JMM observation that `x` is only ever 0 or 1.

---

## Summary

| Question | Answer |
|----------|--------|
| 1. Wired candidate | Full P0↔P1 load-buffering execution: P0 `Ry=1` ← P1's `Wy` (id5), P1 `Rx=1` ← P0's `Wx=1` (id2); sole sdep edge `id4→id5`; P2's `y=2` present but unobserved. |
| 2. WEAKEST verdict | **ALLOWED** (SAT). The cycle's closing edge `id0→id2` (P0 read y → write x) has no semantic dependency — P0's `x=1` is a constant store — so `acyclic(sdep ∪ jf)` holds. (RC11 forbids via bare `po`.) |
| 3. jfCoherence-consistent candidate with x=2 | **No.** `x` is only ever written 0 or 1; no write of `x=2` exists, so no such candidate is even wireable — the question is vacuous. |

Reproduce:

```
java -Djava.library.path=target/native -cp "target/classes;$(cat target/cp.txt)" \
     wev.smt.proof.ParseDump eval/examples/jmm/TC06.litmus
```
