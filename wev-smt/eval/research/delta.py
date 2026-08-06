#!/usr/bin/env python3
"""STEP 5 -- mechanical OLD/NEW/DELTA over every leaf value in the two snapshots.

Nothing is compared by eye. Every scalar in pre-fix-snapshot.json is looked up in
post-fix-snapshot.json and classified UNCHANGED / CHANGED / ADDED / REMOVED.

Fields that are timestamps or wall-clock measurements are reported separately as
NON-DETERMINISTIC, because they differ between any two runs and carry no scientific
content.

Also diffs eval/numbers.tex (the 100 \\newcommand macros the paper interpolates).

Run:  python eval/research/delta.py
"""
import json, os, re, sys

EVAL = "eval"
PRE = os.path.join(EVAL, "pre-fix-snapshot.json")
POST = os.path.join(EVAL, "post-fix-snapshot.json")

# Fields that are wall-clock measurements or timestamps. These differ between ANY two
# runs on the same binary and carry no scientific content, so they are reported
# separately rather than counted as result changes.
#   /_status/<step>/seconds       per-step wall time
#   /scalability/at_n_max/<fam>/<model>   per-model SOLVE TIME IN MS at n=16
#     (the VERDICT for each family lives in /scalability/.../verdict and is compared
#      as a normal deterministic value)
NONDET = re.compile(
    r"(wall_seconds|/seconds$|ran_at|_ms$|_ms_|/ms$|generated_at|/generated$|timestamp"
    r"|duration|/at_n_max/[^/]+/(SC|TSO|RA|PSO|WEAKEST|PSO_RA_max)$|lb_real_vs_fake_ratio$)")


def flatten(o, p=""):
    out = {}
    if isinstance(o, dict):
        for k, v in o.items():
            out.update(flatten(v, p + "/" + str(k)))
    elif isinstance(o, list):
        out[p] = json.dumps(o, sort_keys=True)
    else:
        out[p] = o
    return out


pre, post = flatten(json.load(open(PRE))), flatten(json.load(open(POST)))
keys = sorted(set(pre) | set(post))

changed, unchanged, nondet, added, removed = [], [], [], [], []
for k in keys:
    if k not in post:
        removed.append(k)
        continue
    if k not in pre:
        added.append(k)
        continue
    a, b = pre[k], post[k]
    if a == b:
        (nondet if NONDET.search(k) else unchanged).append(k)
    else:
        (nondet if NONDET.search(k) else changed).append((k, a, b))

print("=== STEP 5 delta: eval/pre-fix-snapshot.json vs eval/post-fix-snapshot.json ===")
print("   leaf values compared      : %d" % len(keys))
print("   UNCHANGED (deterministic) : %d" % len(unchanged))
print("   CHANGED   (deterministic) : %d" % len(changed))
print("   non-deterministic fields  : %d  (timings/timestamps, excluded)" % len(nondet))
print("   added / removed keys      : %d / %d" % (len(added), len(removed)))

if changed:
    print("\n   *** CHANGED DETERMINISTIC VALUES ***")
    for k, a, b in changed:
        print("      %-56s OLD=%-18s NEW=%s" % (k, a, b))
else:
    print("\n   => every deterministic published value is UNCHANGED.")
if added:
    print("\n   added keys:")
    for k in added[:20]:
        print("      %s" % k)
if removed:
    print("\n   removed keys:")
    for k in removed[:20]:
        print("      %s" % k)

# ── numbers.tex ───────────────────────────────────────────────────────────────
MAC = re.compile(r"\\newcommand\{?\\([A-Za-z]+)\}?\{([^}]*)\}")


def macros(path):
    if not os.path.isfile(path):
        return {}
    return {m.group(1): m.group(2)
            for m in MAC.finditer(open(path, encoding="utf-8", errors="replace").read())}


old_m = macros(os.path.join(EVAL, "_baseline_backup", "numbers.tex.pre"))
new_m = macros(os.path.join(EVAL, "numbers.tex"))
print("\n=== eval/numbers.tex (paper macros) ===")
print("   macros before / after : %d / %d" % (len(old_m), len(new_m)))
TIMING_MACRO = re.compile(r"(Ms$|Runtime|Seconds|LbRealFakeRatio$|Scal[A-Za-z]+(SC|TSO|PSORA|WEAKEST)$)")
diff = [(k, old_m.get(k), new_m.get(k)) for k in sorted(set(old_m) | set(new_m))
        if old_m.get(k) != new_m.get(k)]
sci = [d for d in diff if not TIMING_MACRO.search(d[0])]
tim = [d for d in diff if TIMING_MACRO.search(d[0])]
print("   changed macros: %d total  (%d timing, %d SCIENTIFIC)"
      % (len(diff), len(tim), len(sci)))
if sci:
    print("   *** CHANGED SCIENTIFIC MACROS ***")
    for k, a, b in sci:
        print("      \\%-34s OLD=%-16s NEW=%s" % (k, a, b))
else:
    print("   => NO scientific macro changed; all %d differences are wall-clock timings."
          % len(tim))
    for k, a, b in tim:
        print("      (timing) \\%-28s OLD=%-10s NEW=%s" % (k, a, b))

# ── tables.tex ────────────────────────────────────────────────────────────────
def norm(p):
    if not os.path.isfile(p):
        return None
    return [l.rstrip() for l in open(p, encoding="utf-8", errors="replace")]


ot = norm(os.path.join(EVAL, "_baseline_backup", "tables.tex.pre"))
nt = norm(os.path.join(EVAL, "tables.tex"))
print("\n=== eval/tables.tex (Tables 1-4 bodies) ===")
if ot is None or nt is None:
    print("   (one side missing)")
elif ot == nt:
    print("   => byte-identical (%d lines)." % len(nt))
else:
    print("   *** DIFFERS ***")
    for i, (a, b) in enumerate(zip(ot, nt)):
        if a != b:
            print("      line %d\n        OLD %s\n        NEW %s" % (i + 1, a, b))

sys.exit(0)
