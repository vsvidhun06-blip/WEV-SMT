#!/usr/bin/env python3
"""TASK 4 (explicit coverage) -- branching, loops, aliasing, shifts, overflow,
multiplication, masking, multiple temporaries, mixed arithmetic/bitwise.

Run:  python eval/research/p5_boundaries.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, compare, show, subst_real, site_real, build, W
from z3 import BitVec, BitVecVal, If, Solver, sat, LShR

print(__doc__)

# ── multiple temporaries / mixed arithmetic-bitwise / masking ──────────────────
mixed = [
    Block(["x"], [("a", "x & 0xff"), ("b", "x & 0xff00")], "a & b",
          "disjoint masks: (x&ff)&(x&ff00) == 0"),
    Block(["x"], [("a", "x | 0xffffffff")], "a + 1", "saturating or"),
    Block(["x"], [("a", "x * 2"), ("b", "a + 1")], "b & 1", "2x+1 always odd"),
    Block(["x"], [("a", "x + x"), ("b", "x * 2")], "a - b", "two spellings of 2x"),
    Block(["x"], [("a", "x ^ 0xff"), ("b", "x & 0xff")], "(a ^ x) - 0xff",
          "xor round-trip"),
    Block(["x"], [("a", "x & 1"), ("b", "a * a")], "b - a",
          "idempotent on {0,1}: a*a == a"),
    Block(["x", "y"], [("a", "x & y"), ("b", "x | y")], "a + b - x - y",
          "a+b == x+y  (inclusion-exclusion)"),
    Block(["x", "y"], [("a", "x ^ y"), ("b", "x & y")], "a + b + b - x - y",
          "x^y + 2(x&y) == x+y"),
]
show([r for b in mixed for r in compare(b)],
     "multiple temporaries, masking, mixed arithmetic/bitwise")

# ── overflow / fixed width is essential ───────────────────────────────────────
print("""
=== overflow: the range phenomenon is SPECIFIC to fixed width ===
Over the integers x*2 is injective, so `t:=2x; store t&1` would carry a dependency.
Over BV32 it is 2-to-1 with image the even residues, so t&1 is identically 0.
Fixed-width wraparound is what CREATES the range restriction.""")
show([r for b in [
    Block(["x"], [("t", "x * 2")], "t & 1", "2x is even mod 2^32"),
    Block(["x"], [("t", "x * 4")], "t & 3", "4x  = 0 mod 4"),
    Block(["x"], [("t", "x * 0x80000000")], "t & 0x7fffffff",
          "2^31*x has only the top bit"),
    Block(["x"], [("t", "x + x + x + x")], "t & 3", "4x by repeated addition"),
] for r in compare(b)], "overflow-induced range restriction")

# ── shifts: outside the shipped grammar ───────────────────────────────────────
print("""
=== shifts ===
QfbvConstancyOracle's grammar is  | ^ & + - * ~ unary- and relational ops.
There is NO shift. The oracle returns null for a shift expression and the detector
falls back to a CONSERVATIVE `semantic` classification. So every shift-carrying site is
reported REAL regardless of content. Below, SUBST (which does model shifts) shows what
is being missed.""")
show([r for b in [
    Block(["x"], [("t", "x << 1")], "t & 1", "x<<1 is even"),
    Block(["x"], [("t", "x >> 31")], "t & 2", "logical x>>31 in {0,1}"),
    Block(["x"], [("t", "x << 16")], "t & 0xffff", "low half cleared"),
] for r in compare(b)], "shifts (outside the shipped grammar => auto-REAL)")

# ── branching: straight-line substitution is not enough ───────────────────────
print("""
=== branching ===
A block is no longer straight-line. Modelled here by an explicit `ite` written with the
grammar's relational operator, which is how a guarded assignment would have to be
encoded. The LBfc shape (both arms store the SAME value) is the motivating case.""")
c = "(x == 1)"
branch = [
    # both arms write 1  -> unconditional, fake ctrl dependency
    Block(["x"], [("t", "%s * 1 + (1 - %s) * 1" % (c, c))], "t",
          "LBfc: if(x==1) y=1 else y=1"),
    # arms differ -> genuine
    Block(["x"], [("t", "%s * 1 + (1 - %s) * 2" % (c, c))], "t",
          "LBcd: if(x==1) y=1 else y=2"),
    # arms differ syntactically but agree semantically
    Block(["x"], [("t", "%s * (3 - 2) + (1 - %s) * (5 - 4)" % (c, c))], "t",
          "both arms equal 1 after arithmetic"),
]
show([r for b in branch for r in compare(b)], "branching encoded with ite/select")
print("""   With the guard encoded as a term, substitution handles branching exactly.
   The requirement is that the front end EXPOSE the guard -- an if/else flattened to an
   unconditional store (the current C front end's behaviour) destroys the information
   before any criterion can see it. That is a front-end gap, not a criterion gap.""")

# ── loops: substitution does not terminate ────────────────────────────────────
print("""
=== loops ===
`for i in 0..n-1: s := s + x` leaves s == n*x, so the store `s - x*n` is the constant 0
-- but ONLY when the unrolling depth k equals the trip count n. Below, the loop is
unrolled k = 1..4 times while the store cancels against a FIXED n = 3. The answer is
FAKE exactly at k = 3: an unrolling that does not match the trip count answers a
different question.""")


def unrolled_acc(k, n):
    defs = [("s0", "0")]
    for i in range(1, k + 1):
        defs.append(("s%d" % i, "s%d + x" % (i - 1)))
    return Block(["x"], defs, "s%d - x * %d" % (k, n),
                 "unrolled k=%d, store cancels n=%d" % (k, n))


show([r for b in [unrolled_acc(k, 3) for k in (1, 2, 3, 4)] for r in compare(b)],
     "bounded unrolling vs a fixed trip count (n = 3)")
print("""   Each unrolling is decided exactly, but it decides the k-th iterate, not the loop.
   When the trip count is symbolic no finite k suffices, and deciding whether a loop's
   transfer function is constant in an input is undecidable in general (it subsumes
   halting/reachability). Loops are therefore OUTSIDE any quantifier-free criterion.
   NOTE: the undecidability claim here is by standard reduction, not established by this
   probe; the probe only establishes the depth-sensitivity of unrolling.""")

# ── memory / aliasing ─────────────────────────────────────────────────────────
print("""
=== aliasing / value flow through memory ===
If the value reaches the store through a memory cell, substitution requires knowing that
the load reads exactly that store. Modelled here as the two extremes: `must-alias`
(the load is replaced by the stored value) and `may-alias` (the load is an unconstrained
fresh input).""")
must = Block(["x"], [("m", "x"), ("t", "m")], "t - x", "must-alias: m:=x; t:=m; store t-x")
may = Block(["x", "m2"], [("t", "m2")], "t - x", "may-alias: t:=<unknown>; store t-x")
show(compare(must, inputs=["x"]) + compare(may, inputs=["x"]),
     "memory: must-alias vs may-alias")
print("""   must-alias: FAKE (correctly). may-alias: REAL (correctly, and conservatively).
   So the criterion is sound under an unknown-alias assumption, but recovering the fake
   case needs an alias oracle -- information strictly outside the expression language.""")
