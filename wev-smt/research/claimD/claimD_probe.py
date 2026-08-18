# -*- coding: utf-8 -*-
"""
CLAIM D probe.

FORMALIZATION
-------------
Site abstraction class A_bool:
    beta : Expr x Reg -> Bool          (site-uniform: depends only on the
                                        expression at the site and the register)
Program abstraction induced by beta, for P = [(x_1,e_1), ..., (x_n,e_n)]:
    alpha_beta(P) = [ (x_i, { r in Reg : beta(e_i, r) }) ]_{i=1..n}

Program-level dependence (as in PwT_Program.thy):
    prog_dep P x y  <->  exists s v1 v2.
                          run P (s[x:=v1]) y  !=  run P (s[x:=v2]) y

CLAIM D:
    for all beta in A_bool,
      not exists f. for all P x y. prog_dep P x y = f (alpha_beta P) x y

STRATEGY (pigeonhole):
  Work in the 2-instruction shape  t := e(x) ; y := g(t).
  Then prog_dep = [ g not constant on range(e) ].
  Known (proved separately, re-verified below): e1 ~ e2  iff  range(e1)=range(e2)
  or both e are constant.  Distinct ~-classes therefore need distinct beta images.
  The e-slot of alpha_beta contributes exactly the bit-vector
      ( beta(e,r) )_{r in Reg}          -> at most 2^|Reg| values.
  If the GRAMMAR realizes more than 2^|Reg| classes, pigeonhole gives a collision.

This script measures the grammar-realized class count per width and checks the
inequality.  It does NOT assume the previous audit's numbers.
"""
import itertools, json, os, sys

OPS = ['+', '-', '*', '&', '^']
NREG = 3          # x, t, y  -> Boolean capacity of the e-slot is 2^3 = 8


def mk(w):
    N = 1 << w
    M = N - 1

    def ap(op, a, b):
        if op == '+': return (a + b) & M
        if op == '-': return (a - b) & M
        if op == '*': return (a * b) & M
        if op == '&': return a & b
        if op == '^': return a ^ b
        raise ValueError(op)
    return N, M, ap


def grammar_funcs(w, depth):
    """All 1-register functions denotable by the grammar up to `depth` binary ops.
    Atoms: r, ~r, and every constant in BV_w. Paren-free left-assoc build-up,
    matching the shape of expressions the front end accepts."""
    N, M, ap = mk(w)
    consts = list(range(N))
    cur = set()
    cur.add(tuple(v for v in range(N)))            # r
    cur.add(tuple((~v) & M for v in range(N)))      # ~r
    for c in consts:                                # constant functions
        cur.add(tuple(c for _ in range(N)))
    allf = set(cur)
    frontier = set(cur)
    for _ in range(depth):
        nxt = set()
        for f in frontier:
            for op in OPS:
                for c in consts:
                    nxt.add(tuple(ap(op, f[v], c) for v in range(N)))
                    nxt.add(tuple(ap(op, c, f[v]) for v in range(N)))
                # combine with r and ~r as the right operand
                nxt.add(tuple(ap(op, f[v], v) for v in range(N)))
                nxt.add(tuple(ap(op, f[v], (~v) & M) for v in range(N)))
        frontier = nxt - allf
        allf |= nxt
    return allf


def cls_of(f):
    """~-class: the range, with all constant functions identified."""
    r = frozenset(f)
    return 'CONST' if len(r) == 1 else r


def verify_equiv_characterisation(w):
    """Re-verify (not assume) that e1~e2 iff same range, or both constant,
    by brute force over ALL functions -- feasible for w<=2."""
    N, M, ap = mk(w)
    F = list(itertools.product(range(N), repeat=N))
    sig = {}
    for e in F:
        s = tuple(len({g[e[x]] for x in range(N)}) > 1 for g in F)
        sig.setdefault(s, set()).add(cls_of(e))
    # each dep-signature must correspond to exactly one class, and vice versa
    ok = all(len(v) == 1 for v in sig.values())
    classes = {c for v in sig.values() for c in v}
    return ok, len(sig), len(classes)


out = {}
print("=" * 74)
print("TASK 3 -- re-verify the semantic equivalence (brute force, all functions)")
print("=" * 74)
for w in (1, 2):
    ok, nsig, ncls = verify_equiv_characterisation(w)
    print(f"  w={w}: dep-signatures={nsig}  range-classes={ncls}  "
          f"signature<->class bijection: {ok}")
    out[f"equiv_w{w}"] = {"signatures": nsig, "classes": ncls, "bijection": ok}

print()
print("=" * 74)
print("TASK 4 -- grammar-realized classes vs Boolean capacity 2^%d = %d" % (NREG, 2 ** NREG))
print("=" * 74)
rows = []
for w in (1, 2, 3, 4):
    for depth in (2, 3):
        fs = grammar_funcs(w, depth)
        cs = {cls_of(f) for f in fs}
        cap = 2 ** NREG
        rows.append((w, depth, len(fs), len(cs), cap, len(cs) > cap))
        print(f"  w={w} depth<={depth}: grammar functions={len(fs):6d}  "
              f"~-classes={len(cs):5d}  capacity={cap}  exceeds={len(cs) > cap}")
out["grammar"] = [{"w": a, "depth": b, "funcs": c, "classes": d,
                   "capacity": e, "exceeds": f} for a, b, c, d, e, f in rows]

os.makedirs(os.path.dirname(os.path.abspath(__file__)), exist_ok=True)
p = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'claimD-counts.json')
json.dump(out, open(p, 'w'), indent=1)
print("\nwrote", p)
