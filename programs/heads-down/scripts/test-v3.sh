#!/usr/bin/env bash
# SBPFv3, explicitly: build both heads_down variants with `--arch v3` into
# target/deploy-v3 and target/deploy-devnet-v3 (scripts/build.sh asserts ELF e_flags 3)
# and run the whole LiteSVM fork suite against exactly those files (HD_SBF_ARCH=v3 makes
# tests/src/lib.rs load them). target/deploy is left alone.
#
# SBPFv3 is also the default of scripts/build.sh and scripts/test.sh since v1.3; this
# script stays as the arch-pinned run, next to scripts/test-v0.sh. The golden-vector
# test proves both arches produce byte-identical instructions, events and results.
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so && -f tests/fixtures/ore_stake.so ]] || bash tests/fixtures/fetch-fixtures.sh
HD_SBF_ARCH=v3 HD_SBF_INSTALL=0 bash scripts/build.sh
HD_SBF_ARCH=v3 cargo "$TOOLCHAIN" test -p heads-down-tests "$@"
