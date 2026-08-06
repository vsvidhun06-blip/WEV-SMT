#!/usr/bin/env python3
"""TASK 2 -- is full substitution minimal? Test every weaker transformation class.

Each class is modelled as "apply the transformation, THEN run the shipped detector
(chained site criterion) on the result". A class is REFUTED as sufficient the moment
it disagrees with SUBST on one block.

Run:  python eval/research/p2_minimal.py
"""
import sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real, idents, tokenize

print(__doc__)

# ── restricted substitution: inline only the defs a predicate accepts ───────────

def inline(blk, pred, max_depth=None):
    """Textually inline every def accepted by `pred`, up to max_depth rounds."""
    defs = [(n, e) for n, e in blk.defs]
    rounds = max_depth if max_depth is not None else len(defs) + 1
    for _ in range(rounds):
        changed = False
        newdefs, subst = [], {}
        for n, e in defs:
            e2 = _apply(e, subst)
            if pred(n, e2, blk):
                subst[n] = "(" + e2 + ")"
                changed = True
            else:
                newdefs.append((n, e2))
        store = _apply(blk.store, subst)
        if not changed and store == blk.store and newdefs == defs:
            break
        defs = newdefs
        blk = Block(blk.inputs, defs, store, blk.name)
    return blk


def _apply(expr, subst):
    if not subst:
        return expr
    out = []
    for t in tokenize(expr):
        out.append(subst.get(t, t))
    return " ".join(out)


IS_REG = lambda s: re.fullmatch(r"\s*[A-Za-z_]\w*\s*", s) is not None
IS_CONST = lambda s: re.fullmatch(r"\s*\d+\s*", s) is not None


def is_affine(expr, blk):
    """RHS built only from + - and identifiers/constants (no * & | ^ ~)."""
    return all(t not in ("*", "&", "|", "^", "~", "<<", ">>") for t in tokenize(expr))


CLASSES = [
    ("SITE (no transformation)", lambda b: b),
    ("copy propagation only", lambda b: inline(b, lambda n, e, blk: IS_REG(e))),
    ("constant propagation only", lambda b: inline(b, lambda n, e, blk: IS_CONST(e))),
    ("copy + constant propagation", lambda b: inline(b, lambda n, e, blk: IS_REG(e) or IS_CONST(e))),
    ("affine substitution (+,- only)", lambda b: inline(b, lambda n, e, blk: is_affine(e, blk))),
    ("substitution, depth 1", lambda b: inline(b, lambda n, e, blk: True, max_depth=1)),
    ("substitution, depth 2", lambda b: inline(b, lambda n, e, blk: True, max_depth=2)),
    ("FULL substitution", lambda b: inline(b, lambda n, e, blk: True)),
]


def eval_class(blk, xform):
    b2 = xform(blk)
    return {x: site_real(b2, x) for x in blk.inputs}


# ── the test blocks: both causes, plus chains ──────────────────────────────────

CHAIN3 = Block(["x"],
               [("a", "x + 1"), ("b", "a + 1"), ("c", "b + 1")],
               "c - x - 3", "chain-3: c=x+3; store c-x-3")
CHAIN5 = Block(["x"],
               [("a", "x + 1"), ("b", "a + 1"), ("c", "b + 1"),
                ("d", "c + 1"), ("e", "d + 1")],
               "e - x - 5", "chain-5: e=x+5; store e-x-5")

BLOCKS = [
    Block(["r"], [("t", "r")], "r - t", "W1 copy       t:=r;   store r-t"),
    Block(["x"], [("t", "x + 1")], "t - x", "W2 affine     t:=x+1; store t-x"),
    Block(["x"], [("t", "x & 1")], "t & 2", "W3 mask       t:=x&1; store t&2"),
    Block(["x"], [("t", "x * 4")], "t & 3", "W4 mul-range  t:=4x;  store t&3"),
    Block(["x"], [("t", "x ^ 5")], "t ^ x ^ 5", "W5 xor-share  t:=x^5; store t^x^5"),
    Block(["x"], [("t", "x * x")], "t & 2", "W6 square     t:=x*x; store t&2"),
    CHAIN3,
    CHAIN5,
]

names = [n for n, _ in CLASSES]
print("\n%-30s %s" % ("block", "  ".join("%-9s" % n[:9] for n in names) + "   SUBST"))
print("-" * (30 + 11 * len(names) + 9))

miss = {n: [] for n in names}
for blk in BLOCKS:
    truth = {x: subst_real(blk, x) for x in blk.inputs}
    cells = []
    for cname, xform in CLASSES:
        try:
            got = eval_class(blk, xform)
        except Exception as ex:
            cells.append("ERR")
            continue
        ok = got == truth
        cells.append(("REAL" if list(got.values())[0] else "FAKE") + ("" if ok else "!"))
        if not ok:
            miss[cname].append(blk.name)
    print("%-30s %s   %s"
          % (blk.name[:30], "  ".join("%-9s" % c for c in cells),
             "FAKE" if not list(truth.values())[0] else "REAL"))

print("""
Legend: `!` marks disagreement with SUBST (the class MISSED a fake dependency).
""")
print("=== separators per transformation class ===")
for cname, _ in CLASSES:
    m = miss[cname]
    if m:
        print("  %-32s MISSES %d: %s" % (cname, len(m), "; ".join(s.split()[0] for s in m)))
    else:
        print("  %-32s agrees with SUBST on all %d blocks" % (cname, len(BLOCKS)))

print("""
=== unbounded-depth argument ===
For every fixed depth d, the chain block of length d+1 is a separator: substituting
only d levels leaves the head temporary un-unfolded, so the cancellation against x is
invisible. Verified above for d = 1, 2 against chain-3 / chain-5.""")
for d, blk in [(1, CHAIN3), (2, CHAIN3), (3, CHAIN5), (4, CHAIN5)]:
    b2 = inline(blk, lambda n, e, b: True, max_depth=d)
    got = site_real(b2, "x")
    print("   depth=%d on %-28s -> %s   (SUBST says %s)"
          % (d, blk.name.split(":")[0], "REAL" if got else "FAKE",
             "REAL" if subst_real(blk, "x") else "FAKE"))
