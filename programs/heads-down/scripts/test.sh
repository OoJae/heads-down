#!/usr/bin/env bash
# Everything, from a clean checkout:
#   1. mainnet fixtures (live ORE bytecode + accounts) if missing
#   2. both SBF builds (+ the TEST-ONLY mock ORE)
#   3. host unit tests, the LiteSVM fork suite, clippy
# Extra args go to the fork suite (e.g. `scripts/test.sh -- --nocapture`).
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so ]] || bash tests/fixtures/fetch-fixtures.sh
bash scripts/build.sh
cargo "$TOOLCHAIN" test -p heads-down
cargo "$TOOLCHAIN" test -p heads-down-tests "$@"
cargo "$TOOLCHAIN" clippy --workspace --all-targets -- -D warnings
