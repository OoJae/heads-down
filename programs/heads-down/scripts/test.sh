#!/usr/bin/env bash
# Everything, from a clean checkout:
#   1. mainnet fixtures (live ORE bytecode + accounts) if missing
#   2. both SBF builds (+ the TEST-ONLY mock ORE): SBPFv3 unless HD_SBF_ARCH=v0
#   3. host unit tests, the LiteSVM fork suite, clippy
# Extra args go to the fork suite (e.g. `scripts/test.sh -- --nocapture`).
#
# The fork suite loads target/deploy (what scripts/build.sh just installed, SBPFv3 by
# default). With HD_SBF_ARCH set it loads target/deploy-<arch> instead, which the same
# build wrote.
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so && -f tests/fixtures/ore_stake.so ]] || bash tests/fixtures/fetch-fixtures.sh
bash scripts/build.sh
cargo "$TOOLCHAIN" test -p heads-down
cargo "$TOOLCHAIN" test -p heads-down-tests "$@"
cargo "$TOOLCHAIN" clippy --workspace --all-targets -- -D warnings
