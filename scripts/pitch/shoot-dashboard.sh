#!/usr/bin/env bash
# Takes the deck's screenshots of the live dashboard with headless Chrome, unmodified:
#   site/deck/img/dashboard-overview.png     the overview page's tiles
#   site/deck/img/dashboard-milestones.png   the ORE milestones page
#
# Usage: scripts/pitch/shoot-dashboard.sh [dashboard URL]
#
# It reads two public pages and nothing else. The pictures show what the dashboard showed at the
# moment they were taken, so after a new capture, slides 12 and 15 of site/deck/index.html need
# their captions (the time, and the figures they quote) brought to what the new pictures show;
# then run scripts/pitch/render-deck.sh.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
base="${1:-https://dashboard-production-b80c.up.railway.app}"
base="${base%/}"
out="$root/site/deck/img"

find_chrome() {
  local c
  if [[ -n "${CHROME:-}" ]]; then printf '%s\n' "$CHROME"; return 0; fi
  for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "/Applications/Chromium.app/Contents/MacOS/Chromium" \
           "$HOME"/Library/Caches/ms-playwright/chromium-*/chrome-mac*/*.app/Contents/MacOS/*; do
    [[ -x "$c" ]] && { printf '%s\n' "$c"; return 0; }
  done
  for c in google-chrome chromium chromium-browser; do
    command -v "$c" >/dev/null 2>&1 && { command -v "$c"; return 0; }
  done
  return 1
}

chrome="$(find_chrome)" || { echo "shoot-dashboard: no Chrome or Chromium found (set CHROME=/path/to/chrome)" >&2; exit 1; }
work="$(mktemp -d "${TMPDIR:-/tmp}/hd-dashboard.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

# shoot <path> <file> <css width> <css height>: a 2x capture of the top of the page. The dashboard
# keeps polling its API, and a headless Chrome may then never exit by itself, so it gets 60 s to
# write the file and is stopped afterwards.
shoot() {
  local url="$base$1" file="$2" w="$3" h="$4" pid waited=0
  rm -f "$work/shot.png"
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$work/profile" --force-device-scale-factor=2 --window-size="$w,$h" \
    --virtual-time-budget=15000 --screenshot="$work/shot.png" "$url" >/dev/null 2>&1 &
  pid=$!
  while [[ ! -s "$work/shot.png" ]] && kill -0 "$pid" 2>/dev/null && (( waited < 60 )); do
    sleep 1; waited=$((waited + 1))
  done
  sleep 2
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  [[ -s "$work/shot.png" ]] || { echo "shoot-dashboard: no picture of $url after ${waited}s" >&2; exit 1; }
  mv "$work/shot.png" "$out/$file"
  echo "shoot-dashboard: $url -> site/deck/img/$file ($(date -u +%Y-%m-%dT%H:%MZ))"
}

shoot "/" dashboard-overview.png 560 900
shoot "/milestones/" dashboard-milestones.png 560 900
