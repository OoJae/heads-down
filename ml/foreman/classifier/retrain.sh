#!/usr/bin/env bash
# Retrain the pickup classifier with real sensor-lab sessions recorded on the Redmi 14C.
#
#   ml/foreman/classifier/retrain.sh ~/Downloads/sensorlab-export-20261005-0700.csv [conditions.csv]
#
# 1. Prints what the label rules kept, relabeled and dropped (MODEL_CARD.md "Recording protocol").
# 2. Retrains all three models on synthetic + real windows (real ones weighted 3x), picks the
#    threshold on the real validation sessions when they hold at least 100 pickups, re-runs the
#    selection rule, rewrites model/, RESULTS.md and the android/ml assets and test vectors.
# 3. Runs the Python tests. Then run the Kotlin ones:  cd android && ./gradlew :ml:test
#
# The export never leaves this machine: classifier/data/ is git-ignored except the synthetic sample.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 EXPORT.csv [CONDITIONS.csv]" >&2
  exit 2
fi
EXPORT="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
CONDITIONS=""
if [ "$#" -ge 2 ]; then
  CONDITIONS="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
fi

cd "$(dirname "$0")/.."  # ml/foreman
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
  .venv/bin/pip install -r requirements.txt
fi
PY=.venv/bin/python

echo "== label rules on $(basename "$EXPORT")"
if [ -n "$CONDITIONS" ]; then
  $PY -m classifier.sensorlab inspect "$EXPORT" --conditions "$CONDITIONS"
  $PY -m classifier.train --real "$EXPORT" --conditions "$CONDITIONS"
else
  $PY -m classifier.sensorlab inspect "$EXPORT"
  $PY -m classifier.train --real "$EXPORT"
fi

echo "== python tests"
$PY -m pytest -q -p no:cacheprovider classifier/tests test_android_sync.py

echo "== done. Review classifier/RESULTS.md (the 'real sensor lab' rows), then:"
echo "   cd android && ./gradlew :ml:test"
