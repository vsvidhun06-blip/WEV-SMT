#!/usr/bin/env python3
"""IMPACT ASSESSMENT for the unsound idiom fold in LitmusParser.Job.classify.

Root cause: the fold rewrites the expression STRING with
    e = e.replaceAll(bounded(pattern), "0")
where bounded(p) = (?<![A-Za-z0-9_])(?:p)(?![A-Za-z0-9_]).
The guard only checks that the match is not adjacent to an identifier character. It does
NOT check that the matched `r OP r` is actually a SUB-TERM of the expression. So a match
can span an operand boundary, e.g.

    ~r0 - r0     parses as (~r0) - r0 = -2*r0 - 1        (varies)
                 but "r0-r0" occurs as a substring, is folded to 0,
                 leaving "~0", which no longer mentions r0  =>  reported FAKE.

Because `classify` returns FAKE on `!mentionsReg` BEFORE consulting CONSTANCY_ORACLE,
installing the exact QF_BV oracle does NOT mask the bug (verified: scratchpad/audit4.jsh).

Direction of the error: a genuinely value-carrying dependency is classified fake and
dropped from sdep. That is UNSOUND -- it removes edges, which can break a jf u sdep cycle
that ought to exist and turn a FORBIDDEN outcome into ALLOWED.

This script enumerates small expressions exhaustively and measures the rate.

Run:  python eval/research/a7_soundness.py
"""
import sys, os, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from a5_shipped_model import shipped_classify
from depprobe import build
from z3 import BitVec, Solver, sat

REG = "r0"
ATOMS = [REG, "1", "2", "7"]
BIN = ["+", "-", "&", "^"]


def varies(expr):
    """Ground truth: does expr vary with r0? (other identifiers: none here)"""
    a, b = BitVec("a", 32), BitVec("b", 32)
    try:
        va, _ = build(expr, {REG: a})
        vb, _ = build(expr, {REG: b})
    except Exception:
        return None
    s = Solver()
    s.add(va != vb)
    return s.check() == sat


def gen(depth):
    """All flat expressions of `depth` binary operators, with optional unary ~ on atoms."""
    atoms = ATOMS + ["~" + a for a in ATOMS]
    if depth == 0:
        for a in atoms:
            yield a
        return
    for prev in gen(depth - 1):
        for op in BIN:
            for a in atoms:
                yield "%s %s %s" % (prev, op, a)


print(__doc__)
total = unsound = overapprox = agree = skipped = 0
examples = []
for d in (1, 2):
    for e in gen(d):
        t = varies(e)
        if t is None:
            skipped += 1
            continue
        shipped = shipped_classify(e, REG)      # True = REAL
        total += 1
        if t and not shipped:
            unsound += 1
            if len(examples) < 25:
                examples.append(e)
        elif (not t) and shipped:
            overapprox += 1
        else:
            agree += 1

print("=== exhaustive enumeration, 1-2 binary operators over {r0,1,2,7} and ~ ===")
print("   expressions tested        : %d" % total)
print("   agree with ground truth   : %d" % agree)
print("   sound over-approximations : %d   (fake reported real -- harmless)" % overapprox)
print("   *** UNSOUND               : %d   (REAL dependency reported FAKE) ***" % unsound)
print("   rate                      : %.2f%%" % (100.0 * unsound / max(total, 1)))
print("\n   witnesses (all verified VARIES by Z3, reported FAKE by the shipped fold):")
for e in examples:
    print("      %s" % e)

print("""
=== which pattern is responsible? ===""")
from a5_shipped_model import TO_ZERO, TO_ONES, bounded
import re
blame = {}
for e in examples:
    s = re.sub(r"\s+", "", e)
    R = re.escape(REG)
    for pat, name in TO_ZERO + TO_ONES:
        if re.search(bounded(pat.replace("{R}", R)), s):
            blame[name] = blame.get(name, 0) + 1
for k, v in sorted(blame.items(), key=lambda kv: -kv[1]):
    print("   %-8s implicated in %d witnesses" % (k, v))

print("""
=== minimal reproducer ===
   C T
   { }
    P0 ;
    r0 = READ_ONCE(*x) ;
    WRITE_ONCE(*y, ~r0 - r0) ;
   exists (0:r0=1)

   ground truth        : (~r0) - r0 = -2*r0 - 1, varies with r0  => REAL
   shipped (no oracle) : FAKE
   shipped (QF_BV on)  : FAKE          <- oracle bypassed by the short-circuit
   direct oracle query : isConstant = false (i.e. VARIES)
""")
