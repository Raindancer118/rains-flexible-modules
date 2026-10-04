#!/usr/bin/env bash
# The end-to-end run: builds RainsSpeedrun and the harness, then plays every scenario on real Paper
# servers with headless bots (e2e-harness, speedrun-e2e) and checks that everything the plugin offers
# was played (CoverageTest before, ZzRuntimeCoverageTest after). Menu screenshots with --screenshots.
#
# Usage: scripts/e2e.sh [--offline] [--only <TestClass[#method]>] [--screenshots] [--skip-build]
#
# Needs Java 25 and network access the first time (Paper, the vanilla server and client jars are
# cached in ${RAINS_E2E_CACHE:-~/.cache/rains-e2e}). Everything a run leaves — server logs, the probe's
# events, the coverage reports — is under speedrun-e2e/target/e2e and e2e-harness/target/e2e.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REACTOR_ROOT"

OFFLINE=""
ONLY=""
SCREENSHOTS=0
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --offline) OFFLINE="-o" ;;
    --only) ONLY="$2"; shift ;;
    --screenshots) SCREENSHOTS=1 ;;
    --skip-build) BUILD=0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

MVN=(mvn --batch-mode --no-transfer-progress $OFFLINE)
started=$(date +%s)

if [ "$BUILD" = 1 ]; then
  echo "[e2e] building the plugin, the harness and the scenarios …"
  # clean install, not verify: the standalone jar shades the module out of the local repository, and
  # its own test (StandaloneJarTest#theJarIsNotStale) is what proves the jar is this build's.
  "${MVN[@]}" -q clean install -pl speedrun-module -am -DskipTests
  "${MVN[@]}" -q clean install -pl speedrun-standalone
  "${MVN[@]}" -q clean install -pl e2e-harness -DskipTests
fi

# A fresh record: the run-time coverage check reads what this run, and only this run, did.
rm -rf speedrun-e2e/target/e2e e2e-harness/target/e2e

echo "[e2e] every command, page, setting and button has a scenario …"
"${MVN[@]}" -q test -pl speedrun-e2e -Dtest=CoverageTest

echo "[e2e] the harness on a plain server …"
"${MVN[@]}" test -pl e2e-harness -Pe2e ${ONLY:+-Dtest=HarnessTest -Dsurefire.failIfNoSpecifiedTests=false}

echo "[e2e] RainsSpeedrun, every scenario …"
if [ -n "$ONLY" ]; then
  "${MVN[@]}" test -pl speedrun-e2e -Pe2e -Dtest="$ONLY" -Dsurefire.failIfNoSpecifiedTests=false
else
  "${MVN[@]}" test -pl speedrun-e2e -Pe2e
fi

if [ "$SCREENSHOTS" = 1 ]; then
  "$SCRIPT_DIR/e2e-screenshots.sh" ${OFFLINE:+--offline}
fi

echo "[e2e] coverage:"
cat speedrun-e2e/target/e2e/coverage.txt 2>/dev/null || true
cat speedrun-e2e/target/e2e/runtime-coverage.txt 2>/dev/null || true
echo "[e2e] done in $(( $(date +%s) - started ))s"
