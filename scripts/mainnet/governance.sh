#!/usr/bin/env bash
# Config governance: the emergency pause, timelocked changes, and the current state.
#
#   scripts/mainnet/governance.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--public-rpc] [--yes] COMMAND
#
#   show                       ORE + heads_down state: program, upgrade authority, Config, pending, Executor
#   pause                      propose_config paused=1: `dig` fails with Paused (18) from the next slot.
#                              Nothing else stops: users can still break, freeze, end shifts and close rigs.
#   unpause                    propose_config paused=0: takes effect only after apply_config, 864,000
#                              slots (>= 72 h) later
#   propose [--registrar PK] [--crank-fee N] [--bury-bps N]
#                              a timelocked change (unchanged fields keep their current values);
#                              replaces any pending proposal and restarts its clock
#   apply                      apply_config once the timelock has passed (anyone may; governance pays)
#
# Signed by governance.json (Config.governance). executor_fee can never change. There is no
# command here for the program's governance rotation (propose_governance, accept_governance).
#
# --public-rpc (or HD_PUBLIC_RPC=1) sends over the public mainnet RPC although helius.env is
# there: the way to pause when the Helius key has no credits left and answers HTTP 429 to
# everything. The RPC in use is printed first.
HD_SCRIPT=governance
source "$(dirname "$0")/lib.sh"

CMD="" PASS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --public-rpc) PUBLIC_RPC=1; shift ;;
    --yes) YES=1; shift ;;
    --registrar | --crank-fee | --bury-bps) PASS+=("$1" "$2"); shift 2 ;;
    show | pause | unpause | propose | apply) CMD="$1"; shift ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
[[ -n "$CMD" ]] || die "usage: governance.sh [--cluster C] [--keys-dir D] [--public-rpc] show|pause|unpause|propose|apply"
default_keys
need perl "perl ships with macOS"
resolve_rpc
say_rpc

run() { # run SUBCOMMAND ARGS... : plan pass on mainnet, confirm, then send
  if [[ "$CLUSTER" == mainnet && $YES == 0 ]]; then
    tool "$@" || true
    confirm "$CMD heads_down" "$CMD sends a governance transaction"
  fi
  tool "$@" --yes
}

case "$CMD" in
  show) tool status ;;
  pause) key_ok "$K_GOVERNANCE" || die "$K_GOVERNANCE missing"; run propose-config --governance "$K_GOVERNANCE" --paused 1 --cu-price "$HD_CU_PRICE" ;;
  unpause) key_ok "$K_GOVERNANCE" || die "$K_GOVERNANCE missing"; run propose-config --governance "$K_GOVERNANCE" --paused 0 --cu-price "$HD_CU_PRICE" ;;
  propose)
    [[ ${#PASS[@]} -gt 0 ]] || die "propose needs --registrar, --crank-fee and/or --bury-bps"
    key_ok "$K_GOVERNANCE" || die "$K_GOVERNANCE missing"
    run propose-config --governance "$K_GOVERNANCE" "${PASS[@]}" --cu-price "$HD_CU_PRICE"
    ;;
  apply) key_ok "$K_GOVERNANCE" || die "$K_GOVERNANCE missing"; run apply-config --payer "$K_GOVERNANCE" --cu-price "$HD_CU_PRICE" ;;
esac
