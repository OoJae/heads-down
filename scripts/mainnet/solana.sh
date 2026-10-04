#!/usr/bin/env bash
# The Agave CLI against the deploy cluster, without the Helius key ever reaching a command line.
#
#   scripts/mainnet/solana.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--keypair FILE] [--public-rpc] -- <solana args...>
#
# The RPC URL goes into a private CLI config (dir 700, file 600, deleted on exit); the default
# signer is deployer.json (or --keypair); every line of output is redacted. Examples:
#   scripts/mainnet/solana.sh -- program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p
#   scripts/mainnet/solana.sh -- balance By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge
#   scripts/mainnet/solana.sh -- program close <BUFFER> --recipient <DEPLOYER>            # refund a stale buffer
#   scripts/mainnet/solana.sh -- program set-upgrade-authority HDn4vg… \
#       --new-upgrade-authority <SQUADS_VAULT> --skip-new-upgrade-authority-signer-check   # docs/DEPLOY.md
#
# --public-rpc (or HD_PUBLIC_RPC=1) uses the public mainnet RPC although helius.env is there,
# for the day the Helius key has no credits left. The RPC in use is named on stderr, so the
# CLI's own output on stdout stays as it is.
HD_SCRIPT=solana
source "$(dirname "$0")/lib.sh"

SIGNER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --keypair) SIGNER="$2"; shift 2 ;;
    --public-rpc) PUBLIC_RPC=1; shift ;;
    --) shift; break ;;
    -h | --help) sed -n '2,16p' "$0"; exit 0 ;;
    *) die "unknown option $1 (put solana arguments after --)" ;;
  esac
done
[[ $# -gt 0 ]] || die "usage: solana.sh [--cluster C] [--keys-dir D] [--keypair F] [--public-rpc] -- <solana args...>"
for a in "$@"; do
  case "$a" in -u | --url | -C | --config | --url=* | --config=*) die "solana.sh sets the cluster itself; do not pass $a" ;; esac
done
default_keys
need solana "install the Agave CLI 4.1"
need perl "perl ships with macOS"
resolve_rpc
say_rpc
solana_cfg "${SIGNER:-$K_DEPLOYER}"
scli "$@"
