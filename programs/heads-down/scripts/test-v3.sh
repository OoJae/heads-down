#!/usr/bin/env bash
# SBPFv3: build both heads_down variants with `--arch v3` (SIMD-0500 will
# disable new v0/v1/v2 deploys) and run the whole LiteSVM fork suite against
# them (HD_SBF_ARCH=v3 makes tests/src/lib.rs load target/deploy-v3 and
# target/deploy-devnet-v3). The golden-vector test then also proves the v3
# binary produces byte-identical instructions, events and accounts.
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

[[ -f tests/fixtures/ore.so && -f tests/fixtures/ore_stake.so ]] || bash tests/fixtures/fetch-fixtures.sh
[[ -f target/deploy-mock/mock_ore.so ]] || bash scripts/build.sh

cargo-build-sbf --manifest-path program/Cargo.toml --features mainnet --arch v3 --sbf-out-dir target/deploy-v3
env -u SGT_VERIFY_TEST_GROUP -u SGT_VERIFY_TEST_AUTHORITY \
  cargo-build-sbf --manifest-path program/Cargo.toml --features devnet --arch v3 --sbf-out-dir target/deploy-devnet-v3

python3 - <<'PY'
import struct
for p in ("target/deploy-v3/heads_down.so", "target/deploy-devnet-v3/heads_down.so"):
    d = open(p, "rb").read()
    flags = struct.unpack_from("<I", d, 48)[0]
    assert flags == 3, f"{p}: e_flags {flags}, expected 3 (SBPFv3)"
    print(f"{p}: SBPFv3 (e_flags 3), {len(d)} bytes")
PY

HD_SBF_ARCH=v3 cargo "$TOOLCHAIN" test -p heads-down-tests "$@"
