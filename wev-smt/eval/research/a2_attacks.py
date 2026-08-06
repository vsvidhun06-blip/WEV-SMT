#!/usr/bin/env python3
"""AUDIT steps 3, 4, 5 -- attack the separating pair, the bounded-prefix subclaim,
and the "any poly-time local analysis" strengthening.

Run:  python eval/research/a2_attacks.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from depprobe import Block, subst_real, site_real
from z3 import BitVec, BitVecVal, Solver, ForAll, Not, sat, unsat, prove, Int

print(__doc__)

# ══ STEP 3 -- attack the separating pair ══════════════════════════════════════
print("=== STEP 3. Can the separating pair be invalidated? ===")

x = BitVec("x", 32)
print("\n(a) exhaustive BV32 identity checks (Z3 proves for ALL 2^32 values):")
for name, lhs, rhs in [
    ("(x+1) - x  ==  1",  (x + 1) - x, BitVecVal(1, 32)),
    ("(x+x) - x  ==  x",  (x + x) - x, x),
    ("(x+1) - x  ==  1  at x = 0xFFFFFFFF (wrap)",
     ((BitVecVal(0xFFFFFFFF, 32) + 1) - BitVecVal(0xFFFFFFFF, 32)), BitVecVal(1, 32)),
    ("(x+1) - x  ==  1  at x = 0x7FFFFFFF (signed overflow point)",
     ((BitVecVal(0x7FFFFFFF, 32) + 1) - BitVecVal(0x7FFFFFFF, 32)), BitVecVal(1, 32)),
]:
    s = Solver()
    s.add(lhs != rhs)
    print("    %-46s %s" % (name, "HOLDS" if s.check() == unsat else "FAILS"))

print("""
(b) hidden assumption -- signed-overflow semantics.
    The tool models BV32 with WRAPPING arithmetic (QfbvConstancyOracle uses bv.add /
    bv.subtract). In real C, `x+1` on a signed int is UB at INT_MAX. Does the separator
    survive under a UB reading?
      - wrapping semantics : (x+1)-x == 1 for all 2^32 values          [proved above]
      - UB semantics       : the compiler may ASSUME no overflow, which makes the
                             expression 1 on every DEFINED execution -- still constant.
    So the separator survives under both readings. It is NOT overflow-dependent.
    Recorded as a hypothesis that must be stated, not as a flaw.""")

print("""
(c) does any front-end simplification collapse the pair before classification?
    LitmusParser.deriveReg stores RegSrc(tryEval(expr), reads). tryEval returns a
    CONSTANT only when the reads are themselves constant; for a symbolic load it is
    null. So `t` is never replaced by `r0+1` anywhere in the pipeline, and both
    programs really do reach classify() with the identical expression "t - r0".
    Verified directly against the shipped Java (scratchpad/audit1.jsh trace).""")

print("""
(d) alternative separating pairs (robustness of the witness):""")
alts = [
    (Block(["r0"], [("t", "r0 + 1")], "t - r0"), Block(["r0"], [("t", "r0 + r0")], "t - r0"),
     'e = "t - r0"'),
    (Block(["r0"], [("t", "r0 ^ 5")], "t ^ r0"), Block(["r0"], [("t", "r0 ^ r0")], "t ^ r0"),
     'e = "t ^ r0"  (bitwise)'),
    (Block(["r0"], [("t", "r0 & 1")], "t & 2"), Block(["r0"], [("t", "r0 + r0")], "t & 2"),
     'e = "t & 2"   (r0 NOT in e)'),
    (Block(["r0"], [("t", "r0 + 1"), ("u", "t - r0")], "u"),
     Block(["r0"], [("t", "r0 + r0"), ("u", "t - r0")], "u"),
     'e = "u"       (r0 NOT in e, depth 2)'),
]
for a, b, lbl in alts:
    ta, tb = subst_real(a, "r0"), subst_real(b, "r0")
    print("    %-34s A:%-5s B:%-5s  %s" % (lbl, "REAL" if ta else "FAKE",
                                           "REAL" if tb else "FAKE",
                                           "SEPARATES" if ta != tb else "does not separate"))
print("""    => the separator is not an artifact of one expression: several independent
       pairs work, including bitwise ones and ones where r0 does not occur in e.""")

# ══ STEP 4 -- attack the bounded-prefix subclaim ══════════════════════════════
print("""
=== STEP 4. The bounded-prefix / bounded-information subclaim ===
Claim under attack: "No bounded amount of surrounding program information suffices;
chain-n requires unfold depth exactly n."

Counter-proposal: an AFFINE SUMMARY. Summarise each register by (coefficient of each
input, constant), both BV32. Size is FIXED -- it does not grow with chain length.""")


def affine_summary(blk, inputs):
    """Exact affine transfer summary, or None if a non-affine operator appears."""
    env = {i: ({i: 1}, 0) for i in inputs}          # (coeffs, const)

    def ev(tokens):
        import re
        s = " ".join(tokens)
        if re.search(r"[*&|^~()]", s):
            return None
        coeffs, const, sign, expect = {}, 0, 1, True
        i = 0
        s = s.replace(" ", "")
        while i < len(s):
            c = s[i]
            if c in "+-":
                if expect:
                    sign = -sign if c == "-" else sign
                else:
                    sign, expect = (-1 if c == "-" else 1), True
                i += 1
                continue
            m = re.match(r"[A-Za-z_]\w*|\d+", s[i:])
            if not m:
                return None
            tok = m.group(0)
            if tok.isdigit():
                const += sign * int(tok)
            else:
                if tok not in env:
                    return None
                tc, tk = env[tok]
                for k, v in tc.items():
                    coeffs[k] = coeffs.get(k, 0) + sign * v
                const += sign * tk
            i += len(tok)
            expect, sign = False, 1
        return coeffs, const

    import re
    for n, e in blk.defs:
        r = ev(re.findall(r"[A-Za-z_]\w*|\d+|[-+*&|^~()]", e))
        if r is None:
            return None
        env[n] = r
    return ev(re.findall(r"[A-Za-z_]\w*|\d+|[-+*&|^~()]", blk.store))


def chain(n):
    defs = [("a1", "x + 1")] + [("a%d" % i, "a%d + 1" % (i - 1)) for i in range(2, n + 1)]
    return Block(["x"], defs, "a%d - x - %d" % (n, n), "chain-%d" % n)


print("\n%-10s %-8s %-22s %-10s %s" % ("block", "truth", "affine summary", "affine says",
                                       "summary size"))
print("-" * 74)
ok = True
for n in (1, 2, 3, 5, 10, 20, 50):
    blk = chain(n)
    t = subst_real(blk, "x") if n <= 20 else False   # SMT only for small n
    r = affine_summary(blk, ["x"])
    says = "REAL" if (r is None or r[0].get("x", 0) % (1 << 32) != 0) else "FAKE"
    truth = "FAKE"                                    # all chain-n are FAKE by construction
    if n <= 20:
        truth = "REAL" if t else "FAKE"
    print("%-10s %-8s %-22s %-10s %s" % (blk.name, truth, str(r), says,
                                         "2 BV32 (constant)"))
    ok = ok and says == truth
print("""
   => an abstraction of CONSTANT size (one coefficient + one constant, independent of
      chain length) decides EVERY chain-n. The chain family therefore does NOT support
      the claim that no bounded amount of program information suffices.""")

print("""
   Stronger observation. For a FIXED width w and a fixed number of inputs m, the set of
   transfer functions BV_w^m -> BV_w is FINITE (of size 2^(w*2^(w*m))). The transfer
   function IS a sound and complete summary. Hence a bounded-state abstraction that is
   exact always exists, trivially, for any fixed (w, m) -- it is astronomically large,
   but "bounded" is the wrong axis on which to claim impossibility.

   The subclaim survives ONLY in this narrow syntactic form:
      for every d there are two blocks agreeing on the store expression AND on the first
      d definitions whose truths differ  (chain-(d+1) vs a modified tail).
   That is a statement about TRUNCATING A PREFIX, not about bounded information.""")

# ══ STEP 5 -- abstraction attack: any poly-time analysis of (e, r) ════════════
print("""
=== STEP 5. Locality vs implementation: give the classifier unlimited power ===
Let the classifier compute ANY function -- symbolic rewriting, canonicalization, full
QF_BV reasoning, even an uncomputable oracle -- of (e, r) ONLY.""")
a = Block(["r0"], [("t", "r0 + 1")], "t - r0")
b = Block(["r0"], [("t", "r0 + r0")], "t - r0")
print("   input to the classifier, program A: (%r, %r)" % (a.store, "r0"))
print("   input to the classifier, program B: (%r, %r)" % (b.store, "r0"))
print("   truth A = %s ,  truth B = %s"
      % ("REAL" if subst_real(a, "r0") else "FAKE", "REAL" if subst_real(b, "r0") else "FAKE"))
print("""   The two inputs are the SAME OBJECT. A function returns one value on one input.
   => the impossibility is one of LOCALITY, not of implementation. No amount of
      reasoning power helps, because the information is absent from the argument.
      This is robust, but it is also the reason the result is shallow: it says
      "a function of X cannot compute something that is not a function of X".""")
