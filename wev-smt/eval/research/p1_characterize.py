#!/usr/bin/env python3
"""TASK 1 -- characterize the gap between SITE (shipped) and SUBST (candidate).

Starts from the three established witnesses and asks what they actually have in common.
Run:  python eval/research/p1_characterize.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, compare, show, nonconst_site, subst_real, site_real

print(__doc__)

# ── the three established witnesses ─────────────────────────────────────────────
known = [
    Block(["r"], [("t", "r")], "r - t", "W1 copy:      t:=r;      store r-t"),
    Block(["x"], [("t", "x + 1")], "t - x", "W2 affine:    t:=x+1;    store t-x"),
    Block(["x"], [("t", "x & 1")], "t & 2", "W3 mask:      t:=x&1;    store t&2"),
]
show([r for b in known for r in compare(b)], "TASK 1a -- the three established witnesses")

# ── what distinguishes them? two candidate explanations ─────────────────────────
#
# (a) SHARING   : the site expression has >= 2 free registers whose definitions
#                 mention a COMMON input, so they are not independent.
# (b) RANGE     : a free register of the site expression cannot take all 2^32 values,
#                 so quantifying it over all of BV32 is unsound.
#
# W1 and W2 have two free site registers -> candidates for (a).
# W3 has ONE free site register (t) -> (a) cannot explain it. Test (b) directly.

print("""
Analysis of the site expressions themselves (no environment):""")
for expr, reg in [("r - t", "r"), ("r - t", "t"), ("t - x", "t"), ("t - x", "x"),
                  ("t & 2", "t")]:
    print("   nonconst_site(%-8s, %-2s) = %s"
          % (repr(expr), reg, "REAL" if nonconst_site(expr, reg) else "FAKE"))
print("""
   All REAL -- every site expression is genuinely non-constant when its registers
   are independent. The oracle is not wrong; the INDEPENDENCE ASSUMPTION is.""")

# ── isolate cause (a): sharing, with NO range restriction ───────────────────────
# t := x is a bijection, so t ranges over all of BV32: no range restriction at all.
# The gap must therefore come purely from sharing.
sharing_only = [
    Block(["x"], [("t", "x")], "x - t", "SHARING only: t:=x (bijective); store x-t"),
    Block(["x"], [("t", "x + 1")], "t - x", "SHARING only: t:=x+1 (bijective); store t-x"),
    Block(["x"], [("t", "~x")], "t + x", "SHARING only: t:=~x (bijective); store t+x"),
    Block(["x"], [("t", "0 - x")], "t + x", "SHARING only: t:=-x (bijective); store t+x"),
    Block(["x"], [("t", "3 * x")], "t - 3 * x", "SHARING only: t:=3x (3 odd=bijective)"),
]
show([r for b in sharing_only for r in compare(b)],
     "TASK 1b -- cause (a) SHARING alone (all defs bijective => no range restriction)")

# ── isolate cause (b): range restriction, with NO sharing ──────────────────────
# ONE temporary, used ONCE, no other register in the site expression. Sharing is
# impossible. Any gap is due purely to the temporary's restricted range.
range_only = [
    Block(["x"], [("t", "x & 1")], "t & 2", "RANGE only: im(x&1)={0,1};  store t&2"),
    Block(["x"], [("t", "x & 1")], "t * 2 - t - t", "RANGE only: store 2t-t-t"),
    Block(["x"], [("t", "x * 2")], "t & 1", "RANGE only: im(2x) even;    store t&1"),
    Block(["x"], [("t", "x * 4")], "t & 3", "RANGE only: im(4x)=4Z;      store t&3"),
    Block(["x"], [("t", "x & 0")], "t + 5", "RANGE only: im(x&0)={0};    store t+5"),
    Block(["x"], [("t", "x | 0xffffffff")], "t + 1", "RANGE only: im={-1};  store t+1"),
    Block(["x"], [("t", "x == x")], "t & 2", "RANGE only: im(x==x)={1};   store t&2"),
    Block(["x"], [("t", "x * x")], "t & 2", "RANGE only: squares mod 2^32; store t&2"),
]
show([r for b in range_only for r in compare(b)],
     "TASK 1c -- cause (b) RANGE RESTRICTION alone (one temp, one use, no sharing)")

# ── are the two causes independent? ────────────────────────────────────────────
print("""
=== TASK 1d -- are SHARING and RANGE independent causes? ===

A separator in 1b has >=2 site registers over a bijective def: sharing, no range loss.
A separator in 1c has exactly ONE site register used once: range loss, no sharing.
If both groups produce separators, neither cause subsumes the other, and any
transformation that fixes only one of them is incomplete.""")
