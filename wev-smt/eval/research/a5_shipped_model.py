#!/usr/bin/env python3
"""Faithful reimplementation of the SHIPPED default local classifier, and a corrected
O17 differential test of the COMPOSITION.

The first differential run (a4) compared the composition using f = exact QF_BV against
the shipped detector, which by default has CONSTANCY_ORACLE = null and therefore uses
the SOLVER-FREE fallback: idiom folding + linear cancellation. Those are different f's,
so the run tested the classifier, not the composition. This file fixes that.

`shipped_classify` mirrors LitmusParser.Job.classify with the oracle absent:
  1. strip whitespace
  2. repeatedly fold, under `bounded(p)` = (?<![A-Za-z0-9_])(?:p)(?![A-Za-z0-9_]) :
        r^r, r-r, r&~r, ~r&r, r*0, 0*r, r&0, 0&r      -> "0"
        r|~r, ~r|r                                     -> "1"
  3. if reg no longer occurs (same negative-lookaround guard) -> FAKE
  4. otherwise linearCoefficient(e, reg); 0 -> FAKE, else REAL
     (null whenever any of * & | ^ ~ ( ) appears, or two adjacent terms)

Run:  python eval/research/a5_shipped_model.py cmp eval/research/a4_shipped.txt
"""
import sys, os, re, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, subst_real

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = os.path.join(HERE, "a4_spec.json")

IDENT = re.compile(r"[A-Za-z_]\w*")


def bounded(p):
    return "(?<![A-Za-z0-9_])(?:" + p + ")(?![A-Za-z0-9_])"


def linear_coeff(e, reg):
    """LitmusParser.linearCoefficient, verbatim in behaviour."""
    coeff, sign, expect, i, n = 0, 1, True, 0, len(e)
    while i < n:
        c = e[i]
        if c in "+-":
            if expect:
                if c == "-":
                    sign = -sign
            else:
                sign = -1 if c == "-" else 1
                expect = True
            i += 1
            continue
        if not expect:
            return None                    # two adjacent terms => non-linear
        if c.isalpha() or c == "_":
            j = i
            while j < n and (e[j].isalnum() or e[j] == "_"):
                j += 1
            if e[i:j] == reg:
                coeff += sign
            i = j
            expect, sign = False, 1
            continue
        if c.isdigit():
            j = i
            while j < n and e[j].isdigit():
                j += 1
            i = j
            expect, sign = False, 1
            continue
        return None                        # * & | ^ ~ ( ) => non-linear
    return None if expect else coeff


PREC_OR, PREC_XOR, PREC_AND, PREC_ADD, PREC_MUL, PREC_UNARY = 1, 2, 3, 4, 5, 6

# (regex, name, precedence of the idiom's TOP-LEVEL operator)
TO_ZERO = [(r"{R}\^{R}", "r^r", PREC_XOR), (r"{R}-{R}", "r-r", PREC_ADD),
           (r"{R}&~{R}", "r&~r", PREC_AND), (r"~{R}&{R}", "~r&r", PREC_AND),
           (r"{R}\*0", "r*0", PREC_MUL), (r"0\*{R}", "0*r", PREC_MUL),
           (r"{R}&0", "r&0", PREC_AND), (r"0&{R}", "0&r", PREC_AND)]
TO_ONES = [(r"{R}\|~{R}", "r|~r", PREC_OR), (r"~{R}\|{R}", "~r|r", PREC_OR)]

_PREC = {"|": PREC_OR, "^": PREC_XOR, "&": PREC_AND, "+": PREC_ADD, "-": PREC_ADD,
         "*": PREC_MUL, "~": PREC_UNARY}


def prec_of(c):
    return _PREC.get(c, -1)


def is_subterm(e, s, t, p):
    """Mirrors LitmusParser.isSubterm: is e[s:t] a genuine sub-term for top precedence p?"""
    if s > 0:
        l = e[s - 1]
        if l != "(":
            lp = prec_of(l)
            if lp < 0 or lp >= p:
                return False
    if t < len(e):
        r = e[t]
        if r != ")":
            rp = prec_of(r)
            if rp < 0 or rp > p:
                return False
    return True


def fold_subterms(e, pattern, repl, p):
    """Mirrors LitmusParser.foldSubterms."""
    out, last, changed = [], 0, False
    for m in re.finditer(pattern, e):
        if not is_subterm(e, m.start(), m.end(), p):
            continue
        out.append(e[last:m.start()])
        out.append(repl)
        last = m.end()
        changed = True
    if not changed:
        return e
    out.append(e[last:])
    return "".join(out)


def shipped_classify(expr, reg):
    """True = REAL, False = FAKE. Mirrors classify() with no oracle installed,
    INCLUDING the precedence-aware fold guard added by the fix."""
    e = re.sub(r"\s+", "", expr)
    R = re.escape(reg)
    changed = True
    while changed:
        changed = False
        for pat, _, p in TO_ZERO:
            new = fold_subterms(e, bounded(pat.replace("{R}", R)), "0", p)
            if new != e:
                e, changed = new, True
        for pat, _, p in TO_ONES:
            new = fold_subterms(e, bounded(pat.replace("{R}", R)), "1", p)
            if new != e:
                e, changed = new, True
    if not re.search(r"(?<![A-Za-z0-9_])" + R + r"(?![A-Za-z0-9_])", e):
        return False
    c = linear_coeff(e, reg)
    if c is not None and c == 0:
        return False
    return True


def identifiers(expr):
    out = []
    for m in IDENT.finditer(expr):
        if m.group() not in out:
            out.append(m.group())
    return out


def shipped_site(blk, x, expr=None, depth=0):
    """The shipped composition, instantiated with the shipped local classifier."""
    expr = blk.store if expr is None else expr
    if depth > 64:
        return True
    acc = False
    for t in identifiers(expr):
        if not shipped_classify(expr, t):
            continue
        if t == x:
            acc = True
        elif t in blk.inputs:
            continue
        else:
            d = blk.def_of(t)
            if d is None:
                continue
            acc = acc or shipped_site(blk, x, d, depth + 1)
        if acc:
            return True
    return acc


def cmp(path):
    specs = json.load(open(SPEC))
    shipped = {}
    for line in open(path, errors="replace"):
        p = line.strip().split()
        if len(p) == 4 and p[0] == "RESULT":
            shipped[int(p[1])] = p[3]

    agree = dis = err = 0
    bad = []
    for i, sp in enumerate(specs):
        if i not in shipped:
            continue
        if shipped[i] == "ERR":
            err += 1
            continue
        blk = Block(["r0"], [(n, e) for n, e in sp["defs"]], sp["store"])
        s = int(shipped[i]) > 0
        m = shipped_site(blk, "r0")
        if s == m:
            agree += 1
        else:
            dis += 1
            if len(bad) < 10:
                bad.append((i, blk.pretty(), s, m))

    print("=== O17 (corrected): composition model with the SHIPPED classifier ===")
    print("   compared : %d    AGREE : %d    DISAGREE : %d    parse-err : %d"
          % (agree + dis, agree, dis, err))
    if bad:
        print("\n   *** composition model still diverges ***")
        for i, b, s, m in bad:
            print("     #%-4d %-56s shipped=%s model=%s"
                  % (i, b[:56], "REAL" if s else "FAKE", "REAL" if m else "FAKE"))
    else:
        print("\n   => EXACT agreement. The reconstructed composition")
        print("        S(x,e) = OR_reg [ f(e,reg) AND F(x,reg) ]")
        print("      is faithful to LitmusParser. O17 is now TESTED, and the earlier")
        print("      divergence is fully explained by the classifier instance, not the")
        print("      composition. T2's premise stands.")

    # How wrong is the shipped detector against ground truth on this corpus?
    wrong = tot = 0
    for i, sp in enumerate(specs):
        if i not in shipped or shipped[i] == "ERR":
            continue
        blk = Block(["r0"], [(n, e) for n, e in sp["defs"]], sp["store"])
        try:
            t = subst_real(blk, "r0")
        except Exception:
            continue
        tot += 1
        if (int(shipped[i]) > 0) != t:
            wrong += 1
    print("\n   shipped detector vs GROUND TRUTH: %d/%d disagreements (%.1f%%)"
          % (wrong, tot, 100.0 * wrong / max(tot, 1)))
    print("   direction check: all should be over-approximation (REAL where truth FAKE)")
    over = under = 0
    for i, sp in enumerate(specs):
        if i not in shipped or shipped[i] == "ERR":
            continue
        blk = Block(["r0"], [(n, e) for n, e in sp["defs"]], sp["store"])
        try:
            t = subst_real(blk, "r0")
        except Exception:
            continue
        s = int(shipped[i]) > 0
        if s and not t:
            over += 1
        elif t and not s:
            under += 1
    print("   over-approximations (sound)   : %d" % over)
    print("   UNDER-approximations (UNSOUND): %d" % under)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "cmp":
        cmp(sys.argv[2])
    else:
        print(__doc__)
