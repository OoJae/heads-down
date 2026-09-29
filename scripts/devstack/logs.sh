#!/usr/bin/env bash
# Follow logs:  scripts/devstack/logs.sh [validator|driver|crank|indexer|registrar|deploy|init]  (default: all)
source "$(dirname "$0")/lib.sh"

if [[ $# -eq 0 ]]; then
  exec tail -n 20 -F "$LOGS"/driver.log "$LOGS"/crank.log "$LOGS"/indexer.log
fi
exec tail -n 50 -F "$LOGS/$1.log"
