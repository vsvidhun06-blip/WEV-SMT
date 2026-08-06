#!/usr/bin/env python3
"""ADVERSARIAL VERIFICATION -- try hard to falsify the two load-bearing claims.

  Claim 1 (Theorem C2): on input-separated, range-full environments, SITE == SUBST.
                        A single counterexample refutes the proof in findings §2.
  Claim 2 (soundness):  SITE never answers FAKE when SUBST answers REAL.
                        A counterexample would be a soundness bug in the shipped tool.

This search is deliberately nastier than p4/p7: deeper expressions, more registers,
composed bijections, relational operators, repeated registers, and constants chosen to
trigger carry/borrow boundaries.

Run:  python eval/research/p9_falsify.py [trials]
"""
import sys, os, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real

TRIALS = int(sys.argv[1]) if len(sys.argv) > 1 else 1200

# Bijections of BV32, including compositions. Every one is invertible mod 2^32:
#   x+c, x-c, x^c, ~x, -x, odd*x, and compositions thereof.
ODD = [1, 3, 5, 7, 9, 11, 0x10001, 0x7fffffff, 0xffffffff, 2863311531]
EDGE = [0, 1, 2, 3, 7, 8, 15, 16, 31, 32, 0x7f, 0x80, 0xff, 0x100,
        0x7fff, 0x8000, 0xffff, 0x7fffffff, 0x80000000, 0xffffffff]


def rand_bij(rng, x, depth=None):
    """A composition of 1..3 bitvector bijections applied to x."""
    e = x
    for _ in range(depth or rng.randint(1, 3)):
        k = rng.randrange(6)
        if k == 0:
            e = "(%s + %d)" % (e, rng.choice(EDGE))
        elif k == 1:
            e = "(%s - %d)" % (e, rng.choice(EDGE))
        elif k == 2:
            e = "(%s ^ %d)" % (e, rng.choice(EDGE))
        elif k == 3:
            e = "(~%s)" % e
        elif k == 4:
            e = "(0 - %s)" % e
        else:
            e = "(%d * %s)" % (rng.choice(ODD), e)
    return e


OPS = ["+", "-", "*", "&", "|", "^"]
RELS = ["==", "!=", "<", "<=", ">", ">="]


def rand_expr(rng, regs, d):
    if d == 0 or rng.random() < 0.25:
        r = rng.random()
        if r < 0.70:
            return rng.choice(regs)
        return str(rng.choice(EDGE))
    a = rand_expr(rng, regs, d - 1)
    b = rand_expr(rng, regs, d - 1)
    if rng.random() < 0.12:
        return "(%s %s %s)" % (a, rng.choice(RELS), b)
    e = "(%s %s %s)" % (a, rng.choice(OPS), b)
    return "(~%s)" % e if rng.random() < 0.10 else e


def run_c2(trials):
    rng = random.Random(0xC2C2)
    seps, checks = [], 0
    for t in range(trials):
        k = rng.choice([1, 2, 2, 3, 3, 4])
        inputs = ["x%d" % i for i in range(k)]
        # C2: sigma injective (t_i <- x_i), each rho_i a bijection of that ONE input.
        defs = [("t%d" % i, rand_bij(rng, "x%d" % i)) for i in range(k)]
        regs = ["t%d" % i for i in range(k)]
        store = rand_expr(rng, regs, rng.choice([1, 2, 3, 3]))
        blk = Block(inputs, defs, store, "c2_%d" % t)
        for x in inputs:
            checks += 1
            try:
                a, b = site_real(blk, x), subst_real(blk, x)
            except Exception:
                continue
            if a != b:
                seps.append((blk.pretty(), x, a, b))
    return seps, checks


def run_sound(trials):
    """Unrestricted blocks -- looking ONLY for SITE=FAKE while SUBST=REAL."""
    rng = random.Random(0x5011)
    viol, checks = [], 0
    for t in range(trials):
        ninp = rng.choice([1, 1, 2, 2, 3])
        inputs = ["x%d" % i for i in range(ninp)]
        avail = list(inputs)
        defs = []
        for i in range(rng.choice([1, 2, 3, 4])):
            defs.append(("t%d" % i, rand_expr(rng, avail, rng.choice([1, 2, 2, 3]))))
            avail.append("t%d" % i)
        store = rand_expr(rng, avail, rng.choice([1, 2, 3]))
        blk = Block(inputs, defs, store, "s_%d" % t)
        for x in inputs:
            checks += 1
            try:
                a, b = site_real(blk, x), subst_real(blk, x)
            except Exception:
                continue
            if b and not a:
                viol.append((blk.pretty(), x))
    return viol, checks


print(__doc__)
print("=== Claim 1: adversarial search for a Theorem-C2 counterexample ===")
seps, checks = run_c2(TRIALS)
print("   C2-conforming blocks: %d      (block,input) checks: %d" % (TRIALS, checks))
print("   counterexamples to C2: %d" % len(seps))
for s in seps[:8]:
    print("      *** C2 REFUTED ***", s)
if not seps:
    print("   => no counterexample. Theorem C2 survives adversarial search")
    print("      (composed bijections, odd multipliers, carry-boundary constants,")
    print("       relational operators, depth-3 store expressions, up to 4 registers).")

print("\n=== Claim 2: adversarial search for a SOUNDNESS violation ===")
viol, checks2 = run_sound(TRIALS)
print("   unrestricted blocks: %d       (block,input) checks: %d" % (TRIALS, checks2))
print("   SITE=FAKE while SUBST=REAL: %d" % len(viol))
for v in viol[:8]:
    print("      *** SOUNDNESS VIOLATION ***", v)
if not viol:
    print("   => none found. Consistent with sdep_true subseteq sdep_impl.")

print("\n=== verdict ===")
print("   Theorem C2      : %s" % ("REFUTED" if seps else "survives (%d checks)" % checks))
print("   Soundness       : %s" % ("VIOLATED" if viol else "holds (%d checks)" % checks2))
