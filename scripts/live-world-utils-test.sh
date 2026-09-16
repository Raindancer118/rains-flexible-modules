#!/usr/bin/env bash
# Drives RainsWorldUtils and RainsSpeedrun on a real, unmodified Paper server over RCON, and checks what a
# unit test cannot: that worlds are really created with the seed asked for, really come back after a reset
# with the same seed and their game rules, really disappear on delete, are really loaded again after a
# restart, and that the seed history survives one. And that /speedrunreset really regenerates the run's
# nether and end, not only its overworld.
#
# What it cannot check: anything that needs a player on the server — landing positions, the portal links,
# a locked End refusing /w. Those are covered by unit tests only, and say so in the summary.
#
# Uses the jars already built in target/ (run `mvn clean install` in RainsCore and the reactor first) —
# it does not build, so it tests exactly the jars it is pointed at.
#
# Usage: scripts/live-world-utils-test.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RAINSCORE_ROOT="$(cd "$REACTOR_ROOT/../RainsCore" && pwd)"

PAPER_VERSION="26.2"
PAPER_BUILD="111"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
RCON_PORT=25577
RCON_PASSWORD="live-world-utils-test"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d /tmp/rains-live-world-utils.XXXXXX)"
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

log()  { echo "[live-world-utils] $*"; }
fail() { echo "[live-world-utils] FAIL: $*" >&2; exit 1; }
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
stage "$REACTOR_ROOT/worldutils-bundle/target" 'RainsWorldUtils-*.jar'
stage "$REACTOR_ROOT/speedrun-standalone/target" 'RainsSpeedrun-*.jar'

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

# ─────────────────────────────────────────────────────────────── boot 1
boot first

check "both modules of RainsWorldUtils are up" wait_for 'World Utils is up' 5
check "the world gate is up in the same jar" wait_for 'World Gate is up' 5

log "Creating a family with seed 424242 …"
rcon "worlds create wutest family 424242" >/dev/null
check "wutest is created" wait_for "World 'wutest' has been created" 60
check "wutest_nether is created" wait_for "World 'wutest_nether' has been created" 60
check "wutest_the_end is created" wait_for "World 'wutest_the_end' has been created" 60
check "the overworld of the family has seed 424242" test "$(seed_of wutest)" = "424242"
check "its nether has the same seed" test "$(seed_of wutest_nether)" = "424242"
check "its end has the same seed" test "$(seed_of wutest_the_end)" = "424242"
check "the nether is a nether" bash -c "grep -q 'the Nether' <<<\"$(rcon 'worlds info wutest_nether')\""

log "Setting a game rule, difficulty and a border, then resetting the family with the same seed …"
log "  gamerule answered: $(rcon 'execute in minecraft:wutest run gamerule keep_inventory true' | head -1)"
rcon 'execute in minecraft:wutest run worldborder set 3000' >/dev/null
end_made="$(count_in_log "World 'wutest_the_end' has been created")"
rcon "worlds regen wutest same family confirm" >/dev/null
check "the whole family is made again" wait_until_count "World 'wutest_the_end' has been created" "$end_made" 90
sleep 2
check "…the overworld with the same seed" test "$(seed_of wutest)" = "424242"
check "…the end with the same seed" test "$(seed_of wutest_the_end)" = "424242"
rule_now="$(rcon 'execute in minecraft:wutest run gamerule keep_inventory')"
log "  gamerule now: $rule_now"
check "the game rule came across" bash -c "grep -qi 'true' <<<\"$rule_now\""
border_now="$(rcon 'execute in minecraft:wutest run worldborder get')"
log "  border now: $border_now"
check "the border came across" bash -c "grep -q '3000' <<<\"$border_now\""

log "Resetting only the overworld with a random seed …"
made="$(count_in_log "World 'wutest' has been created")"
rcon "worlds regen wutest random confirm" >/dev/null
check "wutest is made again" wait_until_count "World 'wutest' has been created" "$made" 90
sleep 2
new_seed="$(seed_of wutest)"
check "a random reset gives a different seed ($new_seed)" bash -c "[ -n '$new_seed' ] && [ '$new_seed' != '424242' ]"
check "…and leaves its nether alone" test "$(seed_of wutest_nether)" = "424242"
seeds_out="$(rcon 'worlds seeds wutest')"
log "  seeds: $(echo "$seeds_out" | tr '\n' '|')"
check "the seed history lists the old seed" bash -c "grep -q '424242' <<<\"$seeds_out\""
check "…and the new one" bash -c "grep -q -- '$new_seed' <<<\"$seeds_out\""

log "A name ending in _nether, then deleting it …"
rcon "worlds create solo_nether 777" >/dev/null
wait_for "World 'solo_nether' has been created" 60 || true
check "solo_nether is a nether without being told" bash -c "grep -q 'the Nether' <<<\"$(rcon 'worlds info solo_nether')\""
rcon "worlds delete solo_nether confirm" >/dev/null
check "solo_nether is deleted" wait_for "World 'solo_nether' has been deleted" 30
check "…and no longer loaded" bash -c "grep -q 'There is no loaded world' <<<\"$(rcon 'worlds info solo_nether')\""
check "…but its seed is still in the history" bash -c "grep -q '777' <<<\"$(rcon 'worlds seeds solo_nether')\""

log "Refusals …"
check "the primary world cannot be reset" bash -c "grep -q \"server's own worlds\" <<<\"$(rcon 'worlds regen world confirm')\""
check "the console without confirm is asked to confirm" bash -c "grep -q 'confirm' <<<\"$(rcon 'worlds delete wutest')\""
check "a world that is still there after the refusal" bash -c "grep -q 'seed' <<<\"$(rcon 'worlds info wutest')\""
check "/dim with a selector nobody matches says so" bash -c "grep -qi 'nobody online matches' <<<\"$(rcon 'dim nether @a')\""
check "/w to an unknown world says so" bash -c "grep -qi 'no loaded world' <<<\"$(rcon 'w nowhere Alex')\""

log "Speedrun: /speedrunreset must regenerate the run's nether and end too …"
sr_nether_before="$(count_in_log "World 'speedrun_nether' has been created")"
sr_end_before="$(count_in_log "World 'speedrun_the_end' has been created")"
rcon "speedrunreset" >/dev/null
check "speedrun_nether was made again" wait_until_count "World 'speedrun_nether' has been created" "$sr_nether_before" 90
check "speedrun_the_end was made again" wait_until_count "World 'speedrun_the_end' has been created" "$sr_end_before" 90

stop
log "Boot 1 stopped cleanly."

# ─────────────────────────────────────────────────────────────── boot 2
boot second
check "the worlds made here are loaded again after a restart" wait_for 'World Utils is up: 3 world\(s\) made here, 3 loaded again' 5
check "wutest is there with the seed it had" test "$(seed_of wutest)" = "$new_seed"
check "the seed history survived the restart" bash -c "grep -q '424242' <<<\"$(rcon 'worlds seeds wutest')\""
check "solo_nether stayed deleted" bash -c "grep -q 'There is no loaded world' <<<\"$(rcon 'worlds info solo_nether')\""
stop
# Read after the stop, so what onDisable logs is included — a database write on the world's thread at
# shutdown is exactly the kind of line this is for.
check "no ERROR line from our plugins in boot 2, shutdown included" \
  bash -c "! sed -E 's/\x1b\[[0-9;]*m//g' '$LOG' | grep -E 'ERROR\]' | grep -qE 'RainsCore|RainsWorldUtils|RainsSpeedrun'"

if [ "$FAILURES" -gt 0 ]; then
  fail "$FAILURES check(s) failed"
fi
log "ALL LIVE WORLD-UTILS CHECKS PASSED (player-dependent behaviour is covered by unit tests only)"
