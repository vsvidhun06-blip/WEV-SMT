# Hierarchy-violation witnesses

Detailed witness analysis for the two failed links of the claimed chain
**SC ⊇ TSO ⊇ PSO_CANONICAL ⊇ RA ⊇ WEAKEST** over the 40-test
[`atlas-canonical.csv`](atlas-canonical.csv).

**Convention.** `A ⊇ B` means *A is at least as strong as B* —
`forbidden(A) ⊇ forbidden(B)`, equivalently `allowed(A) ⊆ allowed(B)`. The
inclusion **fails** at a test that **A allows but B forbids** (A fails to forbid
something B forbids). Verdicts are the full-execution consistency of the wired
candidate; `jf ≡ rf` in this single-execution encoding. Init events live on thread
T0.

---

## Failed inclusion 1 — PSO_CANONICAL ⊇ RA  (FAILS)

`allowed(PSO_CANONICAL) ⊄ allowed(RA)`. Two witnesses: **CO-MP** and **MP-relacq**
(both are `buildMP(REL, ACQ)` — release store, acquire load — so the analysis is
identical).

### Witness: CO-MP  (PSO_CANONICAL = ALLOWED, RA = FORBIDDEN)

**Responsible execution** (message passing, `data` then `flag`):

```
 writer P0 (T1):  e83: W(data)=1  --po-->  e84: W(flag)=1   [flag store is a RELEASE]
 reader P1 (T2):  e85: R(flag)    --po-->  e86: R(data)     [flag load is an ACQUIRE]
 rf:  e85 <- e84 (flag=1)   e86 <- e81 (data=0, the initial write)
 co:  data=[e81(init), e83]   flag=[e82(init), e84]
```

The observed outcome is `flag=1 ∧ data=0` — the reader sees the flag set but the
stale initial data.

**Responsible axiom: RA's `irreflexiveHbEco`.**
The release write `e84` read by the acquire load `e85` is a *synchronizes-with*
edge, so `hb = (po ∪ sw)⁺` contains `e83 →po e84 →sw e85 →po e86`. The stale read
`e86` (of `data=0`) is coherence-before the writer's `data=1` (`e83`), giving
`e86 →fr e83`. Then `e83 →hb e86 →fr e83` is an `hb ; eco` cycle ⇒
`irreflexive(hb ; eco?)` is violated ⇒ **RA forbids**.

PSO_CANONICAL has **no** release/acquire term (it is exactly PSO′ minus
`irreflexiveHbEco`). Its `ppo` relaxes same-thread `W→W`, so the writer's
`data→flag` order is dropped and the execution is `ppo ∪ rfe ∪ co ∪ fr`-acyclic ⇒
**PSO_CANONICAL allows**.

**Why the inclusion fails.** Canonical SPARC PSO models no thread synchronization,
so it cannot forbid a message-passing violation that RA rules out purely through
release/acquire happens-before. RA forbids CO-MP; PSO_CANONICAL does not ⇒
`forbidden(PSO_CANONICAL) ⊉ forbidden(RA)`.

### Witness: MP-relacq  (PSO_CANONICAL = ALLOWED, RA = FORBIDDEN)

Same event structure and same `hb ; eco` argument as CO-MP:

```
 e146: W(data)=1 --po--> e147: W(flag)=1 (REL) ;  e148: R(flag)(ACQ) --po--> e149: R(data)
 rf: e148<-e147 (flag=1), e149<-e144 (data=0)
```

RA forbids via `sw(e147→e148)` closing `hb ; fr`; PSO_CANONICAL (no such axiom,
`W→W` relaxed) allows. Same failure cause.

> This link is in fact an **incomparability**: RA also allows executions
> PSO_CANONICAL forbids (e.g. **LB**, **IRIW** — RA has no `ppo`), so
> `RA ⊉ PSO_CANONICAL` as well. The two models are unordered.

---

## Failed inclusion 2 — RA ⊇ WEAKEST  (FAILS)

`allowed(RA) ⊄ allowed(WEAKEST)`. Three witnesses: **LBdep-real**, **LBdep-addr**,
**3.LBdep-real** — load buffering closed by a *genuine* (semantic) dependency.

### Witness: LBdep-real  (RA = ALLOWED, WEAKEST = FORBIDDEN)

**Responsible execution** (2-thread load buffering, real data deps):

```
 P0 (T1):  e164: R(x)  --po-->  e165: W(y)=1     [W(y) value data-depends on R(x)]
 P1 (T2):  e166: R(y)  --po-->  e167: W(x)=1     [W(x) value data-depends on R(y)]
 rf:  e164 <- e167 (x=1)     e166 <- e165 (y=1)
 co:  x=[e162(init), e167]   y=[e163(init), e165]
```

The outcome `r(x)=1 ∧ r(y)=1` is a load-buffering / out-of-thin-air read: each read
sees the value the other thread will write.

**Responsible axiom: WEAKEST's `jfCoherence` = `acyclic(sdep ∪ jf)`.**
The two writes carry **real semantic** data dependencies, contributing `sdep` edges
producer→consumer: `e164 →sdep e165` and `e166 →sdep e167`. With `jf ≡ rf`:
`e167 →jf e164` and `e165 →jf e166`. These close a cycle

```
 e164 →sdep e165 →jf e166 →sdep e167 →jf e164
```

so the strict `jfco` layering is unsatisfiable ⇒ **WEAKEST forbids**.

RA has **no** no-thin-air / dependency axiom. Dependencies are not part of RA's
`hb` (only `po` and release/acquire `sw`, and these accesses are relaxed), so the
`po ∪ rf` cycle is not an `hb ; eco` cycle ⇒ the execution is consistent ⇒
**RA allows**.

**Why the inclusion fails.** WEAKEST is the only model here with a thin-air axiom;
it forbids LB carried by a real dependency, which RA (a pure synchronization model)
permits. RA allows LBdep-real; WEAKEST forbids it ⇒
`forbidden(RA) ⊉ forbidden(WEAKEST)`.

### Witness: LBdep-addr  (RA = ALLOWED, WEAKEST = FORBIDDEN)

Identical LB structure, but the dependency is an **address** dependency:

```
 e170: R(x) --po--> e171: W(y)=1 ;  e172: R(y) --po--> e173: W(x)=1
 rf: e170<-e173 (x=1), e172<-e171 (y=1)
```

`sdep` includes addr edges `e170→e171`, `e172→e173`; with `jf` (`e173→e170`,
`e171→e172`) the same `sdep ∪ jf` cycle forms ⇒ WEAKEST forbids, RA allows.

### Witness: 3.LBdep-real  (RA = ALLOWED, WEAKEST = FORBIDDEN)

The 3-thread extension `x → y → z → x`:

```
 T1: e213: R(x) --po--> e214: W(y)=1        T2: e215: R(y) --po--> e216: W(z)=1
 T3: e217: R(z) --po--> e218: W(x)=1
 rf: e213<-e218 (x=1), e215<-e214 (y=1), e217<-e216 (z=1)
```

Three `sdep` edges (`e213→e214`, `e215→e216`, `e217→e218`) and three `jf` edges
(`e218→e213`, `e214→e215`, `e216→e217`) close one long `sdep ∪ jf` cycle ⇒ WEAKEST
forbids; RA (no thin-air axiom) allows.

> This link is also an **incomparability**: WEAKEST allows executions RA forbids
> (e.g. **CO-MP**, **CO-WRC**, **MP+lwsync**, **LB+ctrlfence** — WEAKEST reasons only
> by `jfCoherence` and ignores fences/release-acquire), so `WEAKEST ⊉ RA` too.

---

## Summary of witnesses

| failed link | witness tests | model_a allows via | model_b forbids via |
|---|---|---|---|
| PSO_CANONICAL ⊇ RA | CO-MP, MP-relacq | no sync axiom; `ppo` relaxes W→W | RA `irreflexive(hb ; eco?)` |
| RA ⊇ WEAKEST | LBdep-real, LBdep-addr, 3.LBdep-real | no thin-air axiom | WEAKEST `acyclic(sdep ∪ jf)` |

Both failures are two-directional (**incomparabilities**), so neither link is a
strict-ordering slip — RA simply does not sit on a total order between the
`ppo`-based hardware models and the dependency-based WEAKEST.
