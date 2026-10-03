#!/usr/bin/env bash
# The SBPFv0 fallback: build both heads_down variants as v0 into target/deploy-v0 and
# target/deploy-devnet-v0 (scripts/build.sh asserts ELF e_flags 0) and run the whole
# LiteSVM fork suite against exactly those files (HD_SBF_ARCH=v0). target/deploy, the
# artifact the deploy scripts read, is left alone (HD_SBF_INSTALL=0).
#
# v0 is not what ships: SIMD-0500 will refuse new v0 deployments. This keeps the
# fallback honest for as long as it exists (README "SBPFv3").
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so && -f tests/fixtures/ore_stake.so ]] || bash tests/fixtures/fetch-fixtures.sh
HD_SBF_ARCH=v0 HD_SBF_INSTALL=0 bash scripts/build.sh
HD_SBF_ARCH=v0 cargo "$TOOLCHAIN" test -p heads-down-tests "$@"
