#!/usr/bin/env bash
# Zero-dependency build: needs only a JDK (17+) and kotlinc on PATH.
#   scripts/run.sh replay            -> prints the Day 1..6 report
#   scripts/run.sh suite             -> runs the test suite (1 known-gap failure by design)
#   scripts/run.sh suite --skip-known-gap
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT=build/manual
mkdir -p "$OUT"
JAR="$OUT/ledger.jar"
SRCS=$(find src/main/kotlin src/test/kotlin -name '*.kt' | sort)
# Rebuild only when a source is newer than the jar.
if [[ ! -f "$JAR" ]] || [[ -n "$(find src -name '*.kt' -newer "$JAR")" ]]; then
  "$KOTLINC" $SRCS -include-runtime -d "$JAR" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
fi
cmd="${1:-replay}"; shift || true
case "$cmd" in
  replay) exec java -cp "$JAR" ledger.ReplayKt "$@" ;;
  suite)  exec java -cp "$JAR" ledger.SuiteKt "$@" ;;
  *) echo "usage: $0 {replay|suite} [--skip-known-gap]" >&2; exit 2 ;;
esac
