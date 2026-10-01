#!/usr/bin/env bash
# The Agave CLI against the deploy cluster, without the Helius key ever reaching a command line.
#
#   scripts/mainnet/solana.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--keypair FILE] -- <solana args...>
#
# The RPC URL goes into a private CLI config (dir 700, file 600, deleted on exit); the default
# signer is deployer.json (or --keypair); every line of output is redacted. Examples:
#   scripts/mainnet/solana.sh -- program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p
#   scripts/mainnet/solana.sh -- balance By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge
#   scripts/mainnet/solana.sh -- program close <BUFFER> --recipient <DEPLOYER>            # refund a stale buffer
#   scripts/mainnet/solana.sh -- program set-upgrade-authority HDn4vg… \
#       --new-upgrade-authority <SQUADS_VAULT> --skip-new-upgrade-authority-signer-check   # docs/DEPLOY.md
HD_SCRIPT=solana
source "$(dirname "$0")/lib.sh"

SIGNER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --keypair) SIGNER="$2"; shift 2 ;;
    --) shift; break ;;
    -h | --help) sed -n '2,13p' "$0"; exit 0 ;;
    *) die "unknown option $1 (put solana arguments after --)" ;;
  esac
done
[[ $# -gt 0 ]] || die "usage: solana.sh [--cluster C] [--keys-dir D] [--keypair F] -- <solana args...>"
for a in "$@"; do
  case "$a" in -u | --url | -C | --config | --url=* | --config=*) die "solana.sh sets the cluster itself; do not pass $a" ;; esac
done
default_keys
need solana "install the Agave CLI 4.1"
need perl "perl ships with macOS"
resolve_rpc
solana_cfg "${SIGNER:-$K_DEPLOYER}"
scli "$@"
