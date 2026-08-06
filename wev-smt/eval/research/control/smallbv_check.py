"""Exhaustive small-bitvector check of the F0 control result (GATE 1, empirical tier).

The previous session reported "8,652 exhaustive small-BV checks, 0 mismatches" but
the script producing it is not in the repository, so this re-derives the check from
scratch rather than citing an unverifiable number.

Two properties are checked exhaustively over a small width W (default 3 bits, i.e.
8 values per register, 3 registers -> 512 valuations per expression triple):

  P1  eval(lift(g, et, ef), s) == effect(g, et, ef, s)          for every valuation
      where lift is the ARITHMETIC lift the oracle's grammar admits,
          (g != 0) * et + (1 - (g != 0)) * ef
      evaluated modulo 2**W, exactly as machine arithmetic does.

  P3  two_copy(r, lift(g, et, ef)) == N4(r, g, et, ef)
      i.e. the self-composition criterion agrees with semantic control dependence.

Expressions are drawn from a generated pool over the oracle's operator set.
"""

import itertools

W = 3
MASK = (1 << W) - 1
VALUES = range(1 << W)
REGS = ("r", "s", "t")


# ── expression pool ─────────────────────────────────────────────────────────

def num(k):
    return ("num", k & MASK)


def reg(name):
    return ("reg", name)


def binop(op, a, b):
    return ("bin", op, a, b)


def relop(op, a, b):
    return ("rel", op, a, b)


def ev(e, env):
    """Evaluate, mirroring QfbvConstancyOracle.evalTerm at width W (signed relops)."""
    k = e[0]
    if k == "num":
        return e[1]
    if k == "reg":
        return env[e[1]]
    if k == "bin":
        _, op, a, b = e
        x, y = ev(a, env), ev(b, env)
        if op == "+":
            return (x + y) & MASK
        if op == "-":
            return (x - y) & MASK
        if op == "*":
            return (x * y) & MASK
        if op == "^":
            return x ^ y
        if op == "&":
            return x & y
        if op == "|":
            return x | y
        raise ValueError(op)
    if k == "rel":
        _, op, a, b = e
        x, y = signed(ev(a, env)), signed(ev(b, env))
        r = {"==": x == y, "!=": x != y, "<": x < y,
             "<=": x <= y, ">": x > y, ">=": x >= y}[op]
        return 1 if r else 0
    raise ValueError(k)


def signed(v):
    return v - (1 << W) if v >= (1 << (W - 1)) else v


# ── the criterion under test ────────────────────────────────────────────────

def lift(g, et, ef):
    c = relop("!=", g, num(0))
    return binop("+", binop("*", c, et),
                 binop("*", binop("-", num(1), c), ef))


def effect(g, et, ef, env):
    return ev(et, env) if ev(g, env) != 0 else ev(ef, env)


def all_envs():
    for vals in itertools.product(VALUES, repeat=len(REGS)):
        yield dict(zip(REGS, vals))


def n4(g, et, ef, r):
    """Exists two valuations agreeing off r with different effect."""
    others = [x for x in REGS if x != r]
    for rest in itertools.product(VALUES, repeat=len(others)):
        base = dict(zip(others, rest))
        seen = set()
        for v in VALUES:
            env = dict(base)
            env[r] = v
            seen.add(effect(g, et, ef, env))
            if len(seen) > 1:
                return True
    return False


def two_copy(e, r):
    """Exists a shared valuation and two words for r making the term differ."""
    others = [x for x in REGS if x != r]
    for rest in itertools.product(VALUES, repeat=len(others)):
        base = dict(zip(others, rest))
        seen = set()
        for v in VALUES:
            env = dict(base)
            env[r] = v
            seen.add(ev(e, env))
            if len(seen) > 1:
                return True
    return False


# ── pool ────────────────────────────────────────────────────────────────────

def pool():
    atoms = [reg("r"), reg("s"), num(0), num(1), num(2)]
    out = list(atoms)
    for op in ("+", "-", "*", "^", "&", "|"):
        for a in atoms:
            for b in atoms:
                out.append(binop(op, a, b))
    return out


def main():
    exprs = pool()
    guards = [reg("r"), reg("s"), relop("==", reg("r"), num(1)),
              relop("!=", reg("r"), num(0)), relop("<", reg("r"), num(2)),
              binop("&", reg("r"), num(1)), num(0), num(1)]

    p1_checks = p1_bad = 0
    p3_checks = p3_bad = 0
    envs = list(all_envs())

    # P1: pointwise, over every guard x arm x arm x valuation.
    arms = exprs[:14]
    for g in guards:
        for et in arms:
            for ef in arms:
                L = lift(g, et, ef)
                for env in envs:
                    p1_checks += 1
                    if ev(L, env) != effect(g, et, ef, env):
                        p1_bad += 1
                        if p1_bad <= 3:
                            print("P1 MISMATCH", g, et, ef, env)

    # P3: the criterion, over every guard x arm x arm, differentiating on r.
    for g in guards:
        for et in arms:
            for ef in arms:
                p3_checks += 1
                if two_copy(lift(g, et, ef), "r") != n4(g, et, ef, "r"):
                    p3_bad += 1
                    if p3_bad <= 3:
                        print("P3 MISMATCH", g, et, ef)

    print(f"width               = {W} bits ({1 << W} values, {len(REGS)} registers)")
    print(f"P1 pointwise checks = {p1_checks}, mismatches = {p1_bad}")
    print(f"P3 criterion checks = {p3_checks}, mismatches = {p3_bad}")
    print(f"TOTAL checks        = {p1_checks + p3_checks}, "
          f"mismatches = {p1_bad + p3_bad}")


if __name__ == "__main__":
    main()
