#!/usr/bin/env bash
# Regenerate the golden vectors in vectors/ by executing every instruction on
# the pinned LiteSVM fork (tests/src/vectors.rs). Run after an intentional
# contract change, review the diff, and update INTERFACE.md in the same commit.
# Without HD_WRITE_VECTORS the same test only checks for drift (scripts/test.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so ]] || bash tests/fixtures/fetch-fixtures.sh
[[ -f target/deploy/heads_down.so && -f target/deploy-mock/mock_ore.so ]] || bash scripts/build.sh
HD_WRITE_VECTORS=1 cargo "$TOOLCHAIN" test -p heads-down-tests --test vectors
git -C .. diff --stat -- heads-down/vectors || true
