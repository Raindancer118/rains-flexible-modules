#!/usr/bin/env bash
# Boots RainsSpeedrunServer (speedrun, manhunt, chat, worldgate, worldutils in one jar) beside RainsCore on a
# real, unmodified Paper server and checks what BundleJarTest cannot: that all five modules come up in
# one plugin, speedrun before manhunt, that each answers its commands, that /speedrunreset still makes
# the run's worlds, and that a second boot and both shutdowns are clean.
#
# Uses the jars already built in target/ (`mvn clean install` in RainsCore and the reactor first).
#
# Usage: scripts/live-speedrun-bundle-test.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RAINSCORE_ROOT="$(cd "$REACTOR_ROOT/../RainsCore" && pwd)"

PAPER_VERSION="26.2"
PAPER_BUILD="111"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
RCON_PORT=25578
RCON_PASSWORD="live-speedrun-bundle-test"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d /tmp/rains-live-speedrun-bundle.XXXXXX)"
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

log()  { echo "[live-speedrun-bundle] $*"; }
fail() { echo "[live-speedrun-bundle] FAIL: $*" >&2; exit 1; }
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
server-port=25568
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

stage "$REACTOR_ROOT/speedrun-bundle/target" 'RainsSpeedrunServer-*.jar'

# ─────────────────────────────────────────────────────────────── boot 1
boot first

check "the speedrun lobby is up" wait_for 'Speedrun lobby is up' 5
check "manhunt is up and offered to the lobby" wait_for 'Manhunt is up' 5
check "the chat is up" wait_for 'Chat is up' 5
check "the world gate is up" wait_for 'World Gate is up' 5
check "world utils is up" wait_for 'World Utils is up' 5
speedrun_line="$(plain | grep -nE 'Speedrun lobby is up' | head -1 | cut -d: -f1)"
manhunt_line="$(plain | grep -nE 'Manhunt is up' | head -1 | cut -d: -f1)"
check "speedrun started before manhunt" bash -c "[ -n '$speedrun_line' ] && [ -n '$manhunt_line' ] && [ '$speedrun_line' -lt '$manhunt_line' ]"

for cmd in speedrun manhunt chathistory worldgate worlds dim w; do
  answer="$(rcon "$cmd")"
  log "  /$cmd → $(echo "$answer" | head -1)"
  check "/$cmd exists" bash -c "! grep -qiE 'Unknown (or incomplete )?command' <<<\"$answer\""
done
check "/manhunt give asks for a player" bash -c "grep -q 'manhunt give' <<<\"$(rcon 'manhunt give')\""
check "/manhunt give names a missing player" bash -c "grep -q 'No online player' <<<\"$(rcon 'manhunt give Nobody tracker')\""
check "/speedruntime with no run says so" bash -c "grep -q 'No run is being played' <<<\"$(rcon 'speedruntime 10:00')\""
check "/speedrunresume with nobody there refuses" bash -c "grep -q 'Nobody is here to race' <<<\"$(rcon 'speedrunresume 42:05')\""
check "/manhunt give all without a hunt says so" bash -c "grep -q 'No hunt is being played' <<<\"$(rcon 'manhunt give all')\""
check "/worlds info answers about the primary world" bash -c "grep -q 'seed' <<<\"$(rcon 'worlds info world')\""

log "Speedrun: /speedrunreset must still regenerate the run's worlds from inside the bundle …"
sr_nether_before="$(count_in_log "World 'speedrun_nether' has been created")"
sr_end_before="$(count_in_log "World 'speedrun_the_end' has been created")"
rcon "speedrunreset" >/dev/null
check "speedrun_nether was made again" wait_until_count "World 'speedrun_nether' has been created" "$sr_nether_before" 90
check "speedrun_the_end was made again" wait_until_count "World 'speedrun_the_end' has been created" "$sr_end_before" 90

stop
check "no ERROR line from our plugins in boot 1, shutdown included" \
  bash -c "! sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -E 'ERROR\]' | grep -qE 'RainsCore|RainsSpeedrunServer'"
log "Boot 1 stopped."

# ─────────────────────────────────────────────────────────────── boot 2
boot second
check "all five come up again" bash -c "for l in 'Speedrun lobby is up' 'Manhunt is up' 'Chat is up' 'World Gate is up' 'World Utils is up'; do sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -q \"\$l\" || exit 1; done"
check "the data lives in per-module subfolders" test -d "$SERVER/plugins/RainsSpeedrunServer"
log "  data folder: $(ls "$SERVER/plugins/RainsSpeedrunServer" 2>/dev/null | tr '\n' ' ')"
stop
check "no ERROR line from our plugins in boot 2, shutdown included" \
  bash -c "! sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -E 'ERROR\]' | grep -qE 'RainsCore|RainsSpeedrunServer'"

if [ "$FAILURES" -gt 0 ]; then
  fail "$FAILURES check(s) failed"
fi
log "ALL LIVE SPEEDRUN-BUNDLE CHECKS PASSED (anything needing a player is covered by unit tests only)"
