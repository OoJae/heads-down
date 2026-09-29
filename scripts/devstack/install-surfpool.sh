#!/usr/bin/env bash
# Install a PINNED Surfpool release binary (no Docker, no curl | bash), verified by SHA-256.
#
# Source: https://github.com/solana-foundation/surfpool/releases (the project moved there from
# txtx/surfpool; https://run.surfpool.run/ is a shell installer that downloads the same
# `releases/latest` tarballs WITHOUT checking a hash, so it is not used here).
# The digests below are GitHub's own asset digests for the tag, re-checked after download.
#
# Installs to $HD_DEVSTACK_HOME/bin/surfpool (outside the repo; nothing on your PATH changes).
source "$(dirname "$0")/lib.sh"

VERSION="v1.6.0"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ASSET=surfpool-darwin-arm64.tar.gz; SHA=a890db2b1a0c77340a4cf90c9cfb7b2d30fbeb538120ad20c707426852b18c2d ;;
  Darwin-x86_64) ASSET=surfpool-darwin-x64.tar.gz; SHA=0861dd1c5be216d4ead2bb3a328eb5ea4d180385a9d10ba6c3cf289045d12b62 ;;
  Linux-x86_64) ASSET=surfpool-linux-x64.tar.gz; SHA=cb471b00aa3b7d603338eb74ebfec1d23959ecba005b5f1be42ba8d174107fc2 ;;
  *) die "no pinned Surfpool $VERSION build for $(uname -s)-$(uname -m)" ;;
esac

if [[ -x "$SURFPOOL_BIN" ]] && "$SURFPOOL_BIN" --version 2>/dev/null | grep -q "${VERSION#v}"; then
  log "surfpool $VERSION already installed at $SURFPOOL_BIN"
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
URL="https://github.com/solana-foundation/surfpool/releases/download/$VERSION/$ASSET"
log "downloading $URL"
curl -fL --retry 3 -o "$TMP/$ASSET" "$URL"
GOT="$(shasum -a 256 "$TMP/$ASSET" | cut -d' ' -f1)"
[[ "$GOT" == "$SHA" ]] || die "SHA-256 mismatch for $ASSET: got $GOT, expected $SHA (refusing to install)"
tar -xzf "$TMP/$ASSET" -C "$TMP"
mkdir -p "$(dirname "$SURFPOOL_BIN")"
install -m 755 "$TMP/surfpool" "$SURFPOOL_BIN"
log "installed $("$SURFPOOL_BIN" --version) at $SURFPOOL_BIN (sha256 of the tarball verified)"
