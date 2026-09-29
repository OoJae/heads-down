#!/usr/bin/env bash
# Re-run every cross-check behind vectors/CROSSCHECK.md. Read-only towards the
# other teams' directories: the crank and the registrar are compiled from
# temporary copies (their own Cargo.lock), with one example file added.
#
#   bash programs/heads-down/vectors/crosscheck/run.sh
#
# Needs: the fork fixtures and SBF builds (programs/heads-down/scripts/test.sh
# makes both), rustc 1.97.1, node >= 23.6 (type stripping).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROGRAM="$(cd "$HERE/../.." && pwd)"
REPO="$(cd "$PROGRAM/../.." && pwd)"
export VECTORS="$PROGRAM/vectors"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== 1. android / crank vectors and event semantics, executed on the LiteSVM fork"
(cd "$PROGRAM" && cargo +1.97.1 test -p heads-down-tests --test crosscheck -- --ignored --nocapture 2>&1 \
  | grep -E '^\[(MATCH|MISMATCH|INFO)\]|^MATCH ')

echo "== 2. the indexer's own decoder (services/indexer/src/codec) on captured events and real logs"
node "$HERE/indexer_events.mjs"

echo "== 3. the crank's own client code (crank/src/hd.rs, gate.rs) on the golden vectors"
mkdir -p "$TMP/crates" "$TMP/crank/examples"
cp -R "$REPO/crates/p256-introspect" "$TMP/crates/"
cp -R "$REPO/crank/Cargo.toml" "$REPO/crank/Cargo.lock" "$REPO/crank/rust-toolchain.toml" "$REPO/crank/src" "$TMP/crank/"
cp "$HERE/crank_golden_xcheck.rs" "$TMP/crank/examples/golden_xcheck.rs"
(cd "$TMP/crank" && cargo +1.97.1 run -q --release --example golden_xcheck)

echo "== 4. the registrar's own voucher.rs on vectors/registrar.json"
mkdir -p "$TMP/registrar/examples"
cp -R "$REPO/registrar/Cargo.toml" "$REPO/registrar/Cargo.lock" "$REPO/registrar/src" "$REPO/registrar/roots" "$TMP/registrar/"
cp "$HERE/registrar_voucher_vector.rs" "$TMP/registrar/examples/voucher_vector.rs"
(cd "$TMP/registrar" && cargo run -q --release --example voucher_vector)
