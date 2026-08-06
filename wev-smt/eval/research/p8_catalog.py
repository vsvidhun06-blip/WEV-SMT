#!/usr/bin/env python3
"""TASK 8 -- consolidated separator catalog.

Re-derives every separator found across p1..p7 and writes eval/research/separators.csv.
A "separator" is a block on which SITE (shipped criterion) and SUBST (candidate) differ.

Run:  python eval/research/p8_catalog.py
"""
import sys, os, csv
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real

OUT = os.path.join("eval", "research", "separators.csv")

# (category, cause, block, input-of-interest)
CASES = [
    ("established-witness", "sharing", Block(["r"], [("t", "r")], "r - t"), "r"),
    ("established-witness", "sharing", Block(["x"], [("t", "x + 1")], "t - x"), "x"),
    ("established-witness", "range", Block(["x"], [("t", "x & 1")], "t & 2"), "x"),

    ("sharing-bijective", "sharing", Block(["x"], [("t", "x")], "x - t"), "x"),
    ("sharing-bijective", "sharing", Block(["x"], [("t", "~x")], "t + x"), "x"),
    ("sharing-bijective", "sharing", Block(["x"], [("t", "0 - x")], "t + x"), "x"),
    ("sharing-bijective", "sharing", Block(["x"], [("t", "3 * x")], "t - 3 * x"), "x"),
    ("sharing-bijective", "sharing", Block(["x"], [("t", "x ^ 5")], "t ^ x ^ 5"), "x"),
    ("sharing-bijective", "sharing", Block(["x"], [("t1", "x ^ 3"), ("t2", "x ^ 5")], "t1 ^ t2 ^ 6"), "x"),

    ("range-restriction", "range", Block(["x"], [("t", "x * 2")], "t & 1"), "x"),
    ("range-restriction", "range", Block(["x"], [("t", "x * 4")], "t & 3"), "x"),
    ("range-restriction", "range", Block(["x"], [("t", "x * x")], "t & 2"), "x"),
    ("range-restriction", "range", Block(["x"], [("t", "x * 0x80000000")], "t & 0x7fffffff"), "x"),
    ("range-restriction", "range", Block(["x"], [("t", "x + x + x + x")], "t & 3"), "x"),
    ("range-restriction", "range", Block(["x"], [("t", "x & 0xf0")], "t == 1"), "x"),

    ("correlated-surjective", "sharing", Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 + x2")], "t2 - t1"), "x1"),
    ("correlated-surjective", "sharing", Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 ^ x2")], "t2 ^ t1"), "x1"),

    ("multi-temp-mixed", "both", Block(["x"], [("a", "x & 0xff"), ("b", "x & 0xff00")], "a & b"), "x"),
    ("multi-temp-mixed", "range", Block(["x"], [("a", "x * 2"), ("b", "a + 1")], "b & 1"), "x"),
    ("multi-temp-mixed", "sharing", Block(["x"], [("a", "x + x"), ("b", "x * 2")], "a - b"), "x"),
    ("multi-temp-mixed", "sharing", Block(["x"], [("a", "x ^ 0xff")], "(a ^ x) - 0xff"), "x"),
    ("multi-temp-mixed", "range", Block(["x"], [("a", "x & 1"), ("b", "a * a")], "b - a"), "x"),
    ("multi-temp-mixed", "sharing", Block(["x", "y"], [("a", "x & y"), ("b", "x | y")], "a + b - x - y"), "x"),
    ("multi-temp-mixed", "sharing", Block(["x", "y"], [("a", "x ^ y"), ("b", "x & y")], "a + b + b - x - y"), "x"),

    ("chain-depth", "sharing", Block(["x"], [("a", "x + 1"), ("b", "a + 1"), ("c", "b + 1")], "c - x - 3"), "x"),
    ("chain-depth", "sharing", Block(["x"], [("a1", "x + 1"), ("a2", "a1 + 1"), ("a3", "a2 + 1"),
                                             ("a4", "a3 + 1"), ("a5", "a4 + 1")], "a5 - x - 5"), "x"),

    ("shift-outside-grammar", "range", Block(["x"], [("t", "x << 1")], "t & 1"), "x"),
    ("shift-outside-grammar", "range", Block(["x"], [("t", "x >> 31")], "t & 2"), "x"),
    ("shift-outside-grammar", "range", Block(["x"], [("t", "x << 16")], "t & 0xffff"), "x"),

    ("functional-dependence", "sharing", Block(["x"], [("t1", "x + 1"), ("t2", "x + 4")], "t2 == (t1 + 3)"), "x"),
    ("functional-dependence", "sharing", Block(["x"], [("t1", "3 * x"), ("t2", "x")], "t2 == (2863311531 * t1)"), "x"),
    ("functional-dependence", "sharing", Block(["x"], [("t1", "x"), ("t2", "0 - x")], "t2 == (0 - t1)"), "x"),

    ("memory-must-alias", "sharing", Block(["x"], [("m", "x"), ("t", "m")], "t - x"), "x"),

    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "x")], "t1 - t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "x")], "t1 ^ t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "~x")], "t1 & t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "~x")], "t1 | t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "0 - x")], "t1 + t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "x & 0")], "t1 * t2"), "x"),
    ("operator-coverage", "sharing", Block(["x"], [("t1", "x"), ("t2", "x")], "t1 == t2"), "x"),

    # p10: the uniform point-indicator separator -- only `==` and `&`, no inverses.
    # Works for EVERY environment whose joint image G is a proper subset of BV^k.
    ("point-indicator", "sharing", Block(["x"], [("t1", "x"), ("t2", "x + 1")], "(t1 == 5) & (t2 == 9)"), "x"),
    ("point-indicator", "sharing", Block(["x"], [("t1", "x * x"), ("t2", "x ^ 0x5a5a")], "(t1 == 3) & (t2 == 7)"), "x"),
    ("point-indicator", "both", Block(["x"], [("t1", "x & 0xff"), ("t2", "x | 0xff00")], "(t1 == 1) & (t2 == 2)"), "x"),
    ("point-indicator", "range", Block(["x"], [("t1", "x & 1")], "t1 == 2"), "x"),
    ("point-indicator", "range", Block(["x"], [("t1", "x * 6")], "t1 == 1"), "x"),
    ("point-indicator", "sharing", Block(["x"], [("t1", "x"), ("t2", "x + 1"), ("t3", "x + 2")],
                                         "(t1 == 0) & (t2 == 0) & (t3 == 0)"), "x"),

    # p10: correlated but SURJECTIVE -- no point-indicator exists; the separator is the
    # complementary coordinate of an invertible change of variables.
    ("fibre-constant", "sharing", Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 + x2")], "t2 - t1"), "x1"),
    ("fibre-constant", "sharing", Block(["x1", "x2"], [("t1", "x1"), ("t2", "x1 ^ x2")], "t2 ^ t1"), "x1"),
    ("fibre-constant", "sharing", Block(["x1", "x2"], [("t1", "x1"), ("t2", "3 * x1 + x2")], "t2 - 3 * t1"), "x1"),
]

rows, nsep = [], 0
for cat, cause, blk, x in CASES:
    a, b = site_real(blk, x), subst_real(blk, x)
    sep = a != b
    nsep += sep
    rows.append({
        "category": cat, "cause": cause, "block": blk.pretty(), "input": x,
        "SITE": "REAL" if a else "FAKE", "SUBST": "REAL" if b else "FAKE",
        "separator": "yes" if sep else "no",
    })

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", newline="", encoding="utf-8") as fh:
    w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

print("catalog: %d cases, %d separators -> %s" % (len(rows), nsep, OUT))
by = {}
for r in rows:
    if r["separator"] == "yes":
        by.setdefault(r["category"], 0)
        by[r["category"]] += 1
for k in sorted(by):
    print("   %-24s %d" % (k, by[k]))
bad = [r for r in rows if r["separator"] == "no"]
if bad:
    print("   non-separators (recorded for completeness):")
    for r in bad:
        print("      %-50s SITE=%s SUBST=%s" % (r["block"][:50], r["SITE"], r["SUBST"]))
print("""
cause legend:
   sharing = two site registers functionally correlated through a common input
   range   = a site register cannot take all 2^32 values
   both    = both mechanisms present""")
