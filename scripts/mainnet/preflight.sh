#!/usr/bin/env bash
# Read-only GO / NO-GO before deploying heads_down. Sends nothing, signs nothing.
#
#   scripts/mainnet/preflight.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--mode fresh|upgrade|buffer]
#                                [--max-len N] [--so PATH] [--json PATH] [--allow-simd0500-pending]
#
# Local:  helius.env present (mode 600, key well-formed, never printed); key dir 700 and key files 600;
#         the program keypair is HDn4vg…; the deployer is the founder's key (mainnet); the .so exists,
#         its sha256 and the git commit are recorded.
# Chain (hd-devstack preflight, via Helius): genesis hash is the cluster's; SIMD-0500 inactive (or an
#         SBPF v3 build); nothing at the program id yet (fresh mode); the deployer holds ProgramData
#         rent for max-len + Program rent + fees + Config rent + the Executor float (rent from the
#         cluster); ORE's ProgramData upgrade slot = 450,496,378 and its bytes = the verified build;
#         ORE Board/Treasury/Config/Round and sampled Automation/Miner sizes + discriminators = pins.
# Exit 0 = GO.
HD_SCRIPT=preflight
source "$(dirname "$0")/lib.sh"

MODE=fresh JSON="" EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --max-len) HD_MAX_LEN="$2"; shift 2 ;;
    --so) HD_SO="$2"; shift 2 ;;
    --json) JSON="$2"; shift 2 ;;
    --allow-simd0500-pending) EXTRA+=(--allow-simd0500-pending); shift ;;
    -h | --help) sed -n '2,17p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
default_keys
need solana-keygen "install the Agave CLI 4.1"
need perl "perl ships with macOS"

FAILS=0 WARNS=0
line() { # line STATUS CHECK DETAIL
  printf '%-4s  %-22s %s\n' "$1" "$2" "$3"
  case "$1" in FAIL) FAILS=$((FAILS + 1)) ;; WARN) WARNS=$((WARNS + 1)) ;; esac
}

bold "heads_down preflight: $CLUSTER, mode $MODE, $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---- local ----------------------------------------------------------------------------------------
if [[ "$CLUSTER" == mainnet ]]; then
  if [[ ! -f "$HD_HELIUS_ENV" ]]; then
    line FAIL "helius.env" "missing: $HD_HELIUS_ENV (one line HELIUS_API_KEY=...; chmod 600)"
  elif ! helius_file_ok; then
    line FAIL "helius.env" "$HD_HELIUS_ENV must be yours with mode 600 (is $(mode_of "$HD_HELIUS_ENV"))"
  else
    rc=0
    load_helius || rc=$?
    if [[ $rc -eq 0 ]]; then
      line PASS "helius.env" "present, mode 600, HELIUS_API_KEY well-formed (${#HELIUS_API_KEY} chars; value not shown)"
    else
      line FAIL "helius.env" "no well-formed HELIUS_API_KEY= line"
    fi
  fi
else
  line INFO "helius.env" "not used on localnet"
fi

if dir_ok "$KEYS"; then line PASS "key dir" "$KEYS (700)"; else line FAIL "key dir" "$KEYS must exist, be yours, mode 700 (scripts/mainnet/keys.sh)"; fi
for k in "$K_DEPLOYER" "$K_CRANK" "$K_GOVERNANCE" "$K_REGISTRAR"; do
  n="$(basename "$k")"
  if key_ok "$k"; then line PASS "$n" "$(pubkey_of "$k") (600)"; else line FAIL "$n" "missing or not mode 600: run scripts/mainnet/keys.sh"; fi
done
if key_ok "$K_SESSION"; then line PASS "session secret" "present (600; value not shown)"; else line WARN "session secret" "missing: keys.sh creates it (the registrar needs it, the deploy does not)"; fi

PROGRAM=""
if key_ok "$HD_PROGRAM_KEYPAIR"; then
  PROGRAM="$(pubkey_of "$HD_PROGRAM_KEYPAIR")"
  if [[ "$PROGRAM" == "$HD_PROGRAM_ID" ]]; then line PASS "program keypair" "$PROGRAM ($HD_PROGRAM_KEYPAIR, 600)"; else line FAIL "program keypair" "$PROGRAM is not $HD_PROGRAM_ID"; fi
else
  line FAIL "program keypair" "$HD_PROGRAM_KEYPAIR missing or not mode 600"
fi

DEPLOYER=""
if key_ok "$K_DEPLOYER"; then
  DEPLOYER="$(pubkey_of "$K_DEPLOYER")"
  if [[ "$CLUSTER" == mainnet && "$DEPLOYER" != "$HD_EXPECTED_DEPLOYER" ]]; then
    line FAIL "deployer" "$DEPLOYER is not the expected $HD_EXPECTED_DEPLOYER (override HD_EXPECTED_DEPLOYER only on purpose)"
  else
    line PASS "deployer" "$DEPLOYER"
  fi
fi

COMMIT="$(git_commit)"
DIRTY="$(git_dirty)"
if [[ "$DIRTY" == 0 ]]; then
  line PASS "git" "commit $COMMIT, clean tree"
elif [[ "$CLUSTER" == mainnet ]]; then
  line WARN "git" "commit $COMMIT with local changes: deploy.sh refuses to deploy mainnet from a dirty tree"
else
  line INFO "git" "commit $COMMIT with local changes"
fi
if [[ -f "$HD_SO" ]]; then
  SO_SHA="$(shasum -a 256 "$HD_SO" | cut -d' ' -f1)"
  line PASS "program .so" "$(wc -c <"$HD_SO" | tr -d ' ') bytes, sha256 $SO_SHA (${HD_SO#"$REPO_ROOT"/})"
else
  line FAIL "program .so" "$HD_SO missing: bash programs/heads-down/scripts/build.sh (deploy.sh builds it)"
fi

# ---- chain (read-only) -----------------------------------------------------------------------------
CHAIN_RC=0
if [[ -n "$PROGRAM" && -n "$DEPLOYER" && -f "$HD_SO" ]]; then
  resolve_rpc public-ok
  echo
  bold "chain checks via $RPC_HOST"
  KEYS_ARGS=()
  if key_ok "$K_CRANK"; then KEYS_ARGS+=(--key "crank-payer=$(pubkey_of "$K_CRANK"):$HD_CRANK_PAYER_LAMPORTS"); fi
  if key_ok "$K_GOVERNANCE"; then KEYS_ARGS+=(--key "governance=$(pubkey_of "$K_GOVERNANCE"):$HD_GOVERNANCE_LAMPORTS"); fi
  if key_ok "$K_REGISTRAR"; then KEYS_ARGS+=(--key "registrar=$(pubkey_of "$K_REGISTRAR"):0"); fi
  mkdir -p "$HD_STATE"
  [[ -n "$JSON" ]] || JSON="$HD_STATE/preflight-$CLUSTER-$(timestamp).json"
  tool preflight --so "$HD_SO" --max-len "$HD_MAX_LEN" --program-id "$PROGRAM" --deployer "$DEPLOYER" --mode "$MODE" \
    --executor-fee "$HD_EXECUTOR_FEE" --crank-fee "$HD_CRANK_FEE" --crank-reserve-digs "$HD_CRANK_RESERVE_DIGS" \
    --fee-budget "$HD_DEPLOY_FEE_BUDGET" ${KEYS_ARGS[@]+"${KEYS_ARGS[@]}"} ${EXTRA[@]+"${EXTRA[@]}"} --json "$JSON" || CHAIN_RC=$?
else
  line FAIL "chain checks" "skipped: fix the local failures above first"
fi

echo
if [[ $FAILS -eq 0 && $CHAIN_RC -eq 0 ]]; then
  bold "GO: $CLUSTER preflight passed ($WARNS local warnings; chain details in ${JSON:-n/a})"
  exit 0
fi
printf '\033[31;1mNO-GO: %s local failure(s), chain checks %s. Nothing was sent.\033[0m\n' "$FAILS" \
  "$([[ $CHAIN_RC -eq 0 ]] && echo passed || echo "FAILED (see FAIL lines above)")"
exit 1
