#!/usr/bin/env bash
# What is running, on which port, and the fork's ORE / heads_down state.
source "$(dirname "$0")/lib.sh"

for n in validator driver crank indexer registrar; do
  if is_running "$n"; then state="running (pid $(cat "$(pidfile "$n")"))"; else state="stopped"; fi
  printf '  %-10s %s\n' "$n" "$state"
done
printf '  %-10s %s\n' crank "$(curl -s -m 2 "http://127.0.0.1:$HD_CRANK_PORT/healthz" || echo unreachable)"
printf '  %-10s %s\n' indexer "$(curl -s -m 2 "http://127.0.0.1:$HD_INDEXER_PORT/v1/health" | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)["data"]; print("txs %s lastSlot %s problems %s" % (d["txs"], d["lastSlot"], d["problems"]))
except Exception: print("unreachable")')"
echo
[[ -x "$TOOL_BIN" ]] && "$TOOL_BIN" --rpc "$RPC_URL" status || true
