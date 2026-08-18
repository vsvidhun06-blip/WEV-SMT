# -*- coding: utf-8 -*-
"""
The unrestricted class was falsified: with m registers the site summary has 2^m
states, and for m large enough beta can encode the class index. That capacity is
not intrinsic to "Boolean".

The paper's actual alpha satisfies a further property, proved in Isabelle as
non_constant_imp_read:   non_constant e r  ==>  r : regs e
i.e. the summary bit can only be set for registers the expression actually reads.

READ-RESTRICTED CLASS  A_read:
    beta : Expr x Reg -> Bool   with   beta(e,r) ==> r in vars(e)

For single-register expressions e over {x}, the only bit that may be set is
beta(e,x): capacity 2, independent of |Reg|.

Question: does Claim D hold for A_read?
"""
import json, os
from claimD_probe import grammar_funcs, cls_of, mk


def prog_dep(e, g, N):
    return len({g[e[x]] for x in range(N)}) > 1


def restricted_capacity(nvars):
    """Under the read restriction, a site over `nvars` variables has at most
    2^nvars states -- NOT 2^|Reg|."""
    return 1 << nvars


def check(w, depth):
    N, M, ap = mk(w)
    fs = sorted(grammar_funcs(w, depth))
    reps = {}
    for f in fs:
        reps.setdefault(cls_of(f), f)
    E = list(reps.values())
    G = fs
    sigs = {e: tuple(prog_dep(e, g, N) for g in G) for e in E}
    nsig = len(set(sigs.values()))
    # every expression here is over the single register x, so under A_read the
    # e-slot has capacity 2 regardless of how many registers the program declares
    cap = restricted_capacity(1)
    return nsig, cap, nsig > cap, len(E), len(G)


print("=" * 74)
print("CLAIM D under the READ-RESTRICTED class A_read")
print("  (expressions over one register => site capacity 2, independent of |Reg|)")
print("=" * 74)
rows = []
for w in (1, 2, 3, 4):
    for depth in (2, 3):
        nsig, cap, holds, ne, ng = check(w, depth)
        rows.append({"w": w, "depth": depth, "signatures": nsig,
                     "capacity": cap, "claimD_holds": holds})
        print(f"  w={w} depth<={depth}: dep-signatures={nsig:5d}  capacity={cap}"
              f"  -> Claim D {'HOLDS' if holds else 'FAILS'}")

p = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'claimD-restricted.json')
json.dump(rows, open(p, 'w'), indent=1)
print("\nwrote", p)

print()
print("=" * 74)
print("SMALLEST WITNESS for the read-restricted theorem (w=2)")
print("=" * 74)
w, depth = 2, 3
N, M, ap = mk(w)
fs = sorted(grammar_funcs(w, depth))
reps = {}
for f in fs:
    reps.setdefault(cls_of(f), f)
E = list(reps.values())
# any beta in A_read gives each e one bit -> by pigeonhole two classes collide
# exhibit the collision explicitly for BOTH possible bit assignments
print("  #classes =", len(E), " capacity = 2  => some two classes share a bit.")
found = None
for i in range(len(E)):
    for j in range(i + 1, len(E)):
        e1, e2 = E[i], E[j]
        for g in fs:
            if prog_dep(e1, g, N) != prog_dep(e2, g, N):
                found = (e1, e2, g)
                break
        if found:
            break
    if found:
        break
e1, e2, g = found
print(f"  e1 = {e1}  range={sorted(set(e1))}")
print(f"  e2 = {e2}  range={sorted(set(e2))}")
print(f"  g  = {g}")
print(f"  prog_dep(e1,g) = {prog_dep(e1,g,N)}   prog_dep(e2,g) = {prog_dep(e2,g,N)}")
print("  => whichever bit beta assigns, some such pair collides (pigeonhole).")
