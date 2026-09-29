#!/usr/bin/env bash
# Let a USB-connected Android phone reach the Mac's stack at 127.0.0.1 (adb reverse).
#   scripts/devstack/phone.sh [--serial <adb serial>] [--fund <wallet pubkey> [sol]] [--remove]
#
# On the phone afterwards:
#   RPC        http://127.0.0.1:8899   WebSocket ws://127.0.0.1:8900
#   crank      ws://127.0.0.1:8787/ws
#   indexer    http://127.0.0.1:8788   registrar http://127.0.0.1:8790 (if started)
source "$(dirname "$0")/lib.sh"

need adb "brew install android-platform-tools"
SERIAL=() FUND="" SOL=10 REMOVE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL=(-s "$2"); shift 2 ;;
    --fund) FUND="$2"; shift 2; if [[ $# -gt 0 && "$1" != --* ]]; then SOL="$1"; shift; fi ;;
    --remove) REMOVE=1; shift ;;
    *) die "unknown option $1" ;;
  esac
done
ADB=(adb ${SERIAL[@]+"${SERIAL[@]}"})
"${ADB[@]}" get-state >/dev/null 2>&1 || die "no device: plug in the phone, enable USB debugging and accept the RSA prompt (adb devices)"

PORTS=("$HD_RPC_PORT" "$HD_WS_PORT" "$HD_CRANK_PORT" "$HD_INDEXER_PORT" "$HD_REGISTRAR_PORT")
if [[ $REMOVE == 1 ]]; then
  for p in "${PORTS[@]}"; do "${ADB[@]}" reverse --remove "tcp:$p" 2>/dev/null || true; done
  log "removed adb reverse rules"
  exit 0
fi
for p in "${PORTS[@]}"; do "${ADB[@]}" reverse "tcp:$p" "tcp:$p" >/dev/null; done
log "adb reverse active on $("${ADB[@]}" get-serialno):"
"${ADB[@]}" reverse --list
if [[ -n "$FUND" ]]; then
  "$DEVSTACK_DIR/fund.sh" "$FUND" "$SOL"
fi
cat <<EOF

The phone now reaches the Mac's stack at 127.0.0.1:
  RPC      http://127.0.0.1:$HD_RPC_PORT   (WebSocket ws://127.0.0.1:$HD_WS_PORT)
  crank    ws://127.0.0.1:$HD_CRANK_PORT/ws
  indexer  http://127.0.0.1:$HD_INDEXER_PORT
Rules last until the cable is unplugged or adb restarts: re-run this script after reconnecting.
EOF
