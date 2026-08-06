#!/usr/bin/env python3
"""AUDIT -- width side conditions. At which bitvector widths do T2 and T3 actually hold?

The write-up asserts the theorems over BV32 without stating any width hypothesis.
An Isabelle formalisation would be parametric in the width w, so any width at which the
witness degenerates is a hypothesis that MUST appear in the theorem statement.

Run:  python eval/research/a3_widths.py
"""
import sys, os
from z3 import BitVec, Solver, sat, unsat, Bool, And, Or, Not

print(__doc__)


def nonconst(fn, w):
    """Is fn : BV_w -> BV_w non-constant?"""
    a, b = BitVec("a", w), BitVec("b", w)
    s = Solver()
    s.add(fn(a) != fn(b))
    return s.check() == sat


WIDTHS = [1, 2, 3, 4, 8, 16, 32]

print("=== ground truth of the four programs, per width ===")
print("%-5s %-22s %-22s %-22s %-22s" % ("w", "P1 store=r0", "P2 store=r0+1",
                                        "P3 store=(r0+1)-r0", "P4 store=(r0+r0)-r0"))
print("-" * 96)
truths = {}
for w in WIDTHS:
    t1 = nonconst(lambda r: r, w)
    t2 = nonconst(lambda r: r + 1, w)
    t3 = nonconst(lambda r: (r + 1) - r, w)
    t4 = nonconst(lambda r: (r + r) - r, w)
    truths[w] = (t1, t2, t3, t4)
    print("%-5s %-22s %-22s %-22s %-22s" % (w,
          "REAL" if t1 else "FAKE", "REAL" if t2 else "FAKE",
          "REAL" if t3 else "FAKE", "REAL" if t4 else "FAKE"))
print("   required pattern for the argument: REAL REAL FAKE REAL")

print("""
=== the dependence BITS used by T3, per width ===
T3 needs P3 and P4 to present the SAME dependence bits. dep(t, r0) is
'is t's defining expression non-constant in r0?'""")
print("%-5s %-18s %-18s %s" % ("w", "dep(r0+1, r0)", "dep(r0+r0, r0)", "T3 witness valid?"))
print("-" * 70)
for w in WIDTHS:
    d3 = nonconst(lambda r: r + 1, w)
    d4 = nonconst(lambda r: r + r, w)
    t3, t4 = truths[w][2], truths[w][3]
    valid = (d3 == d4) and (t3 != t4)
    print("%-5s %-18s %-18s %s" % (w, "REAL" if d3 else "FAKE", "REAL" if d4 else "FAKE",
                                   "YES" if valid else "*** NO -- witness degenerates ***"))
print("""
   => at w = 1, r0+r0 == 0 identically, so dep(r0+r0,r0) = FAKE while
      dep(r0+1,r0) = REAL. The two programs are then DISTINGUISHABLE at the
      dependence-bit abstraction and the T3 witness fails.
      T3 therefore carries a genuine hypothesis: WIDTH >= 2.
      This hypothesis is absent from findings-semantic-transformations.md.""")

print("""
=== T2 (any local f under the shipped composition), per width ===
T2's contradiction is propositional; check whether it survives at every width.""")
print("%-5s %-10s %s" % ("w", "SAT?", "verdict"))
print("-" * 44)
for w in WIDTHS:
    t1, t2, t3, t4 = truths[w]

    f = {}
    def F(a, b):
        k = (a, b)
        if k not in f:
            f[k] = Bool("f_%s_%s" % (a, b))
        return f[k]

    s = Solver()
    c1 = And(F("t", "t"), F("r0", "r0"))
    c2 = And(F("t", "t"), F("r0+1", "r0"))
    c3 = Or(And(F("t-r0", "t"), F("r0+1", "r0")), F("t-r0", "r0"))
    c4 = Or(And(F("t-r0", "t"), F("r0+r0", "r0")), F("t-r0", "r0"))
    s.add(c1 if t1 else Not(c1))
    s.add(c2 if t2 else Not(c2))
    s.add(c3 if t3 else Not(c3))
    s.add(c4 if t4 else Not(c4))
    r = s.check()
    print("%-5s %-10s %s" % (w, r, "T2 HOLDS" if r == unsat else "T2 FAILS (f exists)"))
print("""
   => T2's contradiction does NOT use f("r0+r0","r0") at all (P3 forces both
      f("t-r0","t") and f("t-r0","r0") to 0, which kills P4's disjunction whatever
      f("r0+r0","r0") is). T2 is therefore width-robust where T3 is not.""")

print("""
=== summary of width hypotheses ===
   T1  : holds for every w >= 1  (it only needs P3 and P4 to differ in truth)
   T2  : holds for every w >= 1  (propositional contradiction, verified above)
   T3  : requires w >= 2         (at w = 1 the witness degenerates)
   Any Isabelle statement must carry these hypotheses explicitly.""")
