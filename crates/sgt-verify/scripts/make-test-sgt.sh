#!/usr/bin/env bash
# Issue a test Seeker Genesis Token on a local validator or devnet, with the
# same extension set as a real SGT, using only the Agave CLI + spl-token CLI.
# No Seeker needed.
#
#   scripts/make-test-sgt.sh [RPC_URL] [HOLDER_PUBKEY]
#
# RPC_URL defaults to http://127.0.0.1:8899. HOLDER_PUBKEY defaults to a
# holder keypair created in the state directory (so you can sign with it).
#
# Keys live in $SGT_TEST_DIR (default: <crate>/.test-sgt, gitignored):
#   authority.keypair.json  your test SGT authority (mint/freeze/close/
#                           permanent-delegate/pointer/group-update authority)
#   group.keypair.json      your test group mint (the GT22s89 stand-in)
#   holder.keypair.json     default holder
# They are reused across runs, so every SGT you issue joins the same group.
#
# Build your program against the printed anchors:
#   SGT_VERIFY_TEST_GROUP=<group> SGT_VERIFY_TEST_AUTHORITY=<authority> \
#     cargo build-sbf --features <your devnet feature that enables sgt-verify/test-group>
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
CRATE="$(cd "$(dirname "$0")/.." && pwd)"
URL="${1:-http://127.0.0.1:8899}"
DIR="${SGT_TEST_DIR:-$CRATE/.test-sgt}"
mkdir -p "$DIR"
CFG="$DIR/config.yml"
T22=(--program-2022)

key() { [ -f "$DIR/$1.keypair.json" ] || solana-keygen new --no-bip39-passphrase --silent --force -o "$DIR/$1.keypair.json" >/dev/null; solana-keygen pubkey "$DIR/$1.keypair.json"; }

AUTH=$(key authority)
GROUP=$(key group)
if [ -n "${2:-}" ]; then HOLDER="$2"; else HOLDER=$(key holder); fi
MEMBER_KP="$DIR/member-$(date +%s)-$RANDOM.keypair.json"
solana-keygen new --no-bip39-passphrase --silent -o "$MEMBER_KP" >/dev/null
MEMBER=$(solana-keygen pubkey "$MEMBER_KP")

solana config set -C "$CFG" --url "$URL" --keypair "$DIR/authority.keypair.json" >/dev/null
sol() { solana -C "$CFG" "$@"; }
spl() { spl-token -C "$CFG" "${T22[@]}" "$@"; }

if [ "$(sol balance --lamports "$AUTH" | cut -d' ' -f1)" -lt 100000000 ]; then
  sol airdrop 2 "$AUTH" >/dev/null || { echo "fund $AUTH with ~0.1 SOL and re-run" >&2; exit 1; }
fi

# 1. The group mint (once): GroupPointer -> self, TokenGroup, close + freeze.
if ! sol account "$GROUP" >/dev/null 2>&1; then
  echo "creating test group $GROUP"
  spl create-token --decimals 0 --enable-group --enable-close --enable-freeze \
    "$DIR/group.keypair.json" >/dev/null
  spl initialize-group "$GROUP" 1000000 --update-authority "$AUTH" >/dev/null
fi

# 2. The member mint: MetadataPointer -> group, PermanentDelegate,
#    MintCloseAuthority, GroupMemberPointer -> self; decimals 0; all
#    authorities = AUTH. Then join the group.
echo "issuing test SGT $MEMBER to $HOLDER"
spl create-token --decimals 0 --enable-member --enable-close --enable-freeze \
  --enable-permanent-delegate --metadata-address "$GROUP" "$MEMBER_KP" >/dev/null
spl initialize-member "$MEMBER" "$GROUP" \
  --group-update-authority "$DIR/authority.keypair.json" >/dev/null

# 3. Holder's ATA, exactly one token, frozen (as every real SGT holding is).
spl create-account "$MEMBER" --owner "$HOLDER" --fee-payer "$DIR/authority.keypair.json" >/dev/null
ATA=$(spl address --token "$MEMBER" --owner "$HOLDER" --verbose | awk '/Associated token address/ {print $NF}')
spl mint "$MEMBER" 1 "$ATA" >/dev/null
spl freeze "$ATA" >/dev/null

# 4. Close the loop: dump both accounts and run the verifier's own code path.
sol account "$MEMBER" --output json > "$DIR/last-mint.json"
sol account "$ATA" --output json > "$DIR/last-token-account.json"
echo
echo "SGT_VERIFY_TEST_GROUP=$GROUP"
echo "SGT_VERIFY_TEST_AUTHORITY=$AUTH"
echo "mint=$MEMBER token_account=$ATA holder=$HOLDER"
echo
SGT_VERIFY_TEST_GROUP="$GROUP" SGT_VERIFY_TEST_AUTHORITY="$AUTH" \
  cargo run -q --manifest-path "$CRATE/Cargo.toml" --example verify_account_dump \
  --features test-group -- "$DIR/last-mint.json" "$DIR/last-token-account.json" "$HOLDER"
