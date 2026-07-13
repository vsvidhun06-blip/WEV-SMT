#!/usr/bin/env bash
#
# WEV-SMT artifact reproduction (POPL Artifact Evaluation).
#
# One command reproduces every eval result: unit tests, the weak-memory atlas,
# both scalability sweeps (Day-9 consistency + Day-12 fence/RMW), the optional
# corpus hierarchy-soundness check, the Day-14 edge-case robustness sweep, and all
# plots. Each numeric result is diffed against a known-good snapshot in
# artifact/expected-outputs/ (verdict/atlas snapshots at commit adb65a7; the
# robustness snapshot is Day-14).
#
# Diffs compare only DETERMINISTIC columns (verdicts / match outcomes); timing,
# memory, and minimum-witness-size columns are dropped because they are
# machine- and budget-dependent. The separation atlas and wall-clock lines are
# likewise budget-dependent and are reported, not diffed.
#
# Environment overrides:
#   WEV_REPO            repo root             (default /opt/wev-smt)
#   SWEEP_BUDGET_MIN    per-sweep budget min  (default 3   — consistency verdicts
#                       are budget-independent, so a short budget still reproduces
#                       the diffed result while keeping the run under the time box)
#   SWEEP_PERCALL_SEC   per-call cap seconds  (default 30)
#   CORPUS_BUDGET_MIN   corpus sweep budget   (default 30; only if /corpus mounted)
#
set -euo pipefail
export LC_ALL=C            # byte-wise sort: matches how expected-outputs were generated

REPO="${WEV_REPO:-/opt/wev-smt}"
EVAL="$REPO/eval"
EXP="$REPO/artifact/expected-outputs"
SWEEP_BUDGET_MIN="${SWEEP_BUDGET_MIN:-3}"
SWEEP_PERCALL_SEC="${SWEEP_PERCALL_SEC:-30}"
CORPUS_BUDGET_MIN="${CORPUS_BUDGET_MIN:-30}"

cd "$REPO"
START=$(date +%s)
FAILURES=0
declare -a CHECKLIST=()

note()  { printf '%s\n' "$*"; }
pass()  { CHECKLIST+=("PASS  $1"); note "  [PASS] $1"; }
fail()  { CHECKLIST+=("FAIL  $1"); note "  [FAIL] $1"; FAILURES=$((FAILURES+1)); }
skip()  { CHECKLIST+=("SKIP  $1"); note "  [SKIP] $1"; }

# Deterministic projection of a fresh CSV: drop header, keep columns $2, sort.
proj()     { tail -n +2 "$1" | cut -d, -f"$2" | sort; }
# Expected projection: strip comment + blank lines, sort.
exp_proj() { grep -vE '^[[:space:]]*#' "$1" | sed '/^[[:space:]]*$/d' | sort; }

# Diff a fresh CSV's projected columns against an expected snapshot.
#   $1 label   $2 fresh.csv   $3 cols   $4 expected-file
check_proj() {
  local label="$1" fresh="$2" cols="$3" exp="$4"
  if [[ ! -f "$fresh" ]]; then fail "$label (missing output $fresh)"; return; fi
  if diff <(exp_proj "$exp") <(proj "$fresh" "$cols") > "/tmp/diff.$$" 2>&1; then
    pass "$label"
  else
    fail "$label (projection differs from expected — see below)"
    note "----- diff (expected '<' vs reproduced '>') -----"
    sed 's/^/    /' "/tmp/diff.$$" | head -40
    note "-------------------------------------------------"
  fi
  rm -f "/tmp/diff.$$"
}

note "==================================================================="
note "=== WEV-SMT Artifact Reproduction  (snapshot commit adb65a7)    ==="
note "==================================================================="
note "repo=$REPO  sweep-budget=${SWEEP_BUDGET_MIN}min  per-call=${SWEEP_PERCALL_SEC}s"
note ""

# ── Step 1: unit tests ─────────────────────────────────────────────────────
note "Step 1: Run unit tests (expect 45/45 green)..."
mvn -o -q test
read -r T F E < <(awk -F'[:,]' '/Tests run:/{t+=$2; f+=$4; e+=$6} END{print t+0, f+0, e+0}' \
                    "$REPO"/target/surefire-reports/*.txt)
note "  surefire totals: tests=$T failures=$F errors=$E"
if [[ "$F" -eq 0 && "$E" -eq 0 && "$T" -eq 45 ]]; then
  pass "unit tests 45/45 green"
else
  fail "unit tests (got tests=$T failures=$F errors=$E; expected 45/0/0)"
fi
note ""

# ── Step 2: atlas reconstruction ───────────────────────────────────────────
note "Step 2: Atlas reconstruction (expect 190 compared, 190 matched, 0 mismatched)..."
mvn -o -q exec:exec@atlas | tee "$EVAL/atlas-stdout.txt"
CV="$EVAL/consistency-validation.csv"
if [[ -f "$CV" ]]; then
  compared=$(tail -n +2 "$CV" | awk -F, '$5!="n/a"' | wc -l | tr -d ' ')
  mism=$(tail -n +2 "$CV" | awk -F, '$5=="false"' | wc -l | tr -d ' ')
  note "  validation: compared=$compared  mismatched=$mism"
  if [[ "$compared" -eq 190 && "$mism" -eq 0 ]]; then
    pass "atlas 190/190 matched, 0 mismatched"
  else
    fail "atlas (compared=$compared mismatched=$mism; expected 190/0)"
  fi
else
  fail "atlas (no consistency-validation.csv produced)"
fi
check_proj "atlas validation projection vs expected" \
           "$CV" 1-5 "$EXP/atlas-expected.txt"
note ""

# ── Step 3: scalability sweep (consistency, Day-9) ─────────────────────────
note "Step 3: Scalability sweep (consistency)..."
mvn -o -q exec:exec@scalability-sweep \
    -Dsweep.budget="$SWEEP_BUDGET_MIN" -Dsweep.percall="$SWEEP_PERCALL_SEC"
check_proj "scalability-consistency verdicts vs expected" \
           "$EVAL/scalability-consistency.csv" 1-5 "$EXP/scalability-consistency-expected.csv"
note ""

# ── Step 4: scalability sweep (fences / RMW, Day-12) ───────────────────────
note "Step 4: Scalability sweep (fences/RMW)..."
mvn -o -q exec:exec@scalability-fences \
    -Dsweep.budget="$SWEEP_BUDGET_MIN" -Dsweep.percall="$SWEEP_PERCALL_SEC" \
    | tee "$EVAL/scalability-fences-stdout.txt"
check_proj "scalability-fences verdicts vs expected" \
           "$EVAL/scalability-fences.csv" 1-5 "$EXP/scalability-fences-expected.csv"
# Day-12 stop-trigger (informational): worst fence/RMW overhead vs SBNThread baseline.
grep -h "fence/RMW overhead" "$EVAL/scalability-fences-stdout.txt" || true
note ""

# ── Step 5: corpus hierarchy-soundness (optional, gated on /corpus mount) ──
note "Step 5: Corpus hierarchy-soundness check (skip if no corpus mounted)..."
if [[ -d /corpus ]]; then
  mvn -o -q exec:exec@corpus-validation \
      -Dcorpus.dir=/corpus -Dcorpus.budgetMin="$CORPUS_BUDGET_MIN"
  CC="$EVAL/corpus-validation.csv"
  if [[ -f "$CC" ]]; then
    # Re-derive hierarchy-soundness from the fresh CSV: per fully-validated file
    # (all five models carry a real verdict), check  SC<=TSO<=PSO  and
    # {SC,TSO,PSO}=>WEAKEST.  Columns: file,arch,model,expected,actual,...,status,note
    read -r VAL VIO < <(awk -F, '
      BEGIN { split("SC TSO PSO RA WEAKEST", M, " ") }
      NR==1 { next }
      $8=="OK" || $8=="MATCH" || $8=="MISMATCH" {
        a[$1","$3] = ($5=="ALLOWED") ? 1 : 0; seen[$1]=1
      }
      END {
        for (f in seen) {
          have=1
          for (i=1;i<=5;i++) if (!((f","M[i]) in a)) have=0
          if (!have) continue       # only fully-validated files count
          val++
          sc=a[f",SC"]; tso=a[f",TSO"]; pso=a[f",PSO"]; we=a[f",WEAKEST"]
          ok=1
          if (sc>tso) ok=0                       # SC subset-of TSO
          if (tso>pso) ok=0                       # TSO subset-of PSO
          if ((sc||tso||pso) && !we) ok=0         # {SC,TSO,PSO}=>WEAKEST
          if (!ok) vio++
        }
        print val+0, vio+0
      }' "$CC")
    note "  fully-validated files: $VAL   hierarchy violations: $VIO"
    if [[ "$VIO" -eq 0 ]]; then
      pass "corpus hierarchy-soundness 0 violations ($VAL files)"
    else
      fail "corpus hierarchy-soundness ($VIO violations — soundness regression!)"
    fi
  else
    fail "corpus (no corpus-validation.csv produced)"
  fi
else
  skip "corpus hierarchy-soundness (/corpus not mounted — optional, Reusable-badge extra)"
fi
note ""

# ── Step 6: plots ──────────────────────────────────────────────────────────
note "Step 6: Generate plots..."
if python3 "$REPO/artifact/plots/generate-all.py" "$EVAL"; then
  pass "plots generated (eval/plots/*.pdf,*.png)"
else
  fail "plots (generate-all.py failed)"
fi
note ""

# ── Step 7: robustness sweep (Day-14 edge cases A-K) ───────────────────────
# Each of 11 edge cases must be HANDLED GRACEFULLY (correct verdict, validator
# rejection, resource refusal, or capped timeout) — never a crash or hang. The
# tool exits non-zero iff a case threw an unhandled exception; we also diff the
# deterministic outcome column (time/mem dropped) against the snapshot.
note "Step 7: Robustness sweep (11 edge cases A-K, expect 11/11 handled)..."
RB="$EVAL/robustness-report.txt"
if mvn -o -q exec:exec@robustness | tee "$EVAL/robustness-stdout.txt"; then
  RB_RC=0
else
  RB_RC=$?
fi
if [[ -f "$RB" ]]; then
  handled=$(grep -cE '^[A-K] \|' "$RB" | tr -d ' ')
  note "  robustness cases recorded: $handled (expect 11), sweep exit=$RB_RC"
  if diff <(exp_proj "$EXP/robustness-report-expected.txt") \
          <(grep -E '^[A-K] \|' "$RB" | cut -d'|' -f1,2 | sed 's/[[:space:]]*$//' | sort) \
       > "/tmp/rbdiff.$$" 2>&1 && [[ "$RB_RC" -eq 0 && "$handled" -eq 11 ]]; then
    pass "robustness 11/11 handled, outcomes match expected"
  else
    fail "robustness (handled=$handled exit=$RB_RC; outcome projection differs — see below)"
    note "----- diff (expected '<' vs reproduced '>') -----"
    sed 's/^/    /' "/tmp/rbdiff.$$" | head -40
    note "-------------------------------------------------"
  fi
  rm -f "/tmp/rbdiff.$$"
else
  fail "robustness (no robustness-report.txt produced; sweep exit=$RB_RC)"
fi
note ""

# ── Summary ─────────────────────────────────────────────────────────────────
ELAPSED=$(( $(date +%s) - START ))
note "==================================================================="
note "=== Validation checklist                                        ==="
note "==================================================================="
for line in "${CHECKLIST[@]}"; do note "  $line"; done
note ""
note "Outputs in $EVAL/  (plots in $EVAL/plots/)"
note "Total wall-clock: $((ELAPSED/60))m $((ELAPSED%60))s"
if [[ "$FAILURES" -eq 0 ]]; then
  note "=== Reproduction complete: ALL CHECKS PASSED ==="
  exit 0
else
  note "=== Reproduction FAILED: $FAILURES check(s) did not pass ==="
  exit 1
fi
