#!/usr/bin/env bash
# Local dev wallet funding: mint test SKR on the local fork (never mainnet). The fork carries a
# stand-in for the SKR mint at SKR's own address, whose mint authority is a local key (up.sh).
#   scripts/devstack/fund-skr.sh <pubkey> [skr=100]
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "usage: fund-skr.sh <pubkey> [skr]"
[[ -x "$TOOL_BIN" ]] || die "build the stack first (up.sh)"
[[ -f "$K_SKR" ]] || die "no SKR mint authority key: start the stack with up.sh"
"$TOOL_BIN" --rpc "$RPC_URL" fund-skr "$1" "${2:-100}" --authority "$K_SKR"
