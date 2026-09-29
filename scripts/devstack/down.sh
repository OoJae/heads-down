#!/usr/bin/env bash
# Stop the local stack. Keys (~/.config/heads-down/devstack) and the mainnet dump are kept.
#   scripts/devstack/down.sh [--wipe]   --wipe also deletes the ledger, genesis, crank state and indexer DB
source "$(dirname "$0")/lib.sh"

WIPE=0
[[ "${1:-}" == "--wipe" ]] && WIPE=1
for n in registrar indexer crank driver validator; do
  if is_running "$n"; then
    stop_bg "$n"
    log "stopped $n"
  else
    rm -f "$(pidfile "$n")"
  fi
done
if [[ $WIPE == 1 ]]; then
  rm -rf "$LEDGER" "$GENESIS" "$RUN"
  log "wiped ledger, genesis and run state under $HD_DEVSTACK_HOME (keys and fixtures kept)"
fi
