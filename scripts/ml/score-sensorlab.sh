#!/usr/bin/env bash
# Pull the sensor lab's export off a phone and score the SHIPPED pickup model on it.
#
#   scripts/ml/score-sensorlab.sh [--serial <adb serial>] [--package xyz.headsdown] [EXPORT.csv]
#
# On the phone first (a debug build, which has the sensor lab under Setup): record the sessions
# (ml/foreman/classifier/MODEL_CARD.md, "Recording protocol"), then tap "Export all as CSV" and
# dismiss the share sheet. The export stays in the app's private storage; this script reads it
# with `run-as` (debug builds only), keeps it under ml/foreman/classifier/data/real/ (git-ignored:
# the recordings never leave this machine), and prints what the label rules kept and what the
# model in the app does on those windows. Nothing is trained and no model file is touched.
# With EXPORT.csv, the phone is not asked: that file is scored.
set -euo pipefail

SERIAL="" PKG="xyz.headsdown" EXPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --package) PKG="$2"; shift 2 ;;
    -h | --help) sed -n '2,13p' "$0"; exit 0 ;;
    *) EXPORT="$1"; shift ;;
  esac
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FOREMAN="$ROOT/ml/foreman"
PY="python3"
if [[ -x "$FOREMAN/.venv/bin/python" ]]; then PY="$FOREMAN/.venv/bin/python"; fi
"$PY" -c 'import numpy' 2>/dev/null || { echo "python3 with numpy is needed: python3 -m venv ml/foreman/.venv && ml/foreman/.venv/bin/pip install -r ml/foreman/requirements.txt" >&2; exit 1; }

if [[ -z "$EXPORT" ]]; then
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$d" && -x "$d/platform-tools/adb" ]]; then PATH="$d/platform-tools:$PATH"; fi
  done
  command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
  [[ -z "$SERIAL" ]] || export ANDROID_SERIAL="$SERIAL"
  NAME="$(adb shell run-as "$PKG" ls files/sensorlab/export 2>/dev/null | tr -d '\r' | grep -E '^sensorlab-export-[0-9A-Za-z_-]+\.csv$' | sort | tail -n 1 || true)"
  [[ -n "$NAME" ]] || { echo "no export on the phone: in the sensor lab, tap \"Export all as CSV\" first (and the build must be a debug build of $PKG)" >&2; exit 1; }
  mkdir -p "$FOREMAN/classifier/data/real"
  EXPORT="$FOREMAN/classifier/data/real/$NAME"
  adb exec-out run-as "$PKG" cat "files/sensorlab/export/$NAME" >"$EXPORT"
  [[ -s "$EXPORT" ]] || { echo "the export came back empty" >&2; exit 1; }
  echo "pulled $NAME: $(wc -c <"$EXPORT" | tr -d ' ') bytes, $(grep -c '^# session=' "$EXPORT" || true) sessions"
fi
EXPORT="$(cd "$(dirname "$EXPORT")" && pwd)/$(basename "$EXPORT")"

cd "$FOREMAN"
echo "== what the label rules kept and dropped"
"$PY" -m classifier.sensorlab inspect "$EXPORT"
echo
echo "== the shipped model on these windows"
# The full result (every miss and every false break) is kept beside the recordings, git-ignored.
mkdir -p "$FOREMAN/classifier/data/real"
"$PY" -m classifier.score_real "$EXPORT" --json "$FOREMAN/classifier/data/real/$(basename "${EXPORT%.csv}").score.json"
