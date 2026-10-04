#!/usr/bin/env bash
# Create (only if missing) and list the Heads Down operator keys. Idempotent; never overwrites.
#
#   scripts/mainnet/keys.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--max-len N] [--no-funding]
#                           [--public-rpc]
#
# Keys, under $HD_MAINNET_KEYS (default ~/.config/heads-down/mainnet; dir 700, files 600):
#   deployer.json             fee payer of the deploy and the program's upgrade authority until it
#                             moves to a Squads vault (already exists for mainnet: 9DSVM862…)
#   crank-payer.json          hd-crank fee payer (Railway secret HD_CRANK_KEYPAIR_JSON)
#   governance.json           Config.governance: signs propose_config (pause at once; the rest after 72 h)
#   registrar.json            registrar Ed25519 voucher key = Config.registrar (`hd-registrar keygen`)
#   registrar-session-secret  registrar HMAC key for SIWS sessions (HD_SESSION_SECRET)
# Prints ONLY public keys, then the exact funding per key read from the cluster's rent
# (read-only; mainnet uses helius.env if present, otherwise the public RPC; --public-rpc uses
# the public RPC although helius.env is there).
#
# For mainnet with the default key directory it stops, before any amount is printed, if
# deployer.json, crank-payer.json or governance.json does not derive the address the funding
# table in docs/DEPLOY.md names (lib.sh: HD_EXPECTED_DEPLOYER, HD_EXPECTED_CRANK_PAYER,
# HD_EXPECTED_GOVERNANCE): SOL sent to that address could not be spent with the file at hand.
HD_SCRIPT=keys
source "$(dirname "$0")/lib.sh"

FUNDING=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --max-len) HD_MAX_LEN="$2"; shift 2 ;;
    --no-funding) FUNDING=0; shift ;;
    --public-rpc) PUBLIC_RPC=1; shift ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
default_keys
need solana-keygen "install the Agave CLI 4.1 (~/.local/share/solana/install/active_release/bin)"
need openssl "openssl ships with macOS"

mkdir -p "$KEYS"
chmod 700 "$KEYS"

# new_solana_key PATH: a fresh ed25519 keypair; --silent never shows a seed phrase.
new_solana_key() {
  (umask 077 && solana-keygen new --no-bip39-passphrase --silent --outfile "$1" >/dev/null)
  chmod 600 "$1"
  log "created $(basename "$1")"
}

for k in "$K_DEPLOYER" "$K_CRANK" "$K_GOVERNANCE"; do
  if [[ -e "$k" ]]; then chmod 600 "$k"; else new_solana_key "$k"; fi
done

if [[ ! -e "$K_REGISTRAR" ]]; then
  REG_BIN="$REPO_ROOT/registrar/target/release/hd-registrar"
  if [[ ! -x "$REG_BIN" ]]; then
    log "building hd-registrar for its keygen (registrar/, release)"
    mkdir -p "$HD_STATE"
    (cd "$REPO_ROOT/registrar" && cargo build --release --bin hd-registrar) >"$HD_STATE/build-registrar.log" 2>&1 \
      || die "registrar build failed: $HD_STATE/build-registrar.log"
  fi
  # hd-registrar keygen: Solana CLI JSON, created 0600 with O_EXCL; prints only the public key.
  "$REG_BIN" keygen "$K_REGISTRAR" >/dev/null
  log "created $(basename "$K_REGISTRAR") (hd-registrar keygen)"
fi
chmod 600 "$K_REGISTRAR"

if [[ ! -e "$K_SESSION" ]]; then
  (umask 077 && openssl rand -hex 32 >"$K_SESSION")
  log "created $(basename "$K_SESSION") (32 random bytes, hex)"
fi
chmod 600 "$K_SESSION"

for k in "$K_DEPLOYER" "$K_CRANK" "$K_GOVERNANCE" "$K_REGISTRAR" "$K_SESSION"; do
  key_ok "$k" || die "$k must be a regular file you own with mode 600"
done
dir_ok "$KEYS" || die "$KEYS must be a directory you own with mode 700"

DEPLOYER="$(pubkey_of "$K_DEPLOYER")"
CRANK="$(pubkey_of "$K_CRANK")"
GOVERNANCE="$(pubkey_of "$K_GOVERNANCE")"
REGISTRAR="$(pubkey_of "$K_REGISTRAR")"
PROGRAM="$(pubkey_of "$HD_PROGRAM_KEYPAIR")"
[[ "$PROGRAM" == "$HD_PROGRAM_ID" ]] || warn "$HD_PROGRAM_KEYPAIR is $PROGRAM, not $HD_PROGRAM_ID"
# The funded addresses (mainnet, default key directory): collected here, reported after the table.
WRONG=()
if pins_apply; then
  for k in "$K_DEPLOYER" "$K_CRANK" "$K_GOVERNANCE"; do
    wrong="$(pin_mismatch "$k")"
    if [[ -n "$wrong" ]]; then WRONG+=("$wrong"); fi
  done
fi

echo
bold "Heads Down $CLUSTER keys in $KEYS (dir 700, files 600; public keys only)"
printf '  %-26s %-44s %s\n' "role" "public key" "file"
printf '  %-26s %-44s %s\n' "program id" "$PROGRAM" "$HD_PROGRAM_KEYPAIR"
printf '  %-26s %-44s %s\n' "deployer / upgrade auth." "$DEPLOYER" "deployer.json"
printf '  %-26s %-44s %s\n' "crank fee payer" "$CRANK" "crank-payer.json"
printf '  %-26s %-44s %s\n' "Config.governance" "$GOVERNANCE" "governance.json"
printf '  %-26s %-44s %s\n' "Config.registrar" "$REGISTRAR" "registrar.json"
printf '  %-26s %-44s %s\n' "registrar session secret" "(secret, never printed)" "registrar-session-secret"
cat <<EOF

  deployer      pays for the deploy (ProgramData rent for --max-len $HD_MAX_LEN), initialize_config and the
                Executor PDA float; it is the upgrade authority until that moves to a Squads vault with a
                72 h time lock (docs/DEPLOY.md "Squads").
  crank payer   hot key on Railway (HD_CRANK_KEYPAIR_JSON). Pays dig fees and its lookup-table rent;
                each real dig reimburses crank_fee ($HD_CRANK_FEE lamports). Keep its balance small.
  governance    signs propose_config. paused=1 pauses dig immediately; everything else waits 72 h and
                needs apply_config. The program (v1.3) can hand Config.governance to a successor
                (propose_governance, then accept_governance by the successor after the same 72 h), but
                no script here sends those instructions: scripts/mainnet/governance.sh has no command
                for them. Until one exists, treat this key as the one that stays.
  registrar     signs attestation vouchers off-chain (never pays fees). Rotate via propose_config.
EOF

if [[ ${#WRONG[@]} -gt 0 ]]; then
  echo >&2
  for wrong in "${WRONG[@]}"; do printf '\033[31;1m[keys] STOP: %s.\033[0m\n' "$wrong" >&2; done
  die "${#WRONG[@]} key file(s) do not derive the funded address: send nothing until this is cleared up"
fi
if pins_apply; then
  log "deployer.json, crank-payer.json and governance.json derive the funded addresses"
elif [[ "$CLUSTER" == mainnet ]]; then
  log "funded addresses not checked: $KEYS is not the default mainnet key directory"
fi

if [[ $FUNDING == 1 ]]; then
  echo
  resolve_rpc public-ok
  SO_LEN=111600
  if [[ -f "$HD_SO" ]]; then SO_LEN="$(wc -c <"$HD_SO" | tr -d ' ')"; fi
  bold "Funding for the first $CLUSTER deploy (rent from $RPC_HOST; read-only)"
  if ! tool funding --deployer "$DEPLOYER" --so-len "$SO_LEN" --max-len "$HD_MAX_LEN" --crank-fee "$HD_CRANK_FEE" \
    --crank-reserve-digs "$HD_CRANK_RESERVE_DIGS" --fee-budget "$(deploy_fee_budget)" \
    --key "crank-payer=$CRANK:$HD_CRANK_PAYER_LAMPORTS" --key "governance=$GOVERNANCE:$HD_GOVERNANCE_LAMPORTS" \
    --key "registrar=$REGISTRAR:0"; then
    warn "could not read rent from the cluster; run scripts/mainnet/preflight.sh for exact amounts"
  fi
  cat <<EOF

  Later, not now: an upgrade needs its buffer's rent (the figure above for a $SO_LEN-byte build) and the
  same fee budget in the deployer while it runs; the rent returns when the upgrade lands. An upgrade
  that outgrows --max-len also locks the rent of at least 10,240 more bytes (preflight shows it).
  Executor PDA top-ups: re-run init-config.sh (it only tops up).
EOF
fi
