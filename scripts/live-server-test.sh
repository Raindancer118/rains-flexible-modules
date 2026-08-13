#!/usr/bin/env bash
# Boots a real, unmodified Paper server, installs the freshly built plugins into it, and checks
# that the server actually comes up clean — twice, so a second boot against the data the first
# boot wrote is covered too, which is exactly the class of bug a unit test cannot see (a listener
# that only misbehaves once real Bukkit event dispatch, real file I/O and a real classloader graph
# are involved). This is not a replacement for the unit test suites; it is the layer above them.
#
# What "passes" means, precisely:
#   1. Both boots reach "Done" in the server log within STARTUP_TIMEOUT.
#   2. RainsCoreTestPlugin's own in-server check suite reports zero failures on both boots
#      (RAINSCORE-TEST-RESULT: ALL N CHECKS PASSED) — see RainsCoreTestPlugin's onEnable for what
#      the ~40 checks actually exercise.
#   3. Neither boot's log contains an uncaught exception/stack trace outside the small set of
#      resilience tests that deliberately write broken YAML and expect a WARNUNG about it.
#   4. Every *.yml a plugin wrote under plugins/ parses as valid YAML after both boots — a
#      "corrupted file" is exactly a file that no longer does.
#   5. RainsCore's own stop-hook backup produced a zip on the first clean shutdown — the fix this
#      whole pipeline exists to keep proven, not just unit-tested.
#   6. Both shutdowns exit cleanly (`stop` via RCON, not a kill).
#
# Usage: scripts/live-server-test.sh [--keep]
#   --keep   leave the server directory behind instead of deleting it (for inspecting a failure)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RAINSCORE_ROOT="$(cd "$REACTOR_ROOT/../RainsCore" && pwd)"
TESTPLUGIN_ROOT="$(cd "$REACTOR_ROOT/../RainsCoreTestPlugin" && pwd)"

PAPER_VERSION="26.2"
PAPER_BUILD="111"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-120}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-60}"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d /tmp/rains-live-server-test.XXXXXX)"
cleanup() {
  if [ "$KEEP" = "1" ]; then
    echo "Kept server directory at: $WORKDIR"
  else
    rm -rf "$WORKDIR"
  fi
}
trap cleanup EXIT

log()  { echo "[live-server-test] $*"; }
fail() { echo "[live-server-test] FAIL: $*" >&2; exit 1; }

# ── fetch the real Paper server jar (papermc's Fill API — the old /v2 API was sunset) ──────────
PAPER_JAR="$WORKDIR/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"
log "Fetching Paper ${PAPER_VERSION} build ${PAPER_BUILD} …"
meta="$(curl -sf "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}")"
download_url="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])' <<<"$meta")"
curl -sf -o "$PAPER_JAR" "$download_url" || fail "could not download the Paper server jar"
[ -s "$PAPER_JAR" ] || fail "downloaded Paper jar is empty"

# ── build every module this pipeline installs, so it never tests a stale jar ────────────────────
log "Building RainsCore …"
( cd "$RAINSCORE_ROOT" && mvn -o -q clean install ) || fail "RainsCore did not build"

log "Building RainsCoreTestPlugin …"
( cd "$TESTPLUGIN_ROOT" && mvn -o -q clean package ) || fail "RainsCoreTestPlugin did not build"

log "Building the reactor (speedrun-module/standalone, chained-module/standalone, …) …"
( cd "$REACTOR_ROOT" && mvn -o -q clean install ) || fail "the reactor did not build"

# ── lay out the server directory ─────────────────────────────────────────────────────────────
SERVER_DIR="$WORKDIR/server"
mkdir -p "$SERVER_DIR/plugins"
cp "$PAPER_JAR" "$SERVER_DIR/paper.jar"
echo "eula=true" > "$SERVER_DIR/eula.txt"

cat > "$SERVER_DIR/server.properties" <<'EOF'
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=live-server-test
server-port=25566
level-seed=1
spawn-protection=0
view-distance=6
simulation-distance=4
EOF

find "$RAINSCORE_ROOT/target" -maxdepth 1 -name 'RainsCore-*.jar' \
  ! -name '*-shaded.jar' ! -name 'original-*' -exec cp {} "$SERVER_DIR/plugins/" \;
find "$TESTPLUGIN_ROOT/target" -maxdepth 1 -name 'RainsCoreTestPlugin-*.jar' \
  -exec cp {} "$SERVER_DIR/plugins/" \;
find "$REACTOR_ROOT/speedrun-standalone/target" -maxdepth 1 -name 'RainsSpeedrun-*.jar' \
  ! -name 'original-*' -exec cp {} "$SERVER_DIR/plugins/" \; 2>/dev/null || true
find "$REACTOR_ROOT/chained-standalone/target" -maxdepth 1 -name 'RainsChained-*.jar' \
  ! -name 'original-*' -exec cp {} "$SERVER_DIR/plugins/" \; 2>/dev/null || true

installed="$(find "$SERVER_DIR/plugins" -maxdepth 1 -name '*.jar' -printf '%f\n' | sort)"
[ -n "$installed" ] || fail "no plugin jars were staged into plugins/"
log "Installed jars:"
printf '%s\n' "$installed" | sed 's/^/  /'

RCON_SCRIPT="$SCRIPT_DIR/rcon.py"
[ -f "$RCON_SCRIPT" ] || fail "scripts/rcon.py is missing"
UV_BIN="$(command -v uv || true)"
[ -n "$UV_BIN" ] || fail "uv is not installed (used to run the YAML validation with pyyaml, without a system-wide pip install)"

# ── one boot: start, wait for readiness or death, run the given command list, stop, wait for exit
boot_once() {
  local label="$1"
  local logfile="$SERVER_DIR/logs/latest.log"
  rm -f "$logfile"

  log "Boot ($label): starting the server …"
  ( cd "$SERVER_DIR" && java -Xmx2G -jar paper.jar --nogui \
      > "$SERVER_DIR/stdout-$label.log" 2>&1 & echo $! > "$SERVER_DIR/server.pid" )
  local pid; pid="$(cat "$SERVER_DIR/server.pid")"

  local waited=0
  until [ -f "$logfile" ] && grep -qE '\]: Done \(' "$logfile" 2>/dev/null; do
    if ! kill -0 "$pid" 2>/dev/null; then
      log "── server process died during startup; last 80 log lines: ──"
      tail -n 80 "$SERVER_DIR/stdout-$label.log" 2>/dev/null || true
      fail "Boot ($label): the server process exited before starting"
    fi
    sleep 2
    waited=$((waited + 2))
    if [ "$waited" -ge "$STARTUP_TIMEOUT" ]; then
      log "── startup timed out; last 80 log lines: ──"
      tail -n 80 "$logfile" 2>/dev/null || true
      kill "$pid" 2>/dev/null || true
      fail "Boot ($label): did not reach 'Done' within ${STARTUP_TIMEOUT}s"
    fi
  done
  log "Boot ($label): reached 'Done' after ${waited}s"

  # RainsCoreTestPlugin's own in-server oracle.
  if grep -q 'RAINSCORE-TEST-RESULT: ALL .* CHECKS PASSED' "$logfile"; then
    log "Boot ($label): RainsCoreTestPlugin — all checks passed"
  elif grep -q 'RAINSCORE-TEST-RESULT:' "$logfile"; then
    log "── RainsCoreTestPlugin reported failures: ──"
    grep -A2 '^FAIL' "$logfile" || true
    fail "Boot ($label): RainsCoreTestPlugin reported failing checks"
  else
    fail "Boot ($label): RainsCoreTestPlugin never printed a RAINSCORE-TEST-RESULT line — it did not run"
  fi

  # A stray uncaught exception anywhere in the log is a real failure. The handful of deliberate
  # resilience tests (corrupted-file recovery) log a WARNUNG, never a Java stack trace, so this
  # stays a clean signal rather than something that has to allowlist test fixtures.
  if grep -qE '^\s+at [a-zA-Z0-9_.$]+\(' "$logfile"; then
    log "── a stack trace appeared in the server log: ──"
    grep -B5 -E '^\s+at [a-zA-Z0-9_.$]+\(' "$logfile" | head -60
    fail "Boot ($label): an uncaught exception was logged"
  fi

  # Every YAML file a plugin wrote still has to parse. A ".broken-" file is the plugin's own,
  # deliberate rename of something it refused to trust — those are expected from the resilience
  # tests and are excluded on purpose; anything still named *.yml has to be valid.
  local corrupted=0
  while IFS= read -r -d '' f; do
    if ! "$UV_BIN" run --quiet --with pyyaml -- python3 -c \
        "import sys,yaml; yaml.safe_load(open(sys.argv[1]))" "$f" 2>/dev/null; then
      echo "  corrupted: ${f#"$SERVER_DIR"/}"
      corrupted=1
    fi
  done < <(find "$SERVER_DIR/plugins" -name '*.yml' -print0)
  [ "$corrupted" = "0" ] || fail "Boot ($label): at least one plugin data file failed to parse as YAML"
  log "Boot ($label): every plugins/**/*.yml parses cleanly"

  log "Boot ($label): stopping the server via RCON …"
  python3 "$RCON_SCRIPT" 127.0.0.1 25575 live-server-test stop >/dev/null 2>&1 || true

  local stopped=0
  for _ in $(seq 1 "$SHUTDOWN_TIMEOUT"); do
    kill -0 "$pid" 2>/dev/null || { stopped=1; break; }
    sleep 1
  done
  if [ "$stopped" != "1" ]; then
    kill "$pid" 2>/dev/null || true
    fail "Boot ($label): did not shut down within ${SHUTDOWN_TIMEOUT}s of 'stop'"
  fi
  log "Boot ($label): shut down cleanly"
}

boot_once "first"

# The backup engine this whole incident produced: a clean RainsCore shutdown must leave a zip
# behind. Checked right after the first boot, which is the disable the backup fires on.
backup_dir="$SERVER_DIR/backups/rainscore"
if ! find "$backup_dir" -maxdepth 1 -name 'backup-*.zip' -print -quit 2>/dev/null | grep -q .; then
  fail "no backup-*.zip appeared under $backup_dir after a clean shutdown"
fi
log "Backup engine: a backup zip was produced on shutdown"

# Second boot, reusing everything the first boot wrote — this is what would have caught the
# join/inventory incident's cousin (a listener that behaves on a fresh world but not once real
# data is on disk) and is the whole reason for booting twice instead of once.
boot_once "second"

log "ALL LIVE-SERVER CHECKS PASSED"
