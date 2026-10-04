#!/usr/bin/env bash
# Every page of RainsSpeedrun and Manhunt, photographed with the real Minecraft client (offline mode,
# a virtual display, Mesa's software OpenGL) into docs/screenshots/<page>.png. The game's own files are
# fetched into the e2e cache at run time and never written anywhere else.
#
# Needs Xvfb, Mesa and ImageMagick's `import` (Ubuntu: xvfb libgl1-mesa-dri imagemagick libxtst6).
# Usage: scripts/e2e-screenshots.sh [--offline]   (after scripts/e2e.sh has built the plugin)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
OFFLINE=""
[ "${1:-}" = "--offline" ] && OFFLINE="-o"
for tool in Xvfb import; do
  command -v "$tool" >/dev/null || { echo "[screenshots] $tool is missing" >&2; exit 1; }
done
echo "[screenshots] every page, with the real client …"
mvn --batch-mode --no-transfer-progress $OFFLINE test -pl speedrun-e2e -Pscreenshots -Dtest=ScreenshotsTest
ls -1 docs/screenshots
