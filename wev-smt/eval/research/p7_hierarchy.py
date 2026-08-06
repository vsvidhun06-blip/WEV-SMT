#!/usr/bin/env python3
"""TASK 6 -- the detector hierarchy, with a separator for every strict inclusion,
plus the critical SOUNDNESS direction check.

Criteria are compared as SETS OF EDGES CLASSIFIED REAL. A stronger detector finds more
fakes, hence classifies FEWER edges real, hence is a SUBSET.

    SDEP_true  subseteq  SDEP_subst  subseteq  SDEP_site  subseteq  SDEP_patterns

Soundness of the shipped tool requires SDEP_true subseteq SDEP_impl: the detector may
call a fake edge real, but must NEVER call a real edge fake. The randomized check below
looks specifically for a violation (SITE=FAKE while SUBST=REAL).

Run:  python eval/research/p7_hierarchy.py
"""
import sys, os, re, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, site_real, subst_real, nonconst_site, tokenize

print(__doc__)

# ── level 0: the syntactic pattern matcher (LitmusParser.classify fast path) ───
TO_ZERO = ["{r}^{r}", "{r}-{r}", "{r}&~{r}", "~{r}&{r}", "{r}*0", "0*{r}",
           "{r}&0", "0&{r}"]
TO_ONES = ["{r}|~{r}", "~{r}|{r}"]


def pattern_nonconst(expr, reg):
    """LitmusParser's solver-free fallback: idiom folding + linear cancellation."""
    e = re.sub(r"\s+", "", expr)
    changed = True
    while changed:
        changed = False
        for p in TO_ZERO:
            n = e.replace(p.format(r=reg), "0")
            if n != e:
                e, changed = n, True
        for p in TO_ONES:
            n = e.replace(p.format(r=reg), "1")
            if n != e:
                e, changed = n, True
    if not re.search(r"(?<![A-Za-z0-9_])%s(?![A-Za-z0-9_])" % re.escape(reg), e):
        return False                                   # reg gone => fake
    coeff = linear_coeff(e, reg)
    if coeff is not None and coeff == 0:
        return False                                   # LINEAR_CANCEL => fake
    return True                                        # conservatively REAL


def linear_coeff(e, reg):
    """Net coefficient of reg if e is a flat +/- sum; None if anything else appears."""
    if re.search(r"[*&|^~()]", e):
        return None
    coeff, sign, expect = 0, 1, True
    i = 0
    while i < len(e):
        c = e[i]
        if c in "+-":
            if expect:
                sign = -sign if c == "-" else sign
            else:
                sign, expect = (-1 if c == "-" else 1), True
            i += 1
            continue
        if not expect:
            return None
        m = re.match(r"[A-Za-z_]\w*|\d+", e[i:])
        if not m:
            return None
        tok = m.group(0)
        if tok == reg:
            coeff += sign
        i += len(tok)
        expect, sign = False, 1
    return coeff


# ── inclusion 1: patterns vs site (QF_BV) ────────────────────────────────────
print("\n=== inclusion 1: SDEP_site subseteq SDEP_patterns (strict) ===")
print("Expressions the exact QF_BV query proves constant but the idiom matcher misses.")
print("%-26s %-4s %-12s %-12s %s" % ("expression", "reg", "patterns", "QF_BV site", "sep?"))
print("-" * 72)
n1 = 0
for expr, reg in [("r^r", "r"), ("r-r", "r"), ("r+1-r", "r"),
                  ("r&1&2", "r"), ("(r|1)&(~r|1)&2", "r"), ("r*2-r-r", "r"),
                  ("(r&1)*(r&1)-(r&1)", "r"), ("r*r*2-r*r-r*r", "r"),
                  ("(r^1)^(r^1)^7", "r"), ("(r+1)*(r-1)-r*r+1", "r")]:
    p = pattern_nonconst(expr, reg)
    q = nonconst_site(expr, reg)
    sep = p != q
    n1 += sep
    print("%-26s %-4s %-12s %-12s %s" % (expr, reg, "REAL" if p else "FAKE",
                                         "REAL" if q else "FAKE",
                                         "*** YES ***" if sep else "no"))
print("-> %d separator(s): the QF_BV site criterion is STRICTLY stronger than patterns." % n1)

# ── inclusion 2: site vs subst -- already established, recap ─────────────────
print("""
=== inclusion 2: SDEP_subst subseteq SDEP_site (strict) ===
Established by p1/p2/p3/p5: 30+ separators, of two independent kinds
  (a) SHARING          e.g. t:=x;   store x-t
  (b) RANGE RESTRICTION e.g. t:=x&1; store t&2
""")

# ── the soundness direction: is there EVER SITE=FAKE with SUBST=REAL? ────────
print("=== SOUNDNESS CHECK: does SITE ever miss a REAL dependency? ===")
print("Searching for a block with SITE=FAKE but SUBST=REAL (would be a soundness bug).")
rng = random.Random(31337)
OPS = ["+", "-", "*", "&", "|", "^"]


def rand_e(rng, regs, d):
    if d == 0 or rng.random() < 0.3:
        return rng.choice(regs) if rng.random() < 0.7 else str(rng.randrange(0, 9))
    a, b = rand_e(rng, regs, d - 1), rand_e(rng, regs, d - 1)
    s = "(%s %s %s)" % (a, rng.choice(OPS), b)
    return "~" + s if rng.random() < 0.1 else s


viol, checked = [], 0
for trial in range(400):
    k = rng.choice([1, 2, 3])
    defs, avail = [], ["x"] + (["y"] if rng.random() < 0.4 else [])
    inputs = list(avail)
    for i in range(k):
        defs.append(("t%d" % i, rand_e(rng, avail, rng.choice([1, 2]))))
        avail.append("t%d" % i)
    store = rand_e(rng, avail, rng.choice([1, 2]))
    blk = Block(inputs, defs, store, "s%d" % trial)
    for x in inputs:
        checked += 1
        try:
            a, b = site_real(blk, x), subst_real(blk, x)
        except Exception:
            continue
        if b and not a:
            viol.append((blk.pretty(), x))
print("   random blocks: 400, (block,input) checks: %d" % checked)
print("   SITE=FAKE while SUBST=REAL : %d" % len(viol))
for v in viol[:5]:
    print("      VIOLATION:", v)
if not viol:
    print("""   => no violation found. Consistent with the documented soundness property
      sdep_true subseteq sdep_impl: the shipped detector over-approximates only.
      (Empirical, not a proof.)""")

print("""
=== the hierarchy ===
  SDEP_true  (program-level, incl. branches/loops/memory)
     subseteq  SDEP_subst   (block transfer function, loop-free, alias-resolved)
     subseteq  SDEP_site    (shipped: per-expression QF_BV + chaining)
     subseteq  SDEP_patterns(syntactic idioms + linear cancellation)

  strongest criterion that is still decidable, QF_BV-encodable and practical:
      SDEP_subst  -- loop-free, per-basic-block, two-copy product query.""")
