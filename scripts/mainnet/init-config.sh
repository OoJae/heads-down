#!/usr/bin/env bash
# initialize_config (tag 0, INTERFACE.md v1.1 / vectors/instructions.json) signed by the upgrade
# authority, then fund the Executor PDA float. Idempotent: an existing Config is left alone (and
# compared), and the float is only topped up to its target, so this is also the top-up command.
#
#   scripts/mainnet/init-config.sh [--cluster mainnet|localnet] [--keys-dir DIR]
#       [--governance PUBKEY] [--registrar PUBKEY] [--executor-fee N] [--crank-fee N] [--bury-bps N]
#       [--executor-float LAMPORTS] [--cu-price MICRO_LAMPORTS] [--public-rpc] [--yes]
#
# --public-rpc (or HD_PUBLIC_RPC=1) sends over the public mainnet RPC although helius.env is
# there (a Helius key with no credits left answers HTTP 429 to everything): a float top-up still
# goes out. The RPC in use is printed first.
#
# Accounts: upgrade authority (deployer.json, signer, pays rent) | Config PDA inzDn4og… |
#           ProgramData 3jjGZ8EE… (the program checks the signer against it) | System.
# Defaults (docs/DEPLOY.md "Parameters and why"):
#   governance      governance.json's pubkey (or a Squads vault). No script here can change it
#                   afterwards: the program's rotation instructions exist since v1.3, a command
#                   that sends them does not.
#   registrar       registrar.json's pubkey (hd-registrar keygen)
#   executor_fee    10,000 lamports, immutable: the Discretionary fee every rig's Automation must use
#   crank_fee        7,000 lamports, <= executor_fee (timelocked changes via propose_config)
#   bury_bps        0 (no bury path in v1.1)
#   ore_layout_hash sha256(heads_down::ore::LAYOUT_PREIMAGE), computed by the program crate itself
#   Executor float  rent-exempt(0) + 10 x CHECKPOINT_FEE + 100 x crank_fee (cluster rent)
HD_SCRIPT=init-config
source "$(dirname "$0")/lib.sh"

GOVERNANCE="" REGISTRAR="" FLOAT=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --governance) GOVERNANCE="$2"; shift 2 ;;
    --registrar) REGISTRAR="$2"; shift 2 ;;
    --executor-fee) HD_EXECUTOR_FEE="$2"; shift 2 ;;
    --crank-fee) HD_CRANK_FEE="$2"; shift 2 ;;
    --bury-bps) HD_BURY_BPS="$2"; shift 2 ;;
    --executor-float) FLOAT=(--executor-float "$2"); shift 2 ;;
    --cu-price) HD_CU_PRICE="$2"; shift 2 ;;
    --public-rpc) PUBLIC_RPC=1; shift ;;
    --yes) YES=1; shift ;;
    -h | --help) sed -n '2,25p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
default_keys
need solana-keygen "install the Agave CLI 4.1"
need perl "perl ships with macOS"
key_ok "$K_DEPLOYER" || die "$K_DEPLOYER missing or not mode 600"
[[ -n "$GOVERNANCE" ]] || { key_ok "$K_GOVERNANCE" || die "no --governance and no governance.json (keys.sh)"; GOVERNANCE="$(pubkey_of "$K_GOVERNANCE")"; }
[[ -n "$REGISTRAR" ]] || { key_ok "$K_REGISTRAR" || die "no --registrar and no registrar.json (keys.sh)"; REGISTRAR="$(pubkey_of "$K_REGISTRAR")"; }
resolve_rpc
mkdir -p "$RECEIPTS/$CLUSTER"
RECEIPT="$RECEIPTS/$CLUSTER/$(timestamp)-init.json"

ARGS=(init --authority "$K_DEPLOYER" --governance "$GOVERNANCE" --registrar "$REGISTRAR"
  --executor-fee "$HD_EXECUTOR_FEE" --crank-fee "$HD_CRANK_FEE" --bury-bps "$HD_BURY_BPS"
  --crank-reserve-digs "$HD_CRANK_RESERVE_DIGS" --cu-price "$HD_CU_PRICE" ${FLOAT[@]+"${FLOAT[@]}"})

bold "initialize_config on $CLUSTER via $RPC_HOST"
if [[ "$CLUSTER" == mainnet && $YES == 0 ]]; then
  # Dry pass: the tool prints the plan and refuses to send without --yes.
  tool "${ARGS[@]}" || true
  confirm "initialize heads_down config" "initialize_config is permanent for executor_fee, and no script here can change governance afterwards"
fi
tool "${ARGS[@]}" --yes --receipt "$RECEIPT"
echo
bold "done: Config $HD_CONFIG_PDA, Executor PDA $HD_EXECUTOR_PDA on $CLUSTER"
echo "  receipt  ${RECEIPT#"$REPO_ROOT"/}"
echo "  check    scripts/mainnet/governance.sh --cluster $CLUSTER$([[ "$KEYS" != "$HD_MAINNET_KEYS" ]] && echo " --keys-dir $KEYS") show"
