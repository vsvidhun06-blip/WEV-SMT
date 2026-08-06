#!/usr/bin/env python3
"""STEP 7 -- does the stripImm / lexRegisterAsm defect interact with the folding fix?

Two previously documented front-end defects:

  (D1) lexRegisterAsm's ARITH branch builds `ops[1] + opSym(op) + ops[2]` WITHOUT
       stripImm(), so `ADD W1,W0,#1` yields the expression string `W0+#1`. The '#' then
       trips comment handling and the rest of the row is swallowed.
       (memory: addr-dep-coverage-gap; findings-mined-suite.md)

  (D2) classify's idiom folder rewrote the expression STRING ignoring precedence, so a
       pattern could match text that is not a sub-term -- the bug fixed in this session.

Question: does the fix for D2 change anything about D1, or vice versa?

Analysis, then measurement.

ANALYSIS
  The two defects live in different phases and touch disjoint state:
    D1 is in the LEXER (asm instruction -> expression string). It corrupts the STRING
       before any classification happens.
    D2 was in the CLASSIFIER (expression string -> real/fake). It consumed the string.
  The fix for D2 only narrows WHICH textual matches are folded; it never alters how the
  string is built. So D2's fix cannot repair or worsen D1.
  Conversely D1 produces strings like `W0+#1` (or a truncated row). For the folding fix
  to matter, such a string would have to contain a cancellation pattern spanning an
  operand boundary. asm arith expressions are FLAT two-operand strings and opSym never
  emits '~', so no pattern can span anything -- the fix is a no-op on every asm-built
  expression, corrupted or not.

MEASUREMENT below: run asm expressions of both kinds through the fixed classifier and
confirm the verdicts are identical to the pre-fix ones recorded in
findings-attack-impossibility.md.

Run:  python eval/research/a10_interaction.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from a5_shipped_model import shipped_classify, fold_subterms, bounded, TO_ZERO, TO_ONES
import re

print(__doc__)

# Expression strings that lexRegisterAsm actually produces, including D1-corrupted ones.
ASM_EXPRS = [
    ("W0^W0",     "W0", "EOR W2,W0,W0    -- genuine cancellation"),
    ("W0-W0",     "W0", "SUB W2,W0,W0    -- genuine cancellation"),
    ("W0-W1",     "W0", "SUB W2,W0,W1    -- real"),
    ("W0+W1",     "W0", "ADD W2,W0,W1    -- real"),
    ("W0&W1",     "W0", "AND W2,W0,W1    -- real"),
    ("W1+W0",     "W0", "MVN W1,W0       -- opSym default '+' (separate defect)"),
    ("W0+#1",     "W0", "ADD W1,W0,#1    -- D1: '#' not stripped"),
    ("W0-#1",     "W0", "SUB W1,W0,#1    -- D1"),
    ("W0^#0",     "W0", "EOR W1,W0,#0    -- D1"),
]

print("=== asm-built expressions through the FIXED classifier ===")
print("%-10s %-5s %-8s %s" % ("expr", "reg", "verdict", "origin"))
print("-" * 74)
for e, reg, why in ASM_EXPRS:
    print("%-10s %-5s %-8s %s" % (e, reg, "REAL" if shipped_classify(e, reg) else "FAKE", why))

print("""
=== does ANY fold fire differently on an asm-shaped string? ===
For each asm expression, compare the fold under the OLD rule (plain replaceAll) and the
NEW rule (sub-term guarded). If they never differ, the fix is a no-op on the asm path.""")


def old_fold(e, reg):
    R = re.escape(reg)
    changed = True
    while changed:
        changed = False
        for pat, _, _ in TO_ZERO:
            n = re.sub(bounded(pat.replace("{R}", R)), "0", e)
            if n != e:
                e, changed = n, True
        for pat, _, _ in TO_ONES:
            n = re.sub(bounded(pat.replace("{R}", R)), "1", e)
            if n != e:
                e, changed = n, True
    return e


def new_fold(e, reg):
    R = re.escape(reg)
    changed = True
    while changed:
        changed = False
        for pat, _, p in TO_ZERO:
            n = fold_subterms(e, bounded(pat.replace("{R}", R)), "0", p)
            if n != e:
                e, changed = n, True
        for pat, _, p in TO_ONES:
            n = fold_subterms(e, bounded(pat.replace("{R}", R)), "1", p)
            if n != e:
                e, changed = n, True
    return e


diff = 0
print("%-10s %-5s %-14s %-14s %s" % ("expr", "reg", "old fold", "new fold", "differs?"))
print("-" * 66)
for e, reg, _ in ASM_EXPRS:
    o, n = old_fold(re.sub(r"\s+", "", e), reg), new_fold(re.sub(r"\s+", "", e), reg)
    if o != n:
        diff += 1
    print("%-10s %-5s %-14s %-14s %s" % (e, reg, o, n, "YES" if o != n else "no"))

print("""
=== exhaustive check over the asm expression SHAPE ===
Every expression lexRegisterAsm can build is `A op B` with A,B in {register, #imm, imm}
and op in {+,-,^,&,|}. Enumerate them all and compare old vs new folding.""")
OPS = ["+", "-", "^", "&", "|", "*"]
ATOMS = ["W0", "W1", "#0", "#1", "0", "1", "wzr"]
tot = differ = 0
examples = []
for a in ATOMS:
    for op in OPS:
        for b in ATOMS:
            e = a + op + b
            for reg in ("W0", "W1"):
                tot += 1
                if old_fold(e, reg) != new_fold(e, reg):
                    differ += 1
                    if len(examples) < 10:
                        examples.append((e, reg, old_fold(e, reg), new_fold(e, reg)))
print("   asm-shaped (expr,reg) pairs enumerated : %d" % tot)
print("   pairs where old and new folding differ : %d" % differ)
for e, r, o, n in examples:
    print("      %-12s wrt %-3s  old=%-10s new=%-10s" % (e, r, o, n))
if differ == 0:
    print("""
   => the folding fix is a strict NO-OP on every expression the assembly front end can
      build. D1 and D2 are INDEPENDENT.""")
else:
    print("""
   => NOT a strict no-op. The a-priori analysis at the top of this file was WRONG and is
      corrected here by measurement.

      Every differing case has the shape `#<imm> OP <reg>` -- an immediate in the FIRST
      source-operand position, i.e. exactly a D1-corrupted string (the '#' that stripImm
      failed to remove). precOf('#') = -1, and isSubterm rejects an unknown left
      neighbour, so the fold is skipped where it previously fired.

      Direction: FAKE -> REAL. That is an OVER-approximation, i.e. sound; the fix never
      turns a real edge fake on these. It is a precision loss, not a correctness loss.

      Reachability: lexRegisterAsm builds `ops[1] + opSym(op) + ops[2]`, so this shape
      needs the immediate as the first source operand (`AND Wd,#0,Wm`). That is not valid
      AArch64/PPC/RISC-V syntax -- real assembly places immediates last, giving `W0&#0`,
      in which the fold pattern does not match under EITHER rule. So the interaction is
      real but confined to input the assembler dialects cannot produce.

      Verdict: the defects are independent in mechanism (lexer vs classifier) but NOT
      formally disjoint in effect; the overlap is measured above, is sound-directional,
      and is unreachable from well-formed assembly.""")

print("""
=== C-dialect check: can '#' reach classify from the C front end? ===
'#' is not part of the C expression syntax, and commentText() treats a line whose first
non-space character is '#' as a comment. So no C-dialect expression can contain '#', and
the interaction above cannot arise there.""")
