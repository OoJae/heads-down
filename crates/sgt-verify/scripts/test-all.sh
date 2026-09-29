#!/usr/bin/env bash
# Everything, in the order CI should run it:
#   1. library tests with mainnet anchors (fixtures, spoof suite, properties)
#   2. the same plus the std testkit
#   3. the test-group build (anchors swapped)
#   4. clippy on the library (its on-chain lints are deny-level)
#   5. both SBF probe builds
#   6. the LiteSVM suite (real Token-2022 + the SBF probes)
#
# litesvm 0.17's agave 4.3 dependencies declare rust-version 1.97.1, so step 6
# uses that toolchain unless LITESVM_TOOLCHAIN says otherwise.
set -euo pipefail
cd "$(dirname "$0")/.."
unset SGT_VERIFY_TEST_GROUP SGT_VERIFY_TEST_AUTHORITY

step() { printf '\n==> %s\n' "$*"; }

step "cargo test (mainnet anchors)"
cargo test

step "cargo test --features std"
cargo test --features std

step "cargo test --features test-group,std"
cargo test --features test-group,std

step "mainnet + test-group must not compile"
if cargo check --features mainnet,test-group 2>/dev/null; then
  echo "ERROR: mainnet and test-group compiled together" >&2
  exit 1
fi
echo "ok: refused"

step "cargo clippy"
cargo clippy --all-targets --features std -- -D warnings

step "SBF probes"
./scripts/build-sbf.sh

step "LiteSVM suite"
cargo "+${LITESVM_TOOLCHAIN:-1.97.1}" test -p sgt-verify-litesvm-tests -- --nocapture
