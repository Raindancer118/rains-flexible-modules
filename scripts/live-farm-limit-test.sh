#!/usr/bin/env bash
# Drives RainsFarmLimit on a real, unmodified Paper server over RCON: chickens put in love really breed
# below the limit, really do not above it, and /farms names the crowded chunk.
#
# Uses the jars already built in target/ (RainsCore and the reactor) — it does not build.
#
# Usage: scripts/live-farm-limit-test.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RAINSCORE_ROOT="$(cd "$REACTOR_ROOT/../RainsCore" && pwd)"

PAPER_VERSION="26.2"
PAPER_BUILD="111"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
RCON_PORT=25577
RCON_PASSWORD="live-farm-limit-test"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d /tmp/rains-live-farm-limit.XXXXXX)"
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

log()  { echo "[live-farm-limit] $*"; }
fail() { echo "[live-farm-limit] FAIL: $*" >&2; exit 1; }
FAILURES=0
check() {   # check <description> <command...>: records a failure but keeps going, so one run shows everything
  local what="$1"; shift
  if "$@"; then log "  ok   — $what"; else log "  FAIL — $what"; FAILURES=$((FAILURES + 1)); fi
}

PAPER_JAR="$WORKDIR/paper.jar"
CACHED="$HOME/.cache/rains-e2e/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"
if [ -f "$CACHED" ]; then cp "$CACHED" "$PAPER_JAR"; else
log "Fetching Paper ${PAPER_VERSION} build ${PAPER_BUILD} …"
meta="$(curl -sf "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}")"
url="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])' <<<"$meta")"
curl -sf -o "$PAPER_JAR" "$url" || fail "could not download Paper"
fi

SERVER="$WORKDIR/server"
mkdir -p "$SERVER/plugins"
mv "$PAPER_JAR" "$SERVER/paper.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<EOF
online-mode=false
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASSWORD}
server-port=25567
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
stage "$REACTOR_ROOT/farmlimit-standalone/target" 'RainsFarmLimit-*.jar'
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

# A sealed glass room at y 200 around (x, 8): nothing wanders off and nothing natural spawns inside.
room() {
  local x="$1"
  rcon "forceload add $((x - 16)) -8 $((x + 16)) 24" >/dev/null
  rcon "fill $((x - 4)) 199 4 $((x + 4)) 204 12 minecraft:glass" >/dev/null
  rcon "fill $((x - 3)) 200 5 $((x + 3)) 203 11 minecraft:air" >/dev/null
}
chickens_at() {   # how many chickens stand in the room at x
  rcon "execute positioned $1 201 8 if entity @e[type=minecraft:chicken,distance=..8]" | grep -oE '[0-9]+$' || echo 0
}
in_love() {       # two chickens, ready to breed, with nobody needed to feed them
  rcon "summon minecraft:chicken $1 200 8 {InLove:600}" >/dev/null
  rcon "summon minecraft:chicken $1 200 8 {InLove:600}" >/dev/null
}

boot 1
# 50 chickens in one room would otherwise crush each other down to 24.
rcon "gamerule max_entity_cramming 0" >/dev/null
rcon "gamerule maxEntityCramming 0" >/dev/null
check "the module started" grep -q "Farm limits are on" <(plain)

log "Below the limit: two chickens in love become three."
room 0
in_love 0
for _ in $(seq 1 30); do [ "$(chickens_at 0)" -ge 3 ] && break; sleep 1; done
check "a baby was born below the limit (chickens: $(chickens_at 0))" test "$(chickens_at 0)" -eq 3

log "At the limit: 50 chickens and two more in love stay 52."
room 200
for i in $(seq 0 49); do rcon "summon minecraft:chicken $((197 + i % 7)).5 200 $((5 + i / 7 % 7)).5" >/dev/null; done
in_love 200
sleep 20
check "no baby was born at the limit (chickens: $(chickens_at 200))" test "$(chickens_at 200)" -eq 52
check "the two in love really did try (and lost their love)" \
  test "$(rcon 'execute positioned 200 201 8 if entity @e[type=minecraft:chicken,distance=..8,nbt={InLove:0}]' | grep -oE '[0-9]+$' || echo 0)" -ge 50

log "/farms names the crowded chunk."
FARMS="$(rcon 'farms 30')"
echo "$FARMS" | sed 's/^/    /'
check "/farms lists the farm's chunk" grep -qE "chicken \(52\).*200 8" <<<"$FARMS"
check "/farms leaves out the small room" bash -c '! grep -qE "^ *3 " <<<"$1"' _ "$FARMS"

stop
if [ "$FAILURES" -gt 0 ]; then
  fail "$FAILURES check(s) failed"
fi
log "FARM-LIMIT-LIVE-RESULT: ALL CHECKS PASSED"
