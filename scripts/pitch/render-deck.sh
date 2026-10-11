#!/usr/bin/env bash
# Prints the deck (site/deck/index.html) to site/deck/heads-down-deck.pdf with headless Chrome:
# one slide a page, 1920 x 1080, exactly as the page's print rules lay it out.
#
# Usage: scripts/pitch/render-deck.sh [--shots DIR]
#
# With --shots it also writes one PNG per slide into DIR (slide-01.png ...), for looking at the
# deck without opening it. It reads local files only.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
deck="$root/site/deck/index.html"
pdf="$root/site/deck/heads-down-deck.pdf"
shots=""
if [[ "${1:-}" == "--shots" ]]; then shots="${2:?--shots needs a directory}"; fi

find_chrome() {
  local c
  for c in "${CHROME:-}" \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "/Applications/Chromium.app/Contents/MacOS/Chromium" \
    "$(command -v google-chrome || true)" "$(command -v chromium || true)" "$(command -v chromium-browser || true)"; do
    if [[ -n "$c" && -x "$c" ]]; then printf '%s\n' "$c"; return 0; fi
  done
  return 1
}
chrome="$(find_chrome)" || { echo "render-deck: no Chrome or Chromium found (set CHROME=/path/to/it)" >&2; exit 1; }
profile="$(mktemp -d)"
trap 'rm -rf "$profile"' EXIT

url="file://$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$deck")"

# Headless Chrome writes its file and then, on some machines, does not exit. So it runs beside
# this script, which waits for the file to stop growing and then stops it.
chrome_to_file() { # output file, then Chrome's own arguments
  local out="$1" pid size last=-1 waited=0
  shift
  rm -f "$out"
  "$chrome" --headless=new --disable-gpu --no-first-run --user-data-dir="$profile" "$@" >/dev/null 2>&1 &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    sleep 1; waited=$((waited + 1))
    if [[ -s "$out" ]]; then
      size="$(wc -c <"$out" | tr -d ' ')"
      if [[ "$size" == "$last" ]]; then break; fi
      last="$size"
    fi
    if [[ $waited -ge 90 ]]; then break; fi
  done
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  [[ -s "$out" ]] || { echo "render-deck: Chrome wrote nothing to $out" >&2; return 1; }
}

chrome_to_file "$pdf" --no-pdf-header-footer --virtual-time-budget=4000 --print-to-pdf="$pdf" "$url"
echo "render-deck: $pdf ($(wc -c <"$pdf" | tr -d ' ') bytes)"

if [[ -n "$shots" ]]; then
  mkdir -p "$shots"
  count="$(grep -c '<section class="slide' "$deck")"
  for n in $(seq 1 "$count"); do
    png="$shots/slide-$(printf '%02d' "$n").png"
    chrome_to_file "$png" --hide-scrollbars --window-size=1920,1080 --virtual-time-budget=3000 --screenshot="$png" "$url#$n"
  done
  echo "render-deck: $count slide pictures in $shots"
fi
