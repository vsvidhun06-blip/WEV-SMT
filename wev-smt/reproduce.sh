#!/usr/bin/env bash
#
# WEV-SMT artifact — top-level reproduction entrypoint.
#
#   ./reproduce.sh --quick        kick-the-tyres check          (~1 minute)
#   ./reproduce.sh --verify       verify all shipped evidence   (~2 minutes)
#   ./reproduce.sh                full reproduction             (long, see below)
#   ./reproduce.sh --regenerate   additionally re-run the in-place pipelines
#   ./reproduce.sh --help
#
# DESIGN NOTE — why this script verifies before it regenerates.
#
# Two of the artifact's pipelines write their results back over the shipped
# evidence files, in place, with no output-directory option:
#
#   * eval/run_all.py            rewrites eval/results.json, eval/numbers.tex,
#                                eval/tables.tex and at least eight result CSVs,
#                                several of them cited by name in the paper
#                                (rc11-direction-check.csv, symbolic-rf-results.csv,
#                                scalability-consistency.csv).
#   * eval/dat3m_lkmm_hardbench.py
#                                rewrites the four dat3m-lkmm-* evidence files.
#
# Both embed per-run timings, so a re-run changes their bytes even when every
# reported number reproduces exactly -- which invalidates the SHA-256 manifest in
# eval/dat3m-lkmm-environment.txt. Running them is therefore NOT the default: an
# evaluator who simply wants to check the artifact should not have their copy of
# the evidence silently replaced. They run only under --regenerate.
#
# Everything that CAN be redirected is redirected: the two Java benchmarks and
# the Claim D scripts write into out/ and are then compared against the shipped
# files. Nothing outside out/ and target/ is written in any mode except
# --regenerate.
#
# The manuscript is never read or written by this script.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
OUT="$ROOT/out"

MODE=full
REGEN=0
for arg in "$@"; do
  case "$arg" in
    --quick)      MODE=quick ;;
    --verify)     MODE=verify ;;
    --regenerate) REGEN=1 ;;
    --help|-h)    sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

PASSES=0; FAILS=0; SKIPS=0
declare -a SUMMARY=()

ok()   { echo "  [PASS] $1";               SUMMARY+=("PASS  $1"); PASSES=$((PASSES+1)); }
bad()  { echo "  [FAIL] $1";               SUMMARY+=("FAIL  $1"); FAILS=$((FAILS+1)); }
skip() { echo "  [SKIP] $1";               SUMMARY+=("SKIP  $1"); SKIPS=$((SKIPS+1)); }
hdr()  { echo; echo "=== $1 ${*:2}"; }

# ---------------------------------------------------------------- prerequisites

need() { # $1 tool  $2 what it is for
  if command -v "$1" >/dev/null 2>&1; then
    return 0
  fi
  echo "MISSING PREREQUISITE: '$1' is not on PATH ($2)." >&2
  echo "This script does not install software. Install it and re-run." >&2
  return 1
}

hdr "0. Prerequisites"
MISSING=0
need java   "JDK 21 — runs the checker and Dat3M"        || MISSING=1
need python "Python 3.11 — drives the pipelines"         || MISSING=1
if [ "$MODE" != verify ]; then
  need mvn  "Maven — builds the checker and copies the Z3 natives" || MISSING=1
fi
[ "$MISSING" -eq 0 ] || { echo; echo "Aborting: prerequisites missing."; exit 1; }

JAVA_V="$(java -version 2>&1 | head -1)"
PY_V="$(python --version 2>&1)"
echo "  java   : $JAVA_V"
echo "  python : $PY_V"
if command -v mvn >/dev/null 2>&1; then echo "  maven  : $(mvn -v 2>/dev/null | head -1)"; fi
if command -v isabelle >/dev/null 2>&1; then
  echo "  isabelle: $(isabelle version 2>/dev/null || echo present)"
else
  echo "  isabelle: not on PATH (only needed to re-check the mechanisation)"
fi

DAT3M_HOME="$ROOT/eval/corpus/Dat3M"
JAR="$DAT3M_HOME/dartagnan/target/dartagnan.jar"
CAT="$DAT3M_HOME/cat/linux-kernel.cat"
HAVE_DAT3M=0
if [ -f "$JAR" ] && [ -f "$CAT" ]; then HAVE_DAT3M=1; fi

# ------------------------------------------------------------------- build step

BUILD_OK=0
build() {
  hdr "1. Build (mvn package -DskipTests)"
  if mvn -o -q package -DskipTests; then
    ok "build"
  else
    bad "build (is the Maven repo warm? try without -o)"; return 1
  fi
  if [ ! -f target/cp.txt ]; then
    mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  fi
  CP_RAW="$(cat target/cp.txt)"
  case "$CP_RAW" in *';'*) SEP=';';; *) SEP=':';; esac
  CP="target/classes${SEP}${CP_RAW}"
  [ -d target/native ] || { bad "target/native missing (Z3 natives not copied)"; return 1; }
  ok "Z3 natives present"
  BUILD_OK=1
}

# Steps needing a compiled tree run only if the build succeeded.
need_build() {
  if [ "$BUILD_OK" -eq 1 ]; then return 0; fi
  skip "$1 — build did not complete"
  return 1
}

runjava() { java "-Djava.library.path=target/native" -cp "$CP" "$@"; }

# --------------------------------------------------------- Dat3M control semantics

dat3m_controls() {
  hdr "Dat3M PASS/FAIL semantics (the five shipped calibration controls)"
  if [ "$HAVE_DAT3M" -eq 0 ]; then
    skip "Dat3M controls — dartagnan.jar or linux-kernel.cat not present"
    echo "       build it with: cd eval/corpus/Dat3M && mvn clean install -DskipTests -pl dartagnan -am"
    return 0
  fi
  local d="$ROOT/eval/generated-dat3m/_controls"
  [ -d "$d" ] || { skip "Dat3M controls — $d not shipped"; return 0; }
  local name expect got line
  # PASS = exists reachable = ALLOWED ; FAIL = exists unreachable = FORBIDDEN.
  for pair in "CTRL-reach-yes:PASS" "CTRL-reach-no:FAIL" \
              "CTRL-LB-nodep-n2:PASS" "CTRL-LB-nodep-n3:PASS" \
              "CTRL-LB-realdep-n2:FAIL"; do
    name="${pair%%:*}"; expect="${pair##*:}"
    if [ ! -f "$d/$name.litmus" ]; then skip "control $name (file absent)"; continue; fi
    line="$(DAT3M_HOME="$DAT3M_HOME" java -jar "$JAR" "$CAT" --target=lkmm \
              "$d/$name.litmus" --property=program_spec 2>&1 | grep -E '^Result:' || true)"
    got="${line#Result: }"
    if [ "$got" = "$expect" ]; then ok "control $name -> $got"; else bad "control $name -> got '$got', expected '$expect'"; fi
  done
}

# ------------------------------------------------------- verify shipped evidence

verify_shipped() {
  hdr "Verify shipped evidence (no files written)"
  local rc=0
  python - "$ROOT" <<'PYEOF' || rc=$?
import csv, hashlib, os, re, sys
root = sys.argv[1]; ev = os.path.join(root, 'eval')
fails = []
def chk(label, got, want):
    good = got == want
    print('  [%s] %s: %s%s' % ('PASS' if good else 'FAIL', label, got,
                               '' if good else ' (expected %s)' % (want,)))
    if not good: fails.append(label)

# --- Table 2, recomputed from the shipped CSV -----------------------------
rows = list(csv.DictReader(open(os.path.join(ev, 'generated-hard-pairs.csv'))))
chk('Table 2 instances', len(rows), 3656)
for col, want in (('weakest', (2588, 1068, 0, 0)),
                  ('folding', (2588, 298, 770, 0)),
                  ('syntactic', (2588, 0, 1068, 0))):
    tp = sum(1 for r in rows if r['required'] == 'FORBIDDEN' and r[col] == 'FORBIDDEN')
    tn = sum(1 for r in rows if r['required'] == 'ALLOWED' and r[col] == 'ALLOWED')
    fp = sum(1 for r in rows if r['required'] == 'ALLOWED' and r[col] == 'FORBIDDEN')
    fn = sum(1 for r in rows if r['required'] == 'FORBIDDEN' and r[col] == 'ALLOWED')
    chk('Table 2 row %-9s (TP,TN,FP,FN)' % col, (tp, tn, fp, fn), want)

import math
def wilson(k, n, z=1.959963985):
    p = k / n; d = 1 + z * z / n; c = p + z * z / (2 * n)
    m = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (round((c - m) / d, 4), round((c + m) / d, 4))
for col, k, want in (('weakest', 3656, (0.999, 1.0)),
                     ('folding', 2886, (0.7759, 0.8023)),
                     ('syntactic', 2588, (0.6929, 0.7224))):
    chk('Wilson 95%% %-9s' % col, wilson(k, 3656), want)

# --- matched-pair sweep ---------------------------------------------------
gp = list(csv.DictReader(open(os.path.join(ev, 'generated-pairs-folding.csv'))))
chk('matched pairs generated', len(gp), 1600)
chk('matched pairs valid', sum(1 for r in gp if r['valid'] == 'true'), 1440)
chk('matched pairs discriminating', sum(1 for r in gp if r['discriminating'] == 'true'), 1015)

# --- Dat3M/LKMM -----------------------------------------------------------
main = list(csv.DictReader(open(os.path.join(ev, 'dat3m-lkmm-hardbench.csv'))))
rings = list(csv.DictReader(open(os.path.join(ev, 'dat3m-lkmm-grounded-rings.csv'))))
excl = list(csv.DictReader(open(os.path.join(ev, 'dat3m-lkmm-excluded.csv'))))
chk('Dat3M scored', len(main), 1350)
chk('Dat3M excluded', len(excl), 2306)
chk('Dat3M scored+excluded', len(main) + len(excl), 3656)
chk('Dat3M agreement', sum(1 for r in main if r['agreement'] == 'true'), 1350)
chk('Dat3M mismatches', sum(1 for r in main if r['agreement'] != 'true'), 0)
for st in ('timeout', 'tool-failure', 'parse-failure'):
    chk('Dat3M scored %s' % st, sum(1 for r in main if r['status'] == st), 0)
chk('grounded rings', len(rings), 390)
chk('grounded expressions', len(set(r['expression'] for r in rings)), 195)
chk('grounded forbidden', sum(1 for r in rings if r['dat3m_verdict'] == 'FORBIDDEN'), 390)
chk('grounded allowed', sum(1 for r in rings if r['dat3m_verdict'] == 'ALLOWED'), 0)
chk('excluded are real parse failures',
    sum(1 for r in excl if 'ParsingException' in r['reason']), 2306)

# --- SHA-256 manifest -----------------------------------------------------
envp = os.path.join(ev, 'dat3m-lkmm-environment.txt')
digests = dict(re.findall(r'^(eval/\S+)\n.*\n\s*([0-9a-f]{64})$', open(envp).read(), re.M))
chk('recorded digests', len(digests), 4)
for rel, want in sorted(digests.items()):
    p = os.path.join(ev, os.path.basename(rel))
    got = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    chk('digest %s' % os.path.basename(rel), got == want, True)

# --- Isabelle: no sorry / no oops ----------------------------------------
def mask_comments(s):
    """Blank out (* ... *) comments, which nest in Isabelle, preserving offsets
    (and newlines) so reported line numbers stay correct."""
    out = list(s)
    depth = i = 0
    while i < len(s):
        if s.startswith('(*', i):
            depth += 1
            out[i] = out[i + 1] = ' '
            i += 2
            continue
        if s.startswith('*)', i) and depth:
            depth -= 1
            out[i] = out[i + 1] = ' '
            i += 2
            continue
        if depth and s[i] != '\n':
            out[i] = ' '
        i += 1
    return ''.join(out)

bad_thy, n_thy = [], 0
for dirpath, _, names in os.walk(os.path.join(root, 'isabelle')):
    for n in sorted(names):
        if not n.endswith('.thy'):
            continue
        n_thy += 1
        src = mask_comments(open(os.path.join(dirpath, n), encoding='utf-8',
                                 errors='replace').read())
        for i, ln in enumerate(src.split('\n'), 1):
            if re.search(r'\bsorry\b|\boops\b', ln):
                bad_thy.append('%s:%d' % (n, i))
chk('theories scanned', n_thy, 11)
chk('theories with sorry/oops', bad_thy, [])

print()
sys.exit(1 if fails else 0)
PYEOF
  if [ "$rc" -eq 0 ]; then ok "shipped evidence verified"; else bad "shipped evidence verification"; fi
}

# ------------------------------------------------------------------ Claim D

claimd() {
  hdr "Claim D / bitwidth synthesis (App. A.5)"
  local sb="$OUT/claimD"
  mkdir -p "$sb"
  # The claimD scripts write their JSON next to themselves, so run a copy in
  # out/ rather than let them rewrite the shipped outputs in place.
  cp research/claimD/claimD_synth.py research/claimD/claimD_restricted.py \
     research/claimD/claimD_probe.py "$sb/"
  ( cd "$sb" && python claimD_synth.py >synth.log 2>&1 \
                && python claimD_restricted.py >restricted.log 2>&1 \
                && python claimD_probe.py >probe.log 2>&1 ) || { bad "claimD scripts"; return 0; }
  local diffs=0
  for j in claimD-synthesis.json claimD-counts.json claimD-restricted.json; do
    if python -c "import json,sys; a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2])); sys.exit(0 if a==b else 1)" \
         "research/claimD/$j" "$sb/$j"; then :; else diffs=$((diffs+1)); echo "       differs: $j"; fi
  done
  if [ "$diffs" -eq 0 ]; then ok "claimD reproduces (3/3 JSON identical to shipped)"
  else bad "claimD: $diffs of 3 outputs differ from shipped"; fi
}

# ------------------------------------------------------------------ Isabelle

isabelle_build() {
  hdr "Isabelle mechanisation (§4.3)"
  if ! command -v isabelle >/dev/null 2>&1; then
    skip "Isabelle not on PATH — mechanisation not re-checked"
    echo "       to verify: isabelle build -d isabelle -c WEV_SMT WEV_SMT_PwT   (~33 s)"
    return 0
  fi
  if ( cd isabelle && isabelle build -d . -c WEV_SMT WEV_SMT_PwT ); then
    ok "isabelle build WEV_SMT WEV_SMT_PwT (BUILD_EXIT=0)"
  else
    bad "isabelle build"
  fi
}

# ------------------------------------------- long regenerations (redirected)

hardbench() {
  hdr "Table 2 — HardExprBenchRun (regenerated into out/)"
  need_build "Table 2 regeneration" || return 0
  echo "  NOTE: 3,656 solver instances. Duration was not measured in this environment."
  mkdir -p "$OUT"
  if runjava wev.smt.cli.HardExprBenchRun \
        eval/generated-hard-candidates.json "$OUT/generated-hard-pairs.csv" 2 3 >"$OUT/hardbench.log" 2>&1; then
    if diff -q eval/generated-hard-pairs.csv "$OUT/generated-hard-pairs.csv" >/dev/null; then
      ok "Table 2 CSV regenerated, identical to shipped"
    else
      bad "Table 2 CSV regenerated but DIFFERS from shipped (see $OUT/generated-hard-pairs.csv)"
    fi
  else
    bad "HardExprBenchRun failed (see $OUT/hardbench.log)"
  fi
}

pairsweep() {
  hdr "Matched-pair sweep — GeneratedPairsRun (regenerated into out/)"
  need_build "matched-pair regeneration" || return 0
  echo "  NOTE: duration was not measured in this environment."
  echo "  NOTE: the current source emits the 17-column folding schema, so this is"
  echo "        compared against eval/generated-pairs-folding.csv. The 15-column"
  echo "        eval/generated-pairs.csv is a superseded-schema snapshot of the same"
  echo "        run; the two agree on all 15 shared columns."
  mkdir -p "$OUT"
  if runjava wev.smt.cli.GeneratedPairsRun \
        "$OUT/generated-pairs-folding.csv" "$OUT/generated" 2 6 >"$OUT/pairs.log" 2>&1; then
    if diff -q eval/generated-pairs-folding.csv "$OUT/generated-pairs-folding.csv" >/dev/null; then
      ok "matched-pair CSV regenerated, identical to shipped"
    else
      bad "matched-pair CSV regenerated but DIFFERS from shipped"
    fi
  else
    bad "GeneratedPairsRun failed (see $OUT/pairs.log)"
  fi
}

# -------------------------------------------- in-place pipelines (--regenerate)

regenerate_inplace() {
  hdr "IN-PLACE REGENERATION (--regenerate)"
  echo "  WARNING: the two pipelines below overwrite shipped evidence in eval/."
  echo "  Both embed per-run timings, so the files will differ byte-wise even when"
  echo "  every reported number reproduces, and the SHA-256 manifest in"
  echo "  eval/dat3m-lkmm-environment.txt will be replaced."
  echo
  echo "  Step: python eval/run_all.py            (~35 min; ~2.5 min with --quick)"
  if python eval/run_all.py; then ok "run_all.py"; else bad "run_all.py"; fi
  echo
  echo "  Step: python eval/dat3m_lkmm_hardbench.py   (~10 min, measured)"
  if python eval/dat3m_lkmm_hardbench.py; then ok "dat3m_lkmm_hardbench.py"; else bad "dat3m_lkmm_hardbench.py"; fi
}

# --------------------------------------------------------------------- driver

echo
echo "WEV-SMT artifact reproduction — mode: $MODE$([ "$REGEN" -eq 1 ] && echo ' +regenerate')"
echo "root: $ROOT"

case "$MODE" in
  quick)
    build || true
    hdr "2. Smoke test (three-way LB separation)"
    if bash eval/smoke-test.sh; then ok "smoke test (3/3)"; else bad "smoke test"; fi
    dat3m_controls
    ;;
  verify)
    verify_shipped
    dat3m_controls
    claimd
    isabelle_build
    ;;
  full)
    build || true
    hdr "2. Smoke test (three-way LB separation)"
    if bash eval/smoke-test.sh; then ok "smoke test (3/3)"; else bad "smoke test"; fi
    verify_shipped
    dat3m_controls
    claimd
    isabelle_build
    hardbench
    pairsweep
    [ "$REGEN" -eq 1 ] && regenerate_inplace
    ;;
esac

echo
echo "=== Summary ====================================================="
for s in ${SUMMARY[@]+"${SUMMARY[@]}"}; do echo "  $s"; done
echo "-----------------------------------------------------------------"
echo "  passed: $PASSES   failed: $FAILS   skipped: $SKIPS"
if [ "$FAILS" -eq 0 ]; then
  echo "=== REPRODUCTION OK ==="
  exit 0
else
  echo "=== REPRODUCTION FAILED ($FAILS check(s)) ==="
  exit 1
fi
