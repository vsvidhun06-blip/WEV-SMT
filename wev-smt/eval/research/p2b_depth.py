#!/usr/bin/env python3
"""TASK 2 (corrected) -- is BOUNDED-depth substitution ever sufficient?

The p2 `inline` helper substitutes in one pass over the def list, which already unfolds
a whole chain in a single "round" -- so it could not test depth at all. Here depth is
defined properly: rho^k unfolds the STORE expression exactly k levels.

    rho^0(e) = e
    rho^(k+1)(e) = rho^k(e) with every remaining temporary replaced by its RHS, once.

Run:  python eval/research/p2b_depth.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real, tokenize

print(__doc__)


def unfold_k(blk, k):
    """Unfold the store expression exactly k levels; keep remaining defs as defs."""
    rhs = {n: e for n, e in blk.defs}
    store = blk.store
    for _ in range(k):
        toks = tokenize(store)
        if not any(t in rhs for t in toks):
            break
        store = " ".join(("(" + rhs[t] + ")") if t in rhs else t for t in toks)
    return Block(blk.inputs, blk.defs, store, blk.name)


def chain(n):
    defs = [("a1", "x + 1")] + [("a%d" % i, "a%d + 1" % (i - 1)) for i in range(2, n + 1)]
    return Block(["x"], defs, "a%d - x - %d" % (n, n), "chain-%d" % n)


print("%-10s %-9s %s" % ("block", "SUBST", "  ".join("k=%-5d" % k for k in range(0, 8))))
print("-" * 72)
for n in (1, 2, 3, 4, 5, 6):
    blk = chain(n)
    truth = subst_real(blk, "x")
    cells = []
    for k in range(0, 8):
        b2 = unfold_k(blk, k)
        cells.append("REAL" if site_real(b2, "x") else "FAKE")
    print("%-10s %-9s %s" % (blk.name, "REAL" if truth else "FAKE",
                             "  ".join("%-7s" % c for c in cells)))

print("""
Reading: for chain-n the answer only becomes FAKE at k = n, i.e. exactly when the last
temporary has been unfolded down to the input x. For every fixed bound d there is a
block (chain-(d+1)) on which depth-d substitution still reports REAL while the true
answer is FAKE.

=> No bounded-depth substitution is sufficient. The transformation must be the
   TRANSITIVE (least-fixed-point) unfolding down to program inputs.""")

# Same for a bitwise chain, to show it is not an artifact of additive cancellation.
print("\n=== bitwise chain (mask narrowing), same phenomenon ===")


def maskchain(n):
    defs = [("a1", "x & 0xff")]
    for i in range(2, n + 1):
        defs.append(("a%d" % i, "a%d & %s" % (i - 1, hex(0xff >> (i - 1)))))
    # a_n has at most (8-(n-1)) low bits set; AND with a disjoint high bit is 0
    return Block(["x"], defs, "a%d & 0x100" % n, "maskchain-%d" % n)


print("%-14s %-9s %s" % ("block", "SUBST", "  ".join("k=%-5d" % k for k in range(0, 6))))
print("-" * 62)
for n in (1, 2, 3, 4):
    blk = maskchain(n)
    truth = subst_real(blk, "x")
    cells = ["REAL" if site_real(unfold_k(blk, k), "x") else "FAKE" for k in range(0, 6)]
    print("%-14s %-9s %s" % (blk.name, "REAL" if truth else "FAKE",
                             "  ".join("%-7s" % c for c in cells)))
