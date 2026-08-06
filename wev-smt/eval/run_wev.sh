#!/usr/bin/env bash
# WEV-SMT run wrapper (C4-1). Sets the Z3 native library path and runs a CLI main class
# with the full project classpath. All experiment runs should go through this wrapper so
# the -Djava.library.path is never wrong or forgotten.
#
# Usage:  eval/run_wev.sh <fully.qualified.MainClass> [args...]
# Example: eval/run_wev.sh wev.smt.cli.Layer2AuditRun eval/corpus-weakest-manifest-v2.csv
#
# Requires: `mvn -q test-compile` at least once (compiles classes and copies the Z3 natives
# into target/native via the OS-activated profile). This script (re)generates the cached
# dependency classpath if missing.
set -euo pipefail

# Repo root = parent of this script's directory (script lives in eval/).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

NATIVE_DIR="target/native"
CP_FILE="target/cp.txt"

if [[ ! -d "$NATIVE_DIR" ]]; then
  echo "[run_wev] target/native missing — running 'mvn -q test-compile' to compile + copy Z3 natives..." >&2
  mvn -q test-compile
fi
if [[ ! -f "$CP_FILE" ]]; then
  echo "[run_wev] regenerating dependency classpath ($CP_FILE)..." >&2
  mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
fi

if [[ $# -lt 1 ]]; then
  echo "usage: eval/run_wev.sh <MainClass> [args...]" >&2
  exit 2
fi

# Windows Git-Bash uses ';' as the classpath separator; POSIX uses ':'.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac
CP="target/classes${SEP}$(cat "$CP_FILE")"

exec java -Djava.library.path="$NATIVE_DIR" -cp "$CP" "$@"
