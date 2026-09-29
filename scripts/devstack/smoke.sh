#!/usr/bin/env bash
# The phone-less end-to-end smoke ("the trustless beat"), one command:
#   scripts/devstack/smoke.sh [--keep] [up.sh options...]
#
# Brings the stack up if it is not running (and takes it down again afterwards unless --keep),
# then: clock-in in one tx -> simulated phone heartbeats to the crank -> the crank digs on the
# local fork -> the indexer records RigDug -> the phone is lifted -> no dig next round, and a
# replayed heartbeat / reused lease is refused on-chain. Exit code 0 = passed.
source "$(dirname "$0")/lib.sh"

KEEP=0 UP_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    *) UP_ARGS+=("$1"); shift ;;
  esac
done

STARTED=0
if ! is_running validator || ! is_running crank || ! is_running indexer || ! is_running driver; then
  "$DEVSTACK_DIR/up.sh" ${UP_ARGS[@]+"${UP_ARGS[@]}"}
  STARTED=1
fi
cleanup() {
  if [[ $STARTED == 1 && $KEEP == 0 ]]; then "$DEVSTACK_DIR/down.sh"; fi
}
trap cleanup EXIT

log "running the smoke (log: $LOGS/smoke.log)"
set +e
"$TOOL_BIN" --rpc "$RPC_URL" smoke --crank-ws "ws://127.0.0.1:$HD_CRANK_PORT/ws" \
  --crank-http "http://127.0.0.1:$HD_CRANK_PORT" --indexer "http://127.0.0.1:$HD_INDEXER_PORT" 2>&1 | tee "$LOGS/smoke.log"
rc=${PIPESTATUS[0]}
set -e
if [[ $rc -ne 0 ]]; then
  log "SMOKE FAILED (exit $rc). Recent driver / crank logs:"
  tail -n 15 "$LOGS/driver.log" "$LOGS/crank.log" 2>/dev/null || true
fi
exit "$rc"
