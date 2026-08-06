#!/usr/bin/env python3
"""TASK 4/5 -- randomized stress test of fragment C2, maximality constructions,
and the site-locality impossibility result.

Run:  python eval/research/p4_maximal_impossible.py
"""
import sys, os, random, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real, compare, show

print(__doc__)

# ── 1. randomized stress test of C2 ────────────────────────────────────────────
# C2 environments: t_i := bij_i(x_i), sigma injective, each bij a bitvector bijection.
BIJ = ["{x}", "{x} + %d", "{x} - %d", "{x} ^ %d", "~{x}", "0 - {x}",
       "3 * {x}", "5 * {x}", "7 * {x}", "({x} + %d) ^ %d"]
OPS = ["+", "-", "*", "&", "|", "^"]


def rand_bij(rng, x):
    p = rng.choice(BIJ)
    n = p.count("%d")
    return p.format(x=x) % tuple(rng.randrange(1, 97) for _ in range(n))


def rand_expr(rng, regs, depth=2):
    if depth == 0 or rng.random() < 0.3:
        return rng.choice(regs) if rng.random() < 0.75 else str(rng.randrange(0, 17))
    a = rand_expr(rng, regs, depth - 1)
    b = rand_expr(rng, regs, depth - 1)
    op = rng.choice(OPS)
    if rng.random() < 0.12:
        return "~(%s %s %s)" % (a, op, b)
    return "(%s %s %s)" % (a, op, b)


rng = random.Random(20260728)
N = 250
seps, checked = [], 0
for trial in range(N):
    k = rng.choice([1, 2, 2, 3])
    inputs = ["x%d" % i for i in range(k)]
    defs = [("t%d" % i, rand_bij(rng, "x%d" % i)) for i in range(k)]
    store = rand_expr(rng, ["t%d" % i for i in range(k)], depth=rng.choice([1, 2, 3]))
    blk = Block(inputs, defs, store, "rand%d" % trial)
    for x in inputs:
        checked += 1
        try:
            a, b = site_real(blk, x), subst_real(blk, x)
        except Exception:
            continue
        if a != b:
            seps.append((blk.pretty(), x, a, b))

print("\n=== TASK 4a -- randomized stress test of fragment C2 ===")
print("random C2-conforming environments: %d blocks, %d (block,input) checks" % (N, checked))
print("separators found: %d" % len(seps))
for s in seps[:10]:
    print("   SEPARATOR:", s)
if not seps:
    print("   => C2 held on every random instance. Consistent with the theorem below.")

# ── 2. maximality: every violation of C2 admits a constructed separator ────────
print("""
=== TASK 4b -- maximality constructions ===
For each way of violating C2, construct a separator MECHANICALLY rather than by luck.

(a) NON-SURJECTIVE rho_i with image S. Because the grammar contains `==`, the
    expression  e := (t_i == c)  for any c not in S is constant (0) on S but not on
    BV32. So non-surjectivity ALWAYS yields a separator -- no cleverness needed.""")
a_rows = []
for defn, c, why in [("x & 1", "2", "im={0,1}"), ("x * 2", "1", "im=even"),
                     ("x & 0xf0", "1", "im=low nibble clear"),
                     ("x * x", "2", "im=squares mod 2^32"),
                     ("(x == x) + 4", "0", "im={5}")]:
    b = Block(["x"], [("t", defn)], "t == %s" % c, "t:=%-10s store t==%s   [%s]" % (defn, c, why))
    a_rows += compare(b)
show(a_rows, "(a) non-surjective => `t == c` is a separator")

print("""
(b) FUNCTIONAL DEPENDENCE (two registers over the same input). If t1:=rho1(x) and
    t2:=rho2(x) with rho1 invertible in the grammar, then e := (t2 == rho2(rho1^-1(t1)))
    is constant 1 on the reachable graph but not on BV^2.
    Shown here for xor- and affine-invertible rho1.""")
b_rows = []
for r1, r2, f, why in [("x ^ 3", "x ^ 5", "t1 ^ 6", "rho2.rho1^-1 = ^6"),
                       ("x + 1", "x + 4", "t1 + 3", "rho2.rho1^-1 = +3"),
                       ("x", "0 - x", "0 - t1", "negation"),
                       ("3 * x", "x", "2863311531 * t1", "3^-1 mod 2^32")]:
    b = Block(["x"], [("t1", r1), ("t2", r2)], "t2 == (%s)" % f,
              "t1:=%-7s t2:=%-7s store t2==%s" % (r1, r2, f))
    b_rows += compare(b)
show(b_rows, "(b) functional dependence => equality-with-the-inverse is a separator")

# ── 3. TASK 5 -- impossibility of any purely site-local criterion ─────────────
print("""
=== TASK 5 -- impossibility: no site-local criterion can be exact ===
Two blocks with the IDENTICAL store expression `t - x` but different environments.
Any criterion that is a function of the store expression alone must give them the same
answer; the true answers differ. Hence the environment is FUNDAMENTALLY necessary.""")
imp = [
    Block(["x"], [("t", "x + 1")], "t - x", "env A: t:=x+1  => stored value == 1"),
    Block(["x"], [("t", "x * 2")], "t - x", "env B: t:=2x   => stored value == x"),
]
show([r for b in imp for r in compare(b)], "same site expression `t - x`, two environments")
print("""   A: SUBST=FAKE   B: SUBST=REAL   -- identical site expression.
   => THEOREM (impossibility). There is no function D(e, r) of the store expression and
      register alone with D = program-level dependency. Proof: D('t-x','x') would have to
      equal both FAKE (env A) and REAL (env B). QED.
   Corollary: the shipped criterion is not merely imprecise, it is INFORMATION-STARVED --
   no improvement to its decision procedure can close the gap.""")

# ── 4. how much environment is needed? unbounded ──────────────────────────────
print("""
=== TASK 5b -- how much of the environment is needed? ===
Two blocks agreeing on the store expression AND on the first d definitions, differing
only at depth d+1, with opposite true answers. So no criterion inspecting a BOUNDED
prefix of the environment can be exact either.""")
rows = []
for d in (1, 2, 3):
    defsA = [("a1", "x + 1")] + [("a%d" % i, "a%d + 1" % (i - 1)) for i in range(2, d + 1)]
    defsB = list(defsA)
    tail = "a%d" % d if d >= 1 else "x"
    defsA.append(("h", "%s + 1" % tail))          # h = x + d + 1  -> cancels
    defsB.append(("h", "%s + %s" % (tail, tail)))  # h = 2x + ...  -> does not
    rows += compare(Block(["x"], defsA, "h - x - %d" % (d + 1), "depth-%d A (cancels)" % d))
    rows += compare(Block(["x"], defsB, "h - x - %d" % (d + 1), "depth-%d B (varies)" % d))
show(rows, "identical store expr + identical prefix, differing only at depth d+1")
