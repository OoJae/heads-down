#!/usr/bin/env bash
# Build both variants of the SBF probe used by the LiteSVM suite:
#   target/deploy/sgt_verify_probe.so             mainnet anchors (default)
#   target/deploy-test-group/sgt_verify_probe.so  --features test-group, public test keys
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."

cargo-build-sbf --manifest-path probe/Cargo.toml --sbf-out-dir target/deploy

# The LiteSVM suite signs with the public test keys, so the test-group probe
# must not pick up an override from the caller's environment.
env -u SGT_VERIFY_TEST_GROUP -u SGT_VERIFY_TEST_AUTHORITY \
  cargo-build-sbf --manifest-path probe/Cargo.toml --features test-group \
  --sbf-out-dir target/deploy-test-group

ls -l target/deploy/sgt_verify_probe.so target/deploy-test-group/sgt_verify_probe.so
