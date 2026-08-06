#!/usr/bin/env python3
"""
Research probe harness for semantic-dependency criteria (investigation only).

NOT production code. Nothing here is imported by the tool; it exists to decide
mathematical questions about criteria, using the same logic the shipped detector uses.

MODEL
-----
A straight-line block is
    inputs  x1..xm        (load results -- the things dependency edges come FROM)
    defs    t1 := f1(..), ..., tn := fn(..)     (in order; RHS over inputs and earlier ts)
    store   e             (the stored value expression)

Two criteria are compared, both decided exactly by Z3 over 32-bit bitvectors.

SITE  -- the shipped criterion (wev.smt.QfbvConstancyOracle + LitmusParser composition).
      Per static site it asks
            EXISTS other-regs (SHARED across copies), r1, r2 . e[reg:=r1] != e[reg:=r2]
      i.e. the registers appearing in e are modelled as INDEPENDENT free 32-bit
      variables. LitmusParser then CHAINS these local decisions: a load x is a real
      input to e iff some register t in FV(e) is (locally) non-constant in e AND x is
      recursively a real input to t's defining expression. `site_real` below reproduces
      that recursion faithfully, including the OR-merge.

SUBST -- the candidate criterion. Substitute every definition transitively until only
      inputs remain, then ask non-constancy with respect to an INPUT, with the other
      inputs shared across the two copies:
            EXISTS other-inputs (shared), a, b . E[x:=a] != E[x:=b]
      where E = rho*(e). Equivalently (and this is the cheap encoding, see Task 7):
      two copies of the whole block agreeing on every input except x, asking whether
      the stored values can differ. No substitution is actually required.

Both are exact decision procedures for what they ask; they differ only in WHAT they ask.

Grammar mirrors QfbvConstancyOracle: | ^ & + - * ~ unary-, and relational ops
(== != < <= > >=) yielding 0/1. Shifts and division are OUTSIDE the shipped grammar
(the oracle returns null and the detector falls back to conservative REAL); they are
supported here only so their effect can be measured, and are flagged when used.
"""

import itertools
import re

from z3 import (And, BitVec, BitVecVal, Distinct, Extract, If, Not, Or, Solver, sat,
                unsat, LShR, ULT, ULE, UGT, UGE)

W = 32

# ── expression language ─────────────────────────────────────────────────────────

TOKEN = re.compile(r"\s*(<<|>>|==|!=|<=|>=|[-+*&|^~()<>]|0x[0-9a-fA-F]+|\d+|[A-Za-z_]\w*)")


def tokenize(s):
    out, i = [], 0
    while i < len(s):
        m = TOKEN.match(s, i)
        if not m:
            if s[i].isspace():
                i += 1
                continue
            raise SyntaxError("bad token at %r" % s[i:])
        out.append(m.group(1))
        i = m.end()
    return out


class P:
    """Recursive-descent parser; precedence low->high: | ^ & +- * shift unary primary."""

    def __init__(self, toks, env):
        self.t, self.i, self.env = toks, 0, env
        self.used_outside_grammar = set()

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else None

    def eat(self, x=None):
        v = self.t[self.i]
        if x and v != x:
            raise SyntaxError("expected %s got %s" % (x, v))
        self.i += 1
        return v

    def parse(self):
        v = self.rel()
        if self.i != len(self.t):
            raise SyntaxError("trailing %s" % self.t[self.i:])
        return v

    def rel(self):
        a = self.bor()
        while self.peek() in ("==", "!=", "<", "<=", ">", ">="):
            op = self.eat()
            b = self.bor()
            c = {"==": lambda: a == b, "!=": lambda: a != b,
                 "<": lambda: a < b, "<=": lambda: a <= b,
                 ">": lambda: a > b, ">=": lambda: a >= b}[op]()
            a = If(c, BitVecVal(1, W), BitVecVal(0, W))
        return a

    def bor(self):
        a = self.bxor()
        while self.peek() == "|":
            self.eat()
            a = a | self.bxor()
        return a

    def bxor(self):
        a = self.band()
        while self.peek() == "^":
            self.eat()
            a = a ^ self.band()
        return a

    def band(self):
        a = self.add()
        while self.peek() == "&":
            self.eat()
            a = a & self.add()
        return a

    def add(self):
        a = self.mul()
        while self.peek() in ("+", "-"):
            op = self.eat()
            b = self.mul()
            a = a + b if op == "+" else a - b
        return a

    def mul(self):
        a = self.shift()
        while self.peek() == "*":
            self.eat()
            a = a * self.shift()
        return a

    def shift(self):
        a = self.unary()
        while self.peek() in ("<<", ">>"):
            op = self.eat()
            self.used_outside_grammar.add(op)
            b = self.unary()
            a = (a << b) if op == "<<" else LShR(a, b)
        return a

    def unary(self):
        p = self.peek()
        if p == "~":
            self.eat()
            return ~self.unary()
        if p == "-":
            self.eat()
            return -self.unary()
        return self.primary()

    def primary(self):
        p = self.eat()
        if p == "(":
            v = self.rel()
            self.eat(")")
            return v
        if re.fullmatch(r"0x[0-9a-fA-F]+", p):
            return BitVecVal(int(p, 16), W)
        if p.isdigit():
            return BitVecVal(int(p), W)
        if p in self.env:
            return self.env[p]
        raise KeyError("unbound identifier %r" % p)


def build(expr, env):
    p = P(tokenize(expr), env)
    v = p.parse()
    return v, p.used_outside_grammar


def idents(expr):
    """Free identifiers of an expression string (excludes numeric literals)."""
    return [t for t in tokenize(expr)
            if re.fullmatch(r"[A-Za-z_]\w*", t)]


# ── blocks ──────────────────────────────────────────────────────────────────────

class Block:
    """inputs: list[str]; defs: list[(name, expr)] in order; store: expr string."""

    def __init__(self, inputs, defs, store, name=""):
        self.inputs = list(inputs)
        self.defs = list(defs)
        self.store = store
        self.name = name

    def def_of(self, t):
        for n, e in self.defs:
            if n == t:
                return e
        return None

    def symbolic(self, env):
        """Value of `store` with every def unfolded, over the given input environment."""
        cur = dict(env)
        outside = set()
        for n, e in self.defs:
            v, o = build(e, cur)
            outside |= o
            cur[n] = v
        v, o = build(self.store, cur)
        return v, outside | o

    def pretty(self):
        body = "; ".join("%s := %s" % (n, e) for n, e in self.defs)
        return ("%s%sstore %s" % (body, "; " if body else "", self.store))


# ── the two criteria ────────────────────────────────────────────────────────────

def _fresh(names, tag):
    return {n: BitVec("%s_%s" % (n, tag), W) for n in names}


def nonconst_site(expr, reg, extra_env=None):
    """The shipped per-site query, verbatim.

    EXISTS others (shared), r1, r2 . expr[reg:=r1] != expr[reg:=r2]
    Returns True (REAL / non-constant) or False (FAKE / constant).
    """
    names = sorted(set(idents(expr)))
    if reg not in names:
        return False
    shared = {n: BitVec("s_" + n, W) for n in names if n != reg}
    e1 = dict(shared)
    e1[reg] = BitVec("r1", W)
    e2 = dict(shared)
    e2[reg] = BitVec("r2", W)
    v1, _ = build(expr, e1)
    v2, _ = build(expr, e2)
    s = Solver()
    s.add(v1 != v2)
    return s.check() == sat


def site_real(blk, x, expr=None, _depth=0):
    """LitmusParser's CHAINED site criterion: is input x a real input to `expr`?

    Faithful to referencedReads(): for each identifier t of expr, the local verdict
    classify(expr, t) is ANDed with whether x is (recursively) a real input to t, and
    the results are OR-merged over t.
    """
    expr = blk.store if expr is None else expr
    if _depth > 64:
        return True
    acc = False
    for t in sorted(set(idents(expr))):
        local = nonconst_site(expr, t)
        if not local:
            continue
        if t == x:
            acc = True
        elif t in blk.inputs:
            continue                       # a different input: carries nothing about x
        else:
            d = blk.def_of(t)
            if d is None:
                continue
            acc = acc or site_real(blk, x, d, _depth + 1)
        if acc:
            return True
    return acc


def subst_real(blk, x):
    """The candidate criterion: non-constancy of the block's transfer function in x.

    Two copies of the block agreeing on every input except x; can the stored values
    differ? Exact, and equivalent to substituting all definitions.
    """
    if x not in blk.inputs:
        return False
    shared = {n: BitVec("i_" + n, W) for n in blk.inputs if n != x}
    e1 = dict(shared)
    e1[x] = BitVec("xa", W)
    e2 = dict(shared)
    e2[x] = BitVec("xb", W)
    v1, o1 = blk.symbolic(e1)
    v2, o2 = blk.symbolic(e2)
    s = Solver()
    s.add(v1 != v2)
    return s.check() == sat


def outside_grammar(blk):
    env = {n: BitVec(n, W) for n in blk.inputs}
    try:
        _, o = blk.symbolic(env)
        return o
    except Exception:
        return {"<parse-error>"}


# ── reporting ───────────────────────────────────────────────────────────────────

def compare(blk, note="", inputs=None):
    """Compare SITE vs SUBST for every input. Returns list of separator rows."""
    rows = []
    for x in (inputs or blk.inputs):
        s_site = site_real(blk, x)
        s_sub = subst_real(blk, x)
        rows.append((blk.name or blk.pretty(), x, s_site, s_sub, s_site != s_sub, note))
    return rows


def show(rows, title):
    print("\n=== %s ===" % title)
    print("%-52s %-4s %-6s %-6s %s" % ("block", "in", "SITE", "SUBST", "separator?"))
    print("-" * 92)
    for name, x, a, b, sep, note in rows:
        print("%-52s %-4s %-6s %-6s %s%s"
              % (name[:52], x, "REAL" if a else "FAKE", "REAL" if b else "FAKE",
                 "*** YES ***" if sep else "no", ("   " + note) if note else ""))
    n = sum(1 for r in rows if r[4])
    print("-> %d separator(s) of %d" % (n, len(rows)))
    return n
