#!/usr/bin/env bash
# Boots YeukSMP (moderation and chat in one jar) beside RainsCore on a real Paper server and checks
# the staff chat as a chat channel: both modules come up, /staffchat <line> from the console is said
# once (not twice now that chat routes channels), and a restart and both shutdowns are clean.
# Routing a player's toggled line needs a player; that part is covered by unit tests.
#
# Uses the jars already built in target/ (`mvn clean install` in RainsCore and the reactor first).
#
# Usage: scripts/live-staffchat-test.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RAINSCORE_ROOT="$(cd "$REACTOR_ROOT/../RainsCore" && pwd)"

PAPER_VERSION="26.2"
PAPER_BUILD="111"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
RCON_PORT=25579
RCON_PASSWORD="live-staffchat-test"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d /tmp/rains-live-staffchat.XXXXXX)"
RUNNING_SERVER_PID=""
cleanup() {
  if [ -n "$RUNNING_SERVER_PID" ] && kill -0 "$RUNNING_SERVER_PID" 2>/dev/null; then
    kill "$RUNNING_SERVER_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$RUNNING_SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$RUNNING_SERVER_PID" 2>/dev/null || true
  fi
  if [ "$KEEP" = "1" ]; then echo "Kept server directory at: $WORKDIR"; else rm -rf "$WORKDIR"; fi
}
trap cleanup EXIT

log()  { echo "[live-staffchat] $*"; }
fail() { echo "[live-staffchat] FAIL: $*" >&2; exit 1; }
FAILURES=0
check() {   # check <description> <command...>: records a failure but keeps going, so one run shows everything
  local what="$1"; shift
  if "$@"; then log "  ok   — $what"; else log "  FAIL — $what"; FAILURES=$((FAILURES + 1)); fi
}

PAPER_JAR="$WORKDIR/paper.jar"
log "Fetching Paper ${PAPER_VERSION} build ${PAPER_BUILD} …"
meta="$(curl -sf "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}")"
url="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])' <<<"$meta")"
curl -sf -o "$PAPER_JAR" "$url" || fail "could not download Paper"

SERVER="$WORKDIR/server"
mkdir -p "$SERVER/plugins"
mv "$PAPER_JAR" "$SERVER/paper.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<EOF
online-mode=false
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASSWORD}
server-port=25569
level-seed=1
spawn-protection=0
view-distance=4
simulation-distance=4
EOF

stage() {   # stage <dir> <name-pattern>
  local jar
  jar="$(find "$1" -maxdepth 1 -name "$2" ! -name 'original-*' ! -name '*-shaded.jar' | head -1)"
  [ -n "$jar" ] || fail "no $2 in $1 — build it first"
  cp "$jar" "$SERVER/plugins/"
  log "  staged $(basename "$jar")"
}
stage "$RAINSCORE_ROOT/target" 'RainsCore-*.jar'
# The answer with its legacy colour codes taken out: "seed §f424242" is "seed 424242" to a reader.
rcon() { python3 "$SCRIPT_DIR/rcon.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" "$1" 2>&1 | sed -E 's/§x(§[0-9a-fA-F]){6}//g; s/§[0-9a-fk-orA-FK-OR]//g' || true; }
LOG="$SERVER/logs/latest.log"
plain() { sed -E 's/\x1b\[[0-9;]*m//g' "$LOG"; }

# wait_for <regex> <seconds>: until the log shows it
wait_for() {
  local waited=0
  until plain | grep -qE "$1"; do
    sleep 1; waited=$((waited + 1))
    [ "$waited" -ge "$2" ] && return 1
  done
  return 0
}
count_in_log() { plain | grep -cE "$1" || true; }

boot() {
  rm -f "$LOG"
  ( cd "$SERVER" && exec java -Xmx2G -jar paper.jar --nogui > "$SERVER/stdout-$1.log" 2>&1 ) &
  RUNNING_SERVER_PID=$!
  local waited=0
  until [ -f "$LOG" ] && grep -qE '\]: Done \(' "$LOG" 2>/dev/null; do
    kill -0 "$RUNNING_SERVER_PID" 2>/dev/null || { tail -n 60 "$SERVER/stdout-$1.log"; fail "server died during boot $1"; }
    sleep 2; waited=$((waited + 2))
    [ "$waited" -ge "$STARTUP_TIMEOUT" ] && { tail -n 60 "$LOG"; fail "boot $1 did not reach Done"; }
  done
  log "Boot $1: Done after ${waited}s"
  if plain | grep -qE 'Error occurred while enabling|Encountered an unexpected exception|was not started'; then
    plain | grep -B2 -A15 -E 'Error occurred while enabling|Encountered an unexpected exception|was not started' | head -60
    fail "boot $1: a plugin or module failed to start"
  fi
}

stop() {
  rcon "stop" >/dev/null
  for _ in $(seq 1 60); do kill -0 "$RUNNING_SERVER_PID" 2>/dev/null || { RUNNING_SERVER_PID=""; return 0; }; sleep 1; done
  fail "server did not stop"
}

seed_of() { rcon "worlds info $1" | grep -oE 'seed -?[0-9]+' | head -1 | awk '{print $2}' || true; }
# wait_until_count <regex> <more-than> <seconds>: until the log shows the line more often than before
wait_until_count() {
  local waited=0
  until [ "$(count_in_log "$1")" -gt "$2" ]; do
    sleep 1; waited=$((waited + 1))
    [ "$waited" -ge "$3" ] && return 1
  done
  return 0
}

stage "$REACTOR_ROOT/yeuksmp/target" 'YeukSMP-*.jar'

boot first
check "moderation is up" wait_for 'Moderation is up' 5
check "chat is up" wait_for 'Chat is up' 5

rcon "staffchat live-staffchat-probe" >/dev/null
check "a console staff line reaches the console record" wait_for 'live-staffchat-probe' 10
sleep 2
check "…exactly once" test "$(count_in_log 'live-staffchat-probe')" = "1"
check "/chat answers the console rather than failing" bash -c "! grep -qiE 'Unknown (or incomplete )?command|exception' <<<\"$(rcon 'chat staff')\""

stop
check "no ERROR line from our plugins in boot 1, shutdown included" \
  bash -c "! sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -E 'ERROR\]' | grep -qE 'RainsCore|YeukSMP'"

boot second
check "both come up again" bash -c "sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -q 'Moderation is up' && sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -q 'Chat is up'"
stop
check "no ERROR line from our plugins in boot 2, shutdown included" \
  bash -c "! sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -E 'ERROR\]' | grep -qE 'RainsCore|YeukSMP'"

if [ "$FAILURES" -gt 0 ]; then
  fail "$FAILURES check(s) failed"
fi
log "ALL LIVE STAFF-CHAT CHECKS PASSED (a player's toggled line is covered by unit tests only)"
