#!/usr/bin/env bash
# Dump the live mainnet ORE program, the entropy program and every ORE account
# `dig` touches, so the heads_down LiteSVM suite runs against real bytecode and
# real state (a local mainnet fork). Adapted from spikes/ore-executor.
#
# Output (all gitignored): ore.so, entropy.so, <address>.json for Board, Config,
# Treasury, Var and the current Round, plus round_id.txt / round_address.txt /
# entropy_program_id.txt and fetched_at.txt.
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
RPC="${RPC_URL:-https://api.mainnet-beta.solana.com}"
cd "$(dirname "$0")"

ORE=oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv
BOARD=BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi
CONFIG=9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy
TREASURY=45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG
VAR=BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E

solana -u "$RPC" program dump "$ORE" ore.so
for a in $BOARD $CONFIG $TREASURY $VAR; do
  solana -u "$RPC" account "$a" --output json-compact > "$a.json"
done
# Entropy program = owner of the Var account.
ENTROPY=$(python3 -c "import json;print(json.load(open('$VAR.json'))['account']['owner'])")
echo "$ENTROPY" > entropy_program_id.txt
solana -u "$RPC" program dump "$ENTROPY" entropy.so
# Current round PDA = [b"round", board.round_id (u64 LE)] under ORE.
python3 - "$BOARD" <<'PY' > round_id.txt
import json,base64,struct,sys
d=base64.b64decode(json.load(open(sys.argv[1]+'.json'))['account']['data'][0])
print(struct.unpack_from('<Q',d,8)[0])
PY
RID=$(cat round_id.txt)
ROUND=$(solana find-program-derived-address "$ORE" string:round "u64le:$RID" | head -1)
echo "$ROUND" > round_address.txt
solana -u "$RPC" account "$ROUND" --output json-compact > "$ROUND.json"
date -u +"%Y-%m-%dT%H:%M:%SZ" > fetched_at.txt
echo "board.round_id=$RID round=$ROUND entropy=$ENTROPY"
