#!/usr/bin/env bash
# Build both SBF variants of heads_down:
#   target/deploy/heads_down.so         --features mainnet  (real SGT anchors; what ships)
#   target/deploy-devnet/heads_down.so  --features devnet   (SGT test-group anchors)
#
# The devnet variant reads SGT_VERIFY_TEST_GROUP / SGT_VERIFY_TEST_AUTHORITY at
# build time. For the LiteSVM suite they must be UNSET (public test keys); for a
# real devnet deployment set them to keys you control (see README.md).
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."

cargo-build-sbf --manifest-path program/Cargo.toml --features mainnet --sbf-out-dir target/deploy

if [[ "${HD_DEVNET_ANCHORS:-}" == "env" ]]; then
  cargo-build-sbf --manifest-path program/Cargo.toml --features devnet --sbf-out-dir target/deploy-devnet
else
  env -u SGT_VERIFY_TEST_GROUP -u SGT_VERIFY_TEST_AUTHORITY \
    cargo-build-sbf --manifest-path program/Cargo.toml --features devnet --sbf-out-dir target/deploy-devnet
fi

ls -l target/deploy/heads_down.so target/deploy-devnet/heads_down.so
