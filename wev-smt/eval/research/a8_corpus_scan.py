#!/usr/bin/env python3
"""Does the unsound fold actually fire on the repository's own litmus corpora?

Extracts every value expression the C front end would hand to classify() -- the second
argument of WRITE_ONCE / atomic_store / smp_store_release, and the RHS of a register
assignment -- then, for each register mentioned, compares the shipped fold's verdict
against Z3 ground truth. Reports only UNSOUND cases (shipped FAKE, truth VARIES).

Run:  python eval/research/a8_corpus_scan.py [roots...]
"""
import sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from a5_shipped_model import shipped_classify
from depprobe import build
from z3 import BitVec, Solver, sat

ROOTS = sys.argv[1:] or ["eval/examples", "eval/corpus", "eval/mined/litmus"]

VAL_PATTERNS = [
    re.compile(r"WRITE_ONCE\s*\(\s*\*?\w+\s*,([^)]*)\)"),
    re.compile(r"smp_store_release\s*\(\s*&?\*?\w+\s*,([^)]*)\)"),
    re.compile(r"atomic_store\w*\s*\(\s*&?\*?\w+\s*,([^)]*)\)"),
    re.compile(r"^\s*(?:\w+\s*:)?\s*\w+\s*=\s*([^;]+);", re.M),
]
IDENT = re.compile(r"[A-Za-z_]\w*")
SKIP = re.compile(r"READ_ONCE|smp_|atomic_|xchg|cmpxchg|\(\*|\bif\b|\bwhile\b")


def varies(expr, reg):
    names = sorted(set(IDENT.findall(expr)))
    env_a, env_b = {}, {}
    for n in names:
        if n == reg:
            env_a[n] = BitVec("a", 32)
            env_b[n] = BitVec("b", 32)
        else:
            v = BitVec("s_" + n, 32)
            env_a[n] = v
            env_b[n] = v
    try:
        va, _ = build(expr, env_a)
        vb, _ = build(expr, env_b)
    except Exception:
        return None
    s = Solver()
    s.add(va != vb)
    return s.check() == sat


seen, files, unsound, checked = set(), 0, [], 0
for root in ROOTS:
    if not os.path.isdir(root):
        continue
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith(".litmus"):
                continue
            files += 1
            try:
                txt = open(os.path.join(dp, fn), errors="replace").read()
            except Exception:
                continue
            for pat in VAL_PATTERNS:
                for m in pat.finditer(txt):
                    e = m.group(1).strip()
                    if not e or SKIP.search(e) or len(e) > 90:
                        continue
                    if not IDENT.search(e):
                        continue
                    key = re.sub(r"\s+", "", e)
                    if key in seen:
                        continue
                    seen.add(key)
                    for reg in sorted(set(IDENT.findall(e))):
                        t = varies(e, reg)
                        if t is None:
                            continue
                        checked += 1
                        if t and not shipped_classify(e, reg):
                            unsound.append((os.path.join(dp, fn), e, reg))

print("=== corpus scan for the unsound fold ===")
print("   roots            : %s" % ", ".join(ROOTS))
print("   .litmus files    : %d" % files)
print("   distinct value expressions : %d" % len(seen))
print("   (expr, reg) pairs checked  : %d" % checked)
print("   *** UNSOUND occurrences    : %d ***" % len(unsound))
for f, e, r in unsound[:25]:
    print("      %-52s  expr=%-28s reg=%s" % (os.path.relpath(f)[-52:], e, r))
if not unsound:
    print("""
   => the bug does NOT fire on the repository's current corpora. It is a latent
      soundness defect: real, reachable, and confirmed by direct probe, but not
      currently exercised by any shipped test. Published numbers are therefore
      very likely unaffected -- though this scan only covers expressions the
      regexes above recognise, so it is indicative, not exhaustive.""")
