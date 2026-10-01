#!/usr/bin/env bash
# Build heads_down for SBF.
#
#   bash scripts/build.sh                 # SBPFv3 (the default; what ships to mainnet)
#   HD_SBF_ARCH=v0 bash scripts/build.sh  # SBPFv0 fallback (see README "SBPFv3")
#   HD_SBF_INSTALL=0 ...                  # build the arch, leave target/deploy alone
#
# Output, for the chosen arch:
#   target/deploy-<arch>/heads_down.so          --features mainnet (real SGT anchors)
#   target/deploy-devnet-<arch>/heads_down.so   --features devnet (SGT test-group anchors)
#   target/deploy/heads_down.so                 a copy of the mainnet build: THE artifact.
#                                               scripts/mainnet/deploy.sh and
#                                               scripts/devstack/up.sh deploy this path.
#   target/deploy-devnet/heads_down.so          a copy of the devnet build
#   target/deploy-mock/mock_ore.so              TEST ONLY: the misbehaving ORE stand-in
#
# The per-arch directories let both arches sit side by side: the fork suite loads them
# when HD_SBF_ARCH is set (scripts/test-v3.sh, scripts/test-v0.sh), and target/deploy
# otherwise. HD_SBF_INSTALL=0 skips the two copies, so testing the other arch never
# changes what target/deploy holds.
#
# Why v3 by default: mainnet accepts SBPFv3 deployments (SIMD-0178/0189/0377, active),
# and SIMD-0500 will refuse new v0/v1/v2 deployments once it activates. The v0 path is
# kept for a validator or tool that cannot load v3; nothing in this repo needs it today.
#
# The devnet variant reads SGT_VERIFY_TEST_GROUP / SGT_VERIFY_TEST_AUTHORITY at
# build time. For the LiteSVM suite they must be UNSET (public test keys); for a
# real devnet deployment set them to keys you control (HD_DEVNET_ANCHORS=env, see
# README.md).
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."

ARCH="${HD_SBF_ARCH:-v3}"
case "$ARCH" in
  v3) ARCH_ARGS=(--arch v3); E_FLAGS=3 ;;
  v0) ARCH_ARGS=(); E_FLAGS=0 ;; # cargo-build-sbf 4.1 builds v0 unless told otherwise
  *) echo "HD_SBF_ARCH must be v3 (default) or v0, not '$ARCH'" >&2; exit 2 ;;
esac

cargo-build-sbf --manifest-path program/Cargo.toml --features mainnet ${ARCH_ARGS[@]+"${ARCH_ARGS[@]}"} \
  --sbf-out-dir "target/deploy-$ARCH"

if [[ "${HD_DEVNET_ANCHORS:-}" == "env" ]]; then
  cargo-build-sbf --manifest-path program/Cargo.toml --features devnet ${ARCH_ARGS[@]+"${ARCH_ARGS[@]}"} \
    --sbf-out-dir "target/deploy-devnet-$ARCH"
else
  env -u SGT_VERIFY_TEST_GROUP -u SGT_VERIFY_TEST_AUTHORITY \
    cargo-build-sbf --manifest-path program/Cargo.toml --features devnet ${ARCH_ARGS[@]+"${ARCH_ARGS[@]}"} \
    --sbf-out-dir "target/deploy-devnet-$ARCH"
fi

# TEST ONLY: the misbehaving ORE stand-in used by tests/tests/ore_semantics.rs
# (SBPFv0, like the live ORE program it replaces).
cargo-build-sbf --manifest-path tests/mock-ore/Cargo.toml --sbf-out-dir target/deploy-mock

# Install the chosen arch where the deploy scripts look (unless HD_SBF_INSTALL=0), and
# prove what every file is: the ELF e_flags field is the SBPF version.
BUILT=("target/deploy-$ARCH/heads_down.so" "target/deploy-devnet-$ARCH/heads_down.so")
if [[ "${HD_SBF_INSTALL:-1}" != 0 ]]; then
  mkdir -p target/deploy target/deploy-devnet
  cp "target/deploy-$ARCH/heads_down.so" target/deploy/heads_down.so
  cp "target/deploy-devnet-$ARCH/heads_down.so" target/deploy-devnet/heads_down.so
  BUILT+=(target/deploy/heads_down.so target/deploy-devnet/heads_down.so)
fi
python3 - "$E_FLAGS" "${BUILT[@]}" <<'PY'
import hashlib
import struct
import sys

want = int(sys.argv[1])
for path in sys.argv[2:]:
    data = open(path, "rb").read()
    flags = struct.unpack_from("<I", data, 48)[0]
    if flags != want:
        sys.exit(f"{path}: e_flags {flags}, expected {want} (SBPFv{want})")
    print(f"{path}: SBPFv{flags}, {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")
PY
ls -l target/deploy-mock/mock_ore.so
