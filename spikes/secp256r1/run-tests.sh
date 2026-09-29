#!/usr/bin/env bash
# Spike 1(b): build the SBF test program and run the LiteSVM suites.
#
# --ignore-rust-version: litesvm 0.17.0 pulls Agave 4.3.0 crates that declare
# rust-version = 1.97.1. They compile and pass on rustc 1.96; drop the flag
# once the toolchain is >= 1.97.1.
set -euo pipefail
cd "$(dirname "$0")"

AGAVE_BIN="$HOME/.local/share/solana/install/active_release/bin"
if ! command -v cargo-build-sbf >/dev/null 2>&1; then
  export PATH="$AGAVE_BIN:$PATH"
fi

cargo-build-sbf --manifest-path program/Cargo.toml
cargo test --ignore-rust-version -p p256-spike-tests -- --nocapture --test-threads=1 "$@"
