#!/usr/bin/env python3
"""TASK 7 -- SMT boundary: is the candidate criterion still QF_BV, and at what cost?

Compares two encodings of the SAME query:

  (T) TEXTUAL SUBSTITUTION  -- unfold every definition into the store expression, then
      pose the one-expression non-constancy query. This is what "apply a normalization
      pass, then re-run the existing oracle" would do.

  (S) SELF-COMPOSITION      -- do NOT substitute. Emit two copies of the block's
      definitions as equality constraints, force the copies to agree on every input
      except x, and ask whether the two stored values can differ.

Both decide exactly the same predicate. They differ enormously in size on DAG-shaped
blocks, which is the practical point.

Run:  python eval/research/p6_smt_cost.py
"""
import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, tokenize, build
from z3 import BitVec, Solver, sat, simplify

print(__doc__)


def dag_block(n):
    """t1 := x+x ; t2 := t1+t1 ; ... ; store tn - tn.  Each temp used TWICE."""
    defs = [("t1", "x + x")]
    for i in range(2, n + 1):
        defs.append(("t%d" % i, "t%d + t%d" % (i - 1, i - 1)))
    return Block(["x"], defs, "t%d - t%d" % (n, n), "dag-%d" % n)


def textual_size(blk):
    """Size of the store expression after full textual unfolding (token count)."""
    rhs = {n: e for n, e in blk.defs}
    store = blk.store
    for _ in range(len(blk.defs) + 2):
        toks = tokenize(store)
        if not any(t in rhs for t in toks):
            break
        store = " ".join(("(" + rhs[t] + ")") if t in rhs else t for t in toks)
    return len(tokenize(store)), store


def z3_size(t):
    seen, stack, n = set(), [t], 0
    while stack:
        e = stack.pop()
        k = e.get_id()
        if k in seen:
            continue
        seen.add(k)
        n += 1
        stack.extend(e.children())
    return n            # DAG nodes (Z3 hash-conses, so this is the shared size


def selfcomp(blk, x):
    """Two copies sharing all inputs but x; returns (solver, #assertions, result)."""
    s = Solver()
    envs = []
    for tag in ("a", "b"):
        env = {}
        for n in blk.inputs:
            env[n] = BitVec("i_%s" % n, 32) if n != x else BitVec("x_%s" % tag, 32)
        for n, e in blk.defs:
            v, _ = build(e, env)
            fresh = BitVec("%s_%s" % (n, tag), 32)
            s.add(fresh == v)                 # definitional equality, no unfolding
            env[n] = fresh
        v, _ = build(blk.store, env)
        out = BitVec("out_%s" % tag, 32)
        s.add(out == v)
        envs.append(out)
    s.add(envs[0] != envs[1])
    return s, len(s.assertions())


print("%-8s %-16s %-16s %-14s %-10s" %
      ("block", "textual tokens", "selfcomp asserts", "selfcomp time", "verdict"))
print("-" * 74)
for n in range(1, 17):
    blk = dag_block(n)
    tsz, _ = textual_size(blk)
    s, na = selfcomp(blk, "x")
    t0 = time.time()
    r = s.check()
    dt = time.time() - t0
    print("%-8s %-16d %-16d %-14.3f %-10s"
          % (blk.name, tsz, na, dt, "REAL" if r == sat else "FAKE"))

print("""
Reading:
  textual tokens  grows as 2^n  -- each temporary is used twice, so unfolding doubles.
  selfcomp asserts grows as 2n+3 -- LINEAR. The blow-up is an artefact of SUBSTITUTING,
  not of the criterion.

=> The candidate criterion is QF_BV and needs NO normalization pass. Encoding it as a
   two-copy product query over the block's existing definitional equalities is linear in
   the block size and stays inside the existing solver architecture. A literal
   "normalize then re-run the oracle" implementation would be exponential in the worst
   case for exactly the shape (repeated temporaries) that register allocation produces.""")

# ── does the criterion stay decidable / same complexity class? ────────────────
print("""
=== complexity ===
  current  : 1 copy  of one site expression, QF_BV, NP-complete
  candidate: 2 copies of one basic block,    QF_BV, NP-complete  (same class)
  preprocessing: none required (definitions already exist); if one insists on an
  explicit substituted term, emit it as a DAG / let-bound term, which is O(block size).""")

# ── sanity: the two encodings agree ───────────────────────────────────────────
print("=== agreement check: textual vs self-composition, 60 random blocks ===")
import random
rng = random.Random(7)
OPS = ["+", "-", "*", "&", "|", "^"]
bad = 0
for trial in range(60):
    k = rng.choice([2, 3, 4])
    defs, avail = [], ["x"]
    for i in range(k):
        a, b = rng.choice(avail), rng.choice(avail)
        defs.append(("t%d" % i, "%s %s %s" % (a, rng.choice(OPS), b)))
        avail.append("t%d" % i)
    store = "%s %s %s" % (rng.choice(avail), rng.choice(OPS), rng.choice(avail))
    blk = Block(["x"], defs, store, "r%d" % trial)
    s, _ = selfcomp(blk, "x")
    sc = (s.check() == sat)
    _, unf = textual_size(blk)
    s2 = Solver()
    v1, _ = build(unf, {"x": BitVec("xa", 32)})
    v2, _ = build(unf, {"x": BitVec("xb", 32)})
    s2.add(v1 != v2)
    tx = (s2.check() == sat)
    if sc != tx:
        bad += 1
        print("   MISMATCH on", blk.pretty())
print("   mismatches: %d / 60  %s" % (bad, "(encodings agree)" if bad == 0 else ""))
