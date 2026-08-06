#!/usr/bin/env python3
"""AUDIT of the impossibility claim -- reconstructed from the repository, not from
findings-semantic-transformations.md.

Reconstruction (from src/main/java/wev/smt/parse/LitmusParser.java, referencedReads +
deriveReg + classify, verified against the shipped Java by scratchpad/audit1.jsh):

    the detector summarises each register `reg` by a BOOLEAN per load x:
        F(x, reg) = true            if reg IS the load x
        F(x, reg) = S(x, def(reg))  otherwise
    and at a store site with value expression e:
        S(x, e) = OR over reg in ids(e) of [ classify(e, reg).real  AND  F(x, reg) ]

    the shipped `classify` is a function of (expression, register) only.

Three DISTINCT claims are separated and attacked independently:

  T1  no total function of (store expression e, register r) alone is exact
  T2  no choice of the LOCAL classifier f(e,reg) makes the SHIPPED composition exact
  T3  no function of (store expression, boolean dependence bits) is exact
      -- i.e. the boolean dependence abstraction itself is insufficient,
         regardless of how it is composed

T1 is what the previous write-up asserts. T2 and T3 are what actually bear on the tool.

Run:  python eval/research/a1_audit_core.py
"""
import sys, os, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, subst_real, site_real
from z3 import Bool, And, Or, Not, Solver, sat, unsat, BitVec, BitVecVal

print(__doc__)

# ── the four programs ─────────────────────────────────────────────────────────
# Written exactly as the shipped C front end sees them (verified in audit1.jsh).
P = {
    "P1": Block(["r0"], [("t", "r0")], "t"),            # store value = r0
    "P2": Block(["r0"], [("t", "r0 + 1")], "t"),        # store value = r0+1
    "P3": Block(["r0"], [("t", "r0 + 1")], "t - r0"),   # store value = 1      (FAKE)
    "P4": Block(["r0"], [("t", "r0 + r0")], "t - r0"),  # store value = r0     (REAL)
}

print("=== A1. ground truth by SMT (BV32, exhaustive via Z3) ===")
truth = {}
for k in ("P1", "P2", "P3", "P4"):
    truth[k] = subst_real(P[k], "r0")
    print("   %-3s  %-28s truth = %s   shipped = %s"
          % (k, P[k].pretty(), "REAL" if truth[k] else "FAKE",
             "REAL" if site_real(P[k], "r0") else "FAKE"))
assert truth == {"P1": True, "P2": True, "P3": False, "P4": True}, truth
print("   ground truth confirmed: P3 is the only FAKE.")

# ── T1: is (e, r) alone enough? ───────────────────────────────────────────────
print("""
=== A2. T1 -- classifiers that see only (store expression, register) ===
P3 and P4 present the classifier with the IDENTICAL pair ("t - r0", "r0")
but have different truth values.""")
print("   P3 site pair = %-22s truth FAKE" % ('("%s", "r0")' % P["P3"].store))
print("   P4 site pair = %-22s truth REAL" % ('("%s", "r0")' % P["P4"].store))
print("   => T1 holds: no total function of (e,r) can be exact.")
print("""
   BUT NOTE THE DOMAIN PROBLEM. In P1 and P2 the store expression is "t" and the
   register whose dependency is being decided is the LOAD r0, which does not occur in
   the expression at all. `f("t", "r0")` has no information to work with -- the
   classifier class cannot even express the question in general. T1 is therefore true
   partly for a degenerate reason. See the vacuity audit.""")

# ── T2: any local f, shipped composition ─────────────────────────────────────
print("""
=== A3. T2 -- ANY local classifier f(e,reg) under the SHIPPED composition ===
Encoding the four programs as boolean constraints over f's values and asking Z3
whether any assignment satisfies all four.""")

f = {}
def F(a, b):
    k = (a, b)
    if k not in f:
        f[k] = Bool("f(%s | %s)" % (a, b))
    return f[k]

s = Solver()
# P1: S = f("t","t") AND [ f("r0","r0") AND true ]          truth REAL
s.add(And(F("t", "t"), F("r0", "r0")))
# P2: S = f("t","t") AND [ f("r0 + 1","r0") AND true ]      truth REAL
s.add(And(F("t", "t"), F("r0 + 1", "r0")))
# P3: S = [f("t - r0","t") AND f("r0 + 1","r0")] OR [f("t - r0","r0") AND true]  truth FAKE
s.add(Not(Or(And(F("t - r0", "t"), F("r0 + 1", "r0")), F("t - r0", "r0"))))
# P4: S = [f("t - r0","t") AND f("r0 + r0","r0")] OR [f("t - r0","r0")]          truth REAL
s.add(Or(And(F("t - r0", "t"), F("r0 + r0", "r0")), F("t - r0", "r0")))

r = s.check()
print("   constraint system over %d free boolean f-values: %s" % (len(f), r))
if r == unsat:
    print("   => UNSAT. T2 HOLDS: no local classifier f -- however precise, even an")
    print("      uncomputable oracle -- makes the shipped composition exact.")
    print("      The argument is purely propositional, so it is independent of any")
    print("      decision procedure. Hand derivation:")
    print("        P1  => f(t,t)=1, f(r0,r0)=1")
    print("        P2  => f(r0+1,r0)=1")
    print("        P3  => f(t-r0,r0)=0  and  f(t-r0,t)=0")
    print("        P4  => [0 AND *] OR 0 = 0, but truth is REAL   CONTRADICTION")
else:
    print("   => SAT! T2 IS FALSE. Model:", s.model())

# ── T3: boolean dependence bits, ANY composition ─────────────────────────────
print("""
=== A4. T3 -- is the BOOLEAN DEPENDENCE ABSTRACTION itself insufficient? ===
Strictly stronger than T2: allow ANY composition function g whatsoever, but let the
per-register summary be the boolean "does this register's value depend on load r0?".""")
rows = []
for k in ("P3", "P4"):
    blk = P[k]
    dep_t = subst_real(Block(blk.inputs, [], blk.def_of("t")), "r0")   # does t depend on r0?
    rows.append((k, blk.store, dep_t, True, truth[k]))
print("   %-4s %-10s %-14s %-14s %s" % ("prog", "store e", "dep(t,r0)", "dep(r0,r0)", "truth"))
for k, st, dt, dr, tr in rows:
    print("   %-4s %-10s %-14s %-14s %s" % (k, st, "REAL" if dt else "FAKE",
                                            "REAL" if dr else "FAKE",
                                            "REAL" if tr else "FAKE"))
same_input = rows[0][1] == rows[1][1] and rows[0][2] == rows[1][2] and rows[0][3] == rows[1][3]
diff_truth = rows[0][4] != rows[1][4]
print("   identical (store expr, dependence bits): %s ;  different truth: %s"
      % (same_input, diff_truth))
if same_input and diff_truth:
    print("""   => T3 HOLDS, and it is the sharp statement. Both programs present ANY
      composition g with exactly the same input, yet require different outputs.
      The missing information is not precision and not composition -- it is that a
      BOOLEAN "depends / does not depend" summary discards the VALUE relationship
      (t = r0+1 vs t = 2*r0) that decides the outcome.""")
else:
    print("   => T3 FAILS -- the two programs are distinguishable at this abstraction.")
