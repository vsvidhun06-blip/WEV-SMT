# -*- coding: utf-8 -*-
"""
TASK 5 -- try to SYNTHESIZE a Boolean site abstraction that computes prog_dep.

Two-instruction shape:   t := e(x) ; y := g(t)
prog_dep(e,g) = [ g not constant on range(e) ].

A candidate abstraction is beta : Expr x Reg -> Bool. In this shape the abstraction
of a program is determined by the bit-vectors
    Be = ( beta(e,r) )_{r in Reg}      (2^m possible values)
    Bg = ( beta(g,r) )_{r in Reg}
A decision function f must satisfy prog_dep(e,g) = f(Be, Bg) for all e, g.

We search EXHAUSTIVELY for such a (beta, f) over a finite expression set, for
several capacities m. This both attempts falsification of Claim D and validates
the framework: when capacity >= #classes a solution MUST exist, and if our search
cannot find one then the search itself is broken.
"""
import itertools, json, os
from claimD_probe import grammar_funcs, cls_of, mk   # reuse verified builders


def prog_dep(e, g, N):
    return len({g[e[x]] for x in range(N)}) > 1


def synthesize(w, depth, m, verbose=True):
    """Exhaustive search for beta (as a class->bitvector map) and f.
    Since prog_dep depends on e only through its ~-class, and on g pointwise,
    beta is w.l.o.g. constant on ~-classes for the e-slot; the search below
    assigns a bitvector to each ~-class and to each g, then checks consistency."""
    N, M, ap = mk(w)
    fs = sorted(grammar_funcs(w, depth))
    # representative per ~-class for the e-slot
    reps = {}
    for f in fs:
        reps.setdefault(cls_of(f), f)
    E = list(reps.values())
    G = fs                                  # continuations range over the grammar
    K = 1 << m                              # capacity: number of distinct bitvectors

    if verbose:
        print(f"    w={w} depth<={depth} m={m}: |E-classes|={len(E)}  |G|={len(G)}  capacity={K}")

    # Necessary condition first (cheap): two e-classes sharing a bitvector must
    # agree on prog_dep for EVERY g. Build the dep-signature of each class.
    sigs = {}
    for e in E:
        sigs[e] = tuple(prog_dep(e, g, N) for g in G)
    distinct_sigs = len(set(sigs.values()))
    if verbose:
        print(f"      distinct dep-signatures among e-classes: {distinct_sigs}")
    if distinct_sigs > K:
        return False, f"pigeonhole: {distinct_sigs} signatures > capacity {K}"

    # Capacity suffices in principle -> actually construct beta and f.
    sig2bits = {}
    for s in set(sigs.values()):
        sig2bits[s] = len(sig2bits)
    # beta on e-slot: class -> its signature index (fits since distinct_sigs <= K)
    # beta on g-slot: g -> index of g's "behaviour vector" over e-classes
    gsig = {}
    for g in G:
        gsig[g] = tuple(prog_dep(e, g, N) for e in E)
    gs2bits = {}
    for s in set(gsig.values()):
        gs2bits[s] = len(gs2bits)
    if len(gs2bits) > K:
        return False, (f"g-slot needs {len(gs2bits)} states > capacity {K} "
                       f"(e-slot fits, g-slot does not)")

    # decision table
    table = {}
    ok = True
    for e in E:
        for g in G:
            key = (sig2bits[sigs[e]], gs2bits[gsig[g]])
            v = prog_dep(e, g, N)
            if key in table and table[key] != v:
                ok = False
                break
            table[key] = v
        if not ok:
            break
    return (ok, "constructed beta and f" if ok else "table inconsistent")


if __name__ == '__main__':
    results = []
    print("=" * 74)
    print("SYNTHESIS ATTEMPTS  (m = |Reg|, capacity = 2^m)")
    print("=" * 74)
    for w in (1, 2, 3):
        for depth in (2, 3):
            for m in (1, 2, 3, 4, 8, 12):
                ok, why = synthesize(w, depth, m, verbose=(m == 3))
                results.append({"w": w, "depth": depth, "m": m,
                                "capacity": 1 << m, "synthesized": ok, "reason": why})
                tag = "SUCCESS" if ok else "fail   "
                print(f"      m={m:2d} cap={1<<m:5d}: {tag}  {why}")
            print()
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'claimD-synthesis.json')
    json.dump(results, open(p, 'w'), indent=1)
    print("wrote", p)
