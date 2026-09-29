#!/usr/bin/env bash
# Dump everything the local fork needs from mainnet (read-only): the live ORE, entropy and
# ORE-mint programs, and every ORE account deploy / checkpoint / reset touches.
# Output: $HD_DEVSTACK_HOME/fixtures (outside the repo). RPC: $HD_MAINNET_RPC.
source "$(dirname "$0")/lib.sh"

ORE=oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv
ENTROPY=3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X
MINT_PROGRAM=mintzxW6Kckmeyh1h6Zfdj9QcYgCzhPSGiC8ChZ6fCx
BOARD=BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi
CONFIG=9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy
TREASURY=45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG
VAR=BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E
MINT=oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp
FEE_COLLECTOR=DyB4Kv6V613gp2LWQTq1dwDYHGKuUEoDHnCouGUtxFiX
TOKEN=TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
ATA=ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL

mkdir -p "$FIXTURES"
cd "$FIXTURES"
log "dumping mainnet ORE state into $FIXTURES (RPC host: $(echo "$HD_MAINNET_RPC" | sed -E 's#^[a-z]+://([^/?]+).*#\1#'))"

solana -u "$HD_MAINNET_RPC" program dump "$ORE" ore.so >/dev/null
solana -u "$HD_MAINNET_RPC" program dump "$ENTROPY" entropy.so >/dev/null
solana -u "$HD_MAINNET_RPC" program dump "$MINT_PROGRAM" ore_mint.so >/dev/null

MINT_AUTH=$(solana find-program-derived-address "$MINT_PROGRAM" string:authority | head -1)
TREASURY_ATA=$(solana find-program-derived-address "$ATA" "pubkey:$TREASURY" "pubkey:$TOKEN" "pubkey:$MINT" | head -1)
for a in "$BOARD" "$CONFIG" "$TREASURY" "$VAR" "$MINT" "$MINT_AUTH" "$TREASURY_ATA" "$FEE_COLLECTOR"; do
  solana -u "$HD_MAINNET_RPC" account "$a" --output json-compact >"$a.json"
done

RID=$(python3 - "$BOARD.json" <<'PY'
import json, base64, struct, sys
d = base64.b64decode(json.load(open(sys.argv[1]))['account']['data'][0])
print(struct.unpack_from('<Q', d, 8)[0])
PY
)
ROUND=$(solana find-program-derived-address "$ORE" string:round "u64le:$RID" | head -1)
solana -u "$HD_MAINNET_RPC" account "$ROUND" --output json-compact >"$ROUND.json"

{
  echo "fetched_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "board_round_id=$RID"
  echo "round=$ROUND"
  echo "mint_authority=$MINT_AUTH"
  echo "treasury_ata=$TREASURY_ATA"
  for so in ore.so entropy.so ore_mint.so; do echo "sha256_$so=$(shasum -a 256 "$so" | cut -d' ' -f1)"; done
} >fetched.env
log "fixtures: ORE round $RID, $(ls -1 | wc -l | tr -d ' ') files ($(grep sha256_ore.so fetched.env))"
