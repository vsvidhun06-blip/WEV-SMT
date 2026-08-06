#!/usr/bin/env python3
"""Closing the maximality conjecture.

findings §2 left maximality of C2 in the CORRELATION direction conjectural, because the
separator used there (`t2 == rho2(rho1^-1(t1))`) needs the inverse to be expressible.

There is a uniform construction that avoids inverses entirely. Let
    G = image of the environment map on FV(e)  (subset of BV^k)
If G is a PROPER subset of BV^k, pick any c not in G and take

    e := (t1 == c1) & (t2 == c2) & ... & (tk == ck)

Then e is identically 0 on G -- so SUBST = FAKE -- while e(c) = 1, so e is non-constant
on BV^k and nonconst(e, ti) holds for every i (fix the others at c, vary ti). Hence
SITE = REAL whenever some rho_ti is non-constant in x. Only `==` and `&` are needed, both
in the shipped grammar, so the separator is ALWAYS expressible.

This covers BOTH C2 violations that make G proper:
   - some rho_t non-surjective        => the t-projection of G misses a value
   - sigma not injective (two site registers over one input) => |G| <= 2^w < 2^(w*k)

It does NOT cover the case where G = BV^k yet the environment is still correlated
(the C1 counterexample t1:=x1, t2:=x1+x2). That residual case is analysed at the end.

Run:  python eval/research/p10_maximality.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, compare, show, site_real, subst_real

print(__doc__)

# ── the uniform point-indicator separator, no inverses used ────────────────────
cases = [
    # sigma not injective: t1,t2 both from x. G = {(a, a+1)} is proper. (5,9) not in G.
    (Block(["x"], [("t1", "x"), ("t2", "x + 1")], "(t1 == 5) & (t2 == 9)"),
     "sigma non-injective, both bijective; c=(5,9) off the graph"),
    # a nastier pair: no easy closed-form inverse relation needed
    (Block(["x"], [("t1", "x * x"), ("t2", "x ^ 0x5a5a")], "(t1 == 3) & (t2 == 7)"),
     "sigma non-injective, rho1 NOT invertible; c=(3,7)"),
    (Block(["x"], [("t1", "x & 0xff"), ("t2", "x | 0xff00")], "(t1 == 1) & (t2 == 2)"),
     "both non-surjective; c=(1,2)"),
    # single register, non-surjective: degenerate k=1 case of the same construction
    (Block(["x"], [("t1", "x & 1")], "t1 == 2"),
     "k=1 non-surjective; c=2 outside im"),
    (Block(["x"], [("t1", "x * 6")], "t1 == 1"),
     "k=1 non-surjective (im = multiples of 2); c=1"),
    # three registers over one input
    (Block(["x"], [("t1", "x"), ("t2", "x + 1"), ("t3", "x + 2")],
           "(t1 == 0) & (t2 == 0) & (t3 == 0)"),
     "k=3 over one input; c=(0,0,0) off the graph"),
]
rows = []
for blk, note in cases:
    blk.name = note
    rows += compare(blk, inputs=["x"])
n = show(rows, "uniform point-indicator separator (uses only == and &)")
print("   => %d/%d are separators, with NO inverse expression anywhere." % (n, len(rows)))
print("""   This upgrades the correlation-direction maximality claim from CONJECTURED to
   PROVED for every environment whose joint image G is a proper subset of BV^k --
   which includes every violation of C2's injectivity condition.""")

# ── the residual case: G = BV^k but still correlated ──────────────────────────
print("""
=== residual open case: G = BV^k with correlation ===
t1 := x1, t2 := x1 + x2 has joint image ALL of BV^2, so no point-indicator exists.
It is nonetheless a separator, via t2 - t1 = x2. The separator therefore has to be
constant along each FIBRE (the curve traced by varying x1 with x2 fixed) rather than
off the image. Whether such an expression always exists in the grammar for an arbitrary
correlated-but-surjective environment is the one part of maximality still open.""")
resid = [
    (Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 + x2")], "t2 - t1"),
     "fibre-constant separator exists (= x2)"),
    (Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 ^ x2")], "t2 ^ t1"),
     "fibre-constant separator exists (= x2)"),
    (Block(["x1", "x2"], [("t1", "x1"), ("t2", "3 * x1 + x2")], "t2 - 3 * t1"),
     "fibre-constant separator exists (= x2)"),
]
rows = []
for blk, note in resid:
    blk.name = note
    rows += compare(blk, inputs=["x1"])
show(rows, "correlated-but-surjective: separators found by hand")
print("""   In each case the environment is an invertible LINEAR change of coordinates, and
   the separator is the complementary coordinate. Conjecture (unproved): whenever the
   environment map is a grammar-expressible bijection of BV^m, the complementary
   coordinate is grammar-expressible too, so a separator always exists. This is the only
   remaining gap in the maximality argument.""")
