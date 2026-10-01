#!/usr/bin/env bash
# Build heads_down from a clean, recorded commit, preflight it, deploy it, check the on-chain bytes
# against the build, and write the public receipt to deploy/receipts/<cluster>/.
#
#   scripts/mainnet/deploy.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--mode fresh|upgrade|buffer]
#                             [--max-len N] [--cu-price MICRO_LAMPORTS] [--max-sign-attempts N]
#                             [--buffer-authority PUBKEY] [--skip-build] [--allow-dirty] [--yes]
#
# fresh    first deploy: `solana program deploy --program-id <program keypair> --upgrade-authority
#          deployer.json --max-len N` (the program keypair only signs; it stays where it is)
# upgrade  in-place upgrade signed by the deployer (while it is still the upgrade authority)
# buffer   write a fresh buffer (and hand it to --buffer-authority, e.g. the Squads vault) for a
#          multisig upgrade proposal; nothing is upgraded here
#
# The buffer keypair is kept per commit in the key dir (buffer-<commit>.json), so a failed deploy
# resumes by re-running this script, and the CLI never prints a recovery seed phrase.
# --cluster localnet runs the same path against the dev stack (scripts/mainnet/dry-run.sh).
HD_SCRIPT=deploy
source "$(dirname "$0")/lib.sh"

MODE=fresh SKIP_BUILD=0 ALLOW_DIRTY=0 BUFFER_AUTHORITY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --max-len) HD_MAX_LEN="$2"; shift 2 ;;
    --cu-price) HD_CU_PRICE="$2"; shift 2 ;;
    --max-sign-attempts) HD_MAX_SIGN_ATTEMPTS="$2"; shift 2 ;;
    --buffer-authority) BUFFER_AUTHORITY="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --yes) YES=1; shift ;;
    -h | --help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
case "$MODE" in fresh | upgrade | buffer) ;; *) die "--mode must be fresh, upgrade or buffer" ;; esac
default_keys
need solana "install the Agave CLI 4.1 (~/.local/share/solana/install/active_release/bin)"
need solana-keygen "install the Agave CLI 4.1"
need cargo-build-sbf "install the Agave CLI 4.1"
need perl "perl ships with macOS"
need python3 "python3 ships with the Xcode command line tools"
resolve_rpc
mkdir -p "$HD_STATE"
TS="$(timestamp)"

# ---- 1. a clean, recorded commit ----------------------------------------------------------------
COMMIT="$(git_commit)"
DIRTY="$(git_dirty)"
if [[ "$DIRTY" == 1 ]]; then
  if [[ "$CLUSTER" == mainnet ]]; then die "the tree has local changes: commit or stash them; mainnet deploys only a clean, recorded commit"; fi
  [[ $ALLOW_DIRTY == 1 ]] || die "the tree has local changes (localnet: pass --allow-dirty to deploy them anyway)"
  warn "deploying a dirty tree to localnet (recorded as git_dirty=1)"
fi
if [[ $SKIP_BUILD == 1 && "$CLUSTER" == mainnet ]]; then die "--skip-build is localnet-only: mainnet always builds from the recorded commit"; fi

# ---- 2. build (programs/heads-down/scripts/build.sh, mainnet feature) --------------------------------
if [[ $SKIP_BUILD == 0 ]]; then
  log "building heads_down at ${COMMIT:0:12} (programs/heads-down/scripts/build.sh, --features mainnet)"
  bash "$REPO_ROOT/programs/heads-down/scripts/build.sh" >"$HD_STATE/build-program-$TS.log" 2>&1 \
    || die "program build failed: $HD_STATE/build-program-$TS.log"
  if [[ "$DIRTY" == 0 && "$(git_dirty)" == 1 ]]; then die "the build changed tracked files; refusing to deploy"; fi
fi
[[ -f "$HD_SO" ]] || die "$HD_SO missing"
SO_SHA="$(shasum -a 256 "$HD_SO" | cut -d' ' -f1)"
SO_LEN="$(wc -c <"$HD_SO" | tr -d ' ')"
SOLANA_VER="$(solana --version)"
SBF_VER="$(cargo-build-sbf --version | tr '\n' ' ' | sed 's/ *$//')"
log "built $SO_LEN bytes, sha256 $SO_SHA ($SBF_VER)"

# ---- 3. preflight (read-only) ----------------------------------------------------------------------
PREFLIGHT_JSON="$HD_STATE/preflight-$CLUSTER-$TS.json"
"$MAINNET_SCRIPTS/preflight.sh" --cluster "$CLUSTER" --keys-dir "$KEYS" --mode "$MODE" --max-len "$HD_MAX_LEN" \
  --so "$HD_SO" --json "$PREFLIGHT_JSON" || die "preflight is NO-GO; nothing was sent"

# ---- 4. plan and confirmation --------------------------------------------------------------------------
DEPLOYER="$(pubkey_of "$K_DEPLOYER")"
BUFFER_KP="$KEYS/buffer-${COMMIT:0:12}.json"
if [[ ! -e "$BUFFER_KP" ]]; then
  (umask 077 && solana-keygen new --no-bip39-passphrase --silent --outfile "$BUFFER_KP" >/dev/null)
fi
chmod 600 "$BUFFER_KP"
BUFFER="$(pubkey_of "$BUFFER_KP")"
echo
bold "deploy plan ($CLUSTER, mode $MODE)"
cat <<EOF
  program id         $HD_PROGRAM_ID
  fee payer          $DEPLOYER (deployer.json)
  upgrade authority  $DEPLOYER (moves to a Squads vault later: docs/DEPLOY.md)
  build              $SO_LEN bytes, sha256 $SO_SHA, commit $COMMIT (dirty=$DIRTY)
  max-len            $([[ $MODE == fresh ]] && echo "$HD_MAX_LEN bytes" || echo "n/a ($MODE)")
  buffer             $BUFFER (buffer-${COMMIT:0:12}.json; resumable)
  priority fee       $HD_CU_PRICE micro-lamports/CU, up to $HD_MAX_SIGN_ATTEMPTS signing rounds
  RPC                $RPC_HOST
EOF
if [[ "$MODE" == buffer ]]; then echo "  buffer authority   ${BUFFER_AUTHORITY:-$DEPLOYER (unchanged)}"; fi
confirm "deploy heads_down to mainnet" "this $MODE spends real SOL from $DEPLOYER"

# ---- 5. deploy ----------------------------------------------------------------------------------------
solana_cfg "$K_DEPLOYER"
BAL_BEFORE="$(scli balance "$DEPLOYER" --lamports | awk '{print $1}')"
OUT="$HD_STATE/deploy-$CLUSTER-$TS.out"
COMMON=(--use-rpc --with-compute-unit-price "$HD_CU_PRICE" --max-sign-attempts "$HD_MAX_SIGN_ATTEMPTS" --commitment confirmed --output json-compact)
recover_hint() {
  cat >&2 <<EOF

Nothing is lost. The buffer $BUFFER may hold rent from this attempt:
  resume:  re-run this script at the same commit (it reuses buffer-${COMMIT:0:12}.json)
  refund:  scripts/mainnet/solana.sh --cluster $CLUSTER -- program close $BUFFER --recipient $DEPLOYER
EOF
}
set +e
case "$MODE" in
  fresh)
    WHAT="solana program deploy"
    scli program deploy "${COMMON[@]}" --program-id "$HD_PROGRAM_KEYPAIR" --upgrade-authority "$K_DEPLOYER" \
      --keypair "$K_DEPLOYER" --buffer "$BUFFER_KP" --max-len "$HD_MAX_LEN" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
  upgrade)
    WHAT="solana program deploy (upgrade)"
    scli program deploy "${COMMON[@]}" --program-id "$HD_PROGRAM_ID" --upgrade-authority "$K_DEPLOYER" \
      --keypair "$K_DEPLOYER" --buffer "$BUFFER_KP" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
  buffer)
    WHAT="solana program write-buffer"
    scli program write-buffer "${COMMON[@]}" --buffer "$BUFFER_KP" --keypair "$K_DEPLOYER" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
esac
set -e
if [[ $RC -ne 0 ]]; then
  recover_hint
  die "$WHAT failed (exit $RC); log $OUT"
fi
SIG="$(python3 - "$OUT" <<'PY'
import json, sys
# The CLI's result object: the last JSON object in the output (one line with json-compact,
# several with json), after any progress lines.
text = open(sys.argv[1]).read()
lines = text.splitlines()
for i in range(len(lines) - 1, -1, -1):
    if lines[i].lstrip().startswith("{"):
        try:
            print(json.loads("\n".join(lines[i:])).get("signature") or "")
            break
        except ValueError:
            continue
PY
)"
[[ -n "$SIG" || "$MODE" == buffer ]] || warn "no transaction signature in the CLI output ($OUT); the receipt will not carry one"
if [[ "$MODE" == buffer && -n "$BUFFER_AUTHORITY" ]]; then
  log "handing the buffer to $BUFFER_AUTHORITY"
  scli program set-buffer-authority "$BUFFER" --new-buffer-authority "$BUFFER_AUTHORITY" --keypair "$K_DEPLOYER" \
    --with-compute-unit-price "$HD_CU_PRICE" | tee -a "$OUT"
fi

# ---- 6. verify the bytes and write the receipt ------------------------------------------------------
mkdir -p "$RECEIPTS/$CLUSTER"
RECEIPT="$RECEIPTS/$CLUSTER/$TS-$MODE-${COMMIT:0:12}.json"
# The receipt is public: record the build's path relative to the repository, not this machine's.
cd "$REPO_ROOT"
SO_ARG="$HD_SO"
if [[ "$HD_SO" == "$REPO_ROOT"/* ]]; then SO_ARG="${HD_SO#"$REPO_ROOT"/}"; fi
VERIFY=(--mode "$MODE" --so "$SO_ARG" --program-id "$HD_PROGRAM_ID" --deployer "$DEPLOYER" --balance-before "$BAL_BEFORE"
  --meta "git_commit=$COMMIT" --meta "git_dirty=$DIRTY" --meta "build_script=programs/heads-down/scripts/build.sh"
  --meta "cargo_features=mainnet" --meta "solana_cli=$SOLANA_VER" --meta "cargo_build_sbf=$SBF_VER"
  --meta "cu_price_micro_lamports=$HD_CU_PRICE" --meta "preflight=GO" --out "$RECEIPT")
if [[ -n "$SIG" ]]; then VERIFY+=(--signature "$SIG"); fi
case "$MODE" in
  fresh) VERIFY+=(--max-len "$HD_MAX_LEN") ;;
  buffer) VERIFY+=(--buffer "$BUFFER" --authority "${BUFFER_AUTHORITY:-$DEPLOYER}") ;;
esac
tool verify-deploy "${VERIFY[@]}"

echo
bold "done: $MODE on $CLUSTER"
echo "  receipt   ${RECEIPT#"$REPO_ROOT"/}"
if [[ "$MODE" == fresh ]]; then
  echo "  next      scripts/mainnet/init-config.sh --cluster $CLUSTER$([[ "$KEYS" != "$HD_MAINNET_KEYS" ]] && echo " --keys-dir $KEYS")"
elif [[ "$MODE" == buffer ]]; then
  echo "  next      propose the upgrade in Squads: program $HD_PROGRAM_ID, buffer $BUFFER, spill $DEPLOYER"
fi
if [[ "$CLUSTER" == mainnet ]]; then echo "  commit    git add deploy/receipts/mainnet && git commit -m 'chore(deploy): mainnet receipt ${COMMIT:0:12}'"; fi
