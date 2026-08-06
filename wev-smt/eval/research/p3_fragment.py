#!/usr/bin/env python3
"""TASK 4 -- for which environments does SITE = SUBST? Find the maximal fragment.

Tests a sequence of candidate conditions on the ENVIRONMENT map
    rho = (rho(t1),...,rho(tk)) : BV^m -> BV^k
refuting each until one survives.

Run:  python eval/research/p3_fragment.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real, compare, show

print(__doc__)

# ── Conjecture 1: "rho surjective => SITE = SUBST" ─────────────────────────────
print("""
=== Conjecture C1: surjectivity of rho suffices ===
rho(t1)=x1, rho(t2)=x1+x2 is SURJECTIVE onto BV^2 (given any (a,b) take x1=a, x2=b-a).
If C1 held, SITE and SUBST would agree on every store expression over t1,t2.""")

c1 = [
    Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 + x2")], "t2 - t1",
          "C1: t1:=x1; t2:=x1+x2; store t2-t1  (= x2)"),
    Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 ^ x2")], "t2 ^ t1",
          "C1: t1:=x1; t2:=x1^x2; store t2^t1  (= x2)"),
]
n = show([r for b in c1 for r in compare(b, inputs=["x1"]) ] , "C1 test (input x1)")
print("""   => C1 REFUTED. rho is surjective, yet SITE says REAL and SUBST says FAKE.
   Surjectivity controls the RANGE of the site registers but not their CORRELATION:
   moving x1 moves t1 and t2 TOGETHER, while the site query moves one at a time.""")

# ── Conjecture 2: surjective + each t depends on >= 1 input, pairwise disjoint ──
print("""
=== Conjecture C2: rho bijective per register AND registers depend on DISJOINT inputs ===
Each rho(ti) is a bijection of a single input, and distinct ti use distinct inputs.
Then varying x_i moves exactly t_i, over its whole range, independently of the rest.""")

c2 = [
    Block(["x1", "x2"], [("t1", "x1 + 1"), ("t2", "~x2")], "t1 - t2",
          "C2: t1:=x1+1; t2:=~x2; store t1-t2"),
    Block(["x1", "x2"], [("t1", "x1 ^ 7"), ("t2", "0 - x2")], "t1 & t2",
          "C2: t1:=x1^7; t2:=-x2; store t1&t2"),
    Block(["x1", "x2"], [("t1", "3 * x1"), ("t2", "x2 + 9")], "t1 * t2 + t1 - t2",
          "C2: t1:=3x1; t2:=x2+9; store t1*t2+t1-t2"),
    Block(["x1", "x2"], [("t1", "x1"), ("t2", "x2")], "t1 - t2",
          "C2: identity env; store t1-t2"),
    Block(["x1", "x2"], [("t1", "x1"), ("t2", "x2")], "(t1 & t2) | (t1 ^ t2)",
          "C2: identity env; store (t1&t2)|(t1^t2)"),
    Block(["x1", "x2"], [("t1", "x1 + 5"), ("t2", "x2 * 7")], "t1 ^ t2 ^ t1",
          "C2: t1:=x1+5; t2:=7x2; store t1^t2^t1"),
]
n2 = show([r for b in c2 for r in compare(b)], "C2 test (all inputs)")
print("   => C2 survives on these: %d separators." % n2)

# ── does dropping EITHER condition of C2 reopen the gap? ───────────────────────
print("""
=== C2 is tight: drop one condition at a time ===""")
drop = [
    # drop bijectivity (range restriction), keep disjointness
    Block(["x1", "x2"], [("t1", "x1 & 1"), ("t2", "x2")], "t1 & 2",
          "drop BIJECTIVITY: t1:=x1&1 (range {0,1})"),
    Block(["x1", "x2"], [("t1", "x1 * 4"), ("t2", "x2")], "t1 & 3",
          "drop BIJECTIVITY: t1:=4*x1 (range 4Z)"),
    # drop disjointness (sharing), keep bijectivity
    Block(["x1"], [("t1", "x1"), ("t2", "x1 + 1")], "t2 - t1",
          "drop DISJOINTNESS: both from x1, both bijective"),
    Block(["x1"], [("t1", "x1 ^ 3"), ("t2", "x1 ^ 5")], "t1 ^ t2",
          "drop DISJOINTNESS: t1:=x1^3, t2:=x1^5"),
    # drop single-input (a register depending on two inputs)
    Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 + x2")], "t2 - t1",
          "drop SINGLE-INPUT: t2 depends on x1 and x2"),
]
n3 = show([r for b in drop for r in compare(b, inputs=["x1"])],
          "each drops exactly one C2 condition")
print("   => every relaxation of C2 produces a separator: C2's conditions are all necessary.")

# ── is the fragment SYNTACTIC in the store expression? ────────────────────────
print("""
=== Is the good fragment characterizable by the OPERATORS of e alone? ===
For each binary operator, hold the store expression shape fixed as `t1 OP t2` and ask
whether SOME environment makes it a separator. If every operator admits one, then no
condition on e's syntax can identify the safe fragment -- the fragment is a property
of the ENVIRONMENT, not of the expression.""")
ops = [("-", "x", "x"), ("^", "x", "x"), ("&", "x", "~x"), ("|", "x", "~x"),
       ("+", "x", "0 - x"), ("*", "x", "x & 0"), ("==", "x", "x")]
rows = []
for op, d1, d2 in ops:
    b = Block(["x"], [("t1", d1), ("t2", d2)], "t1 %s t2" % op,
              "op %-2s : t1:=%s, t2:=%s" % (op, d1, d2))
    rows += compare(b)
show(rows, "one cancelling environment per operator")
print("""   => a separator exists for EVERY binary operator in the grammar.
   The safe fragment cannot be described by restricting e's operators.""")
