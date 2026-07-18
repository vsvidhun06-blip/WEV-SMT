#!/usr/bin/env bash
#
# WEV-SMT artifact smoke test (TACAS 2027 AE, "Getting started" kick-the-tyres check).
#
# Builds the project and runs the three-way LB separation that is the paper's
# central claim:
#
#   LB-real            real (semantic) dependency  -> thin-air cycle    -> FORBIDDEN
#   LB-fake-xor-cycle  fake (r^r^1) dependency     -> no semantic cycle -> ALLOWED
#   LBfd               fake linear dependency      -> no semantic cycle -> ALLOWED
#
# Runs in well under 60s on a warm Maven repo (~25s build + ~10s solve).
#
# Usage:  bash eval/smoke-test.sh        (from anywhere; resolves the repo root)

set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

FAILS=0
declare -a RESULTS=()

check() { # $1 name  $2 expected  $3 actual
  if [[ "$3" == "$2" ]]; then
    echo "  [PASS] $1 -> $3"
    RESULTS+=("PASS  $1 ($3)")
  else
    echo "  [FAIL] $1 -> got '$3', expected '$2'"
    RESULTS+=("FAIL  $1 (got $3, expected $2)")
    FAILS=$((FAILS + 1))
  fi
}

echo "=== WEV-SMT smoke test =========================================="
START=$(date +%s)

# -- Step 1: build ----------------------------------------------------------
# `package` also copies the Z3 native libraries into target/native via the
# platform-activated z3-natives-* profile in pom.xml.
echo "Step 1: build (mvn package -DskipTests)..."
if ! mvn -o -q package -DskipTests; then
  echo "BUILD FAILED"; exit 1
fi
echo "  build OK"

if [[ ! -f target/cp.txt ]]; then
  mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
fi
# Maven writes cp.txt with the platform's separator (';' on Windows/Git Bash,
# ':' on Linux/macOS), and java requires that same separator - so infer it from
# the file rather than hardcoding, letting this script run under Git Bash too.
CP_RAW="$(cat target/cp.txt)"
if [[ "$CP_RAW" == *';'* ]]; then SEP=';'; else SEP=':'; fi
CP="target/classes${SEP}${CP_RAW}"

if [[ ! -d target/native ]]; then
  echo "  [FAIL] target/native missing - Z3 natives were not copied."; exit 1
fi

# -- Step 2: the LB separation ---------------------------------------------
# NOTE on the fake case: eval/examples/paper/LB-fake-xor.litmus writes r^r = 0,
# so the parser finds no write of 1 and both reads default to the initial write
# -- the LB cycle is never wired and EVERY model trivially allows it. That makes
# it useless as a discriminator. LB-fake-xor-cycle.litmus (r^r^1, folds to the
# constant 1) does wire the cycle, so its ALLOWED verdict is a real model verdict.
echo "Step 2: LB separation under WEAKEST..."

OUT="$(java "-Djava.library.path=target/native" -cp "$CP" wev.smt.cli.WevBatch \
        eval/examples/paper/LB-real.litmus \
        eval/examples/paper/LB-fake-xor-cycle.litmus \
        eval/examples/paper/LBfd.litmus)"

# WevBatch prints one "name|verdict|solve_ms|status" line per file; the parser
# may also emit diagnostic lines, so match on the pipe-separated shape only.
verdict_of() { # $1 test name
  local line
  line="$(grep -E "^$1\|" <<< "$OUT" | head -1)"
  if [[ -z "$line" ]]; then echo "NO-OUTPUT"; return; fi
  local status; status="$(cut -d'|' -f4 <<< "$line")"
  if [[ "$status" != "OK" ]]; then echo "ERROR($status)"; return; fi
  cut -d'|' -f2 <<< "$line"
}

check "LB-real"           FORBIDDEN "$(verdict_of LB-real)"
check "LB-fake-xor-cycle" ALLOWED   "$(verdict_of LB-fake-xor-cycle)"
check "LBfd"              ALLOWED   "$(verdict_of LBfd)"

# -- Summary ----------------------------------------------------------------
ELAPSED=$(( $(date +%s) - START ))
echo ""
echo "=== Summary ====================================================="
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "  wall-clock: ${ELAPSED}s"
if [[ "$FAILS" -eq 0 ]]; then
  echo "=== SMOKE TEST PASSED (3/3) ==="
  exit 0
else
  echo "=== SMOKE TEST FAILED ($FAILS of 3 checks) ==="
  exit 1
fi
