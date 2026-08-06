#!/usr/bin/env python3
"""Post-fix adversarial sweep: try hard to find ANY remaining unsound classification.

Wider than a7: deeper nesting, parenthesised sub-expressions, multiple registers, and
constants chosen at carry/borrow boundaries. An unsound case is one where the ground
truth VARIES with the register but the shipped classifier answers FAKE.

Run:  python eval/research/a9_postfix_sweep.py [trials]
"""
import sys, os, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from a5_shipped_model import shipped_classify
from depprobe import build
from z3 import BitVec, Solver, sat

TRIALS = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
REGS = ["r0", "r1"]
CONSTS = ["0", "1", "2", "3", "7", "8", "255", "256", "65535", "2147483647"]
OPS = ["+", "-", "&", "^", "|", "*"]


def varies(expr, reg, names):
    ea, eb = {}, {}
    for n in names:
        if n == reg:
            ea[n], eb[n] = BitVec("a", 32), BitVec("b", 32)
        else:
            v = BitVec("s_" + n, 32)
            ea[n] = eb[n] = v
    try:
        va, _ = build(expr, ea)
        vb, _ = build(expr, eb)
    except Exception:
        return None
    s = Solver()
    s.add(va != vb)
    return s.check() == sat


def rand_expr(rng, d):
    if d == 0 or rng.random() < 0.30:
        t = rng.choice(REGS) if rng.random() < 0.65 else rng.choice(CONSTS)
        return ("~" + t) if rng.random() < 0.18 else t
    a, b = rand_expr(rng, d - 1), rand_expr(rng, d - 1)
    e = "%s %s %s" % (a, rng.choice(OPS), b)
    if rng.random() < 0.35:
        e = "(%s)" % e
    if rng.random() < 0.10:
        e = "~(%s)" % e
    return e


rng = random.Random(0xF17ED)
unsound, over, agree, skipped = [], 0, 0, 0
for _ in range(TRIALS):
    e = rand_expr(rng, rng.choice([1, 2, 2, 3, 3, 4]))
    names = sorted({t for t in REGS if t in e})
    if not names:
        skipped += 1
        continue
    for reg in names:
        t = varies(e, reg, names)
        if t is None:
            skipped += 1
            continue
        s = shipped_classify(e, reg)
        if t and not s:
            unsound.append((e, reg))
        elif (not t) and s:
            over += 1
        else:
            agree += 1

print("=== post-fix adversarial sweep ===")
print("   random expressions (depth<=4, parens, 2 regs, boundary constants): %d" % TRIALS)
print("   (expr,reg) pairs checked : %d" % (agree + over + len(unsound)))
print("   agree with ground truth  : %d" % agree)
print("   sound over-approximations: %d" % over)
print("   *** UNSOUND              : %d ***" % len(unsound))
for e, r in unsound[:20]:
    print("      %-46s wrt %s" % (e, r))
if not unsound:
    print("\n   => no unsound classification found.")
