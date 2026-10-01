# syntax=docker/dockerfile:1.7
#
# hd-crank on Railway (docs/DEPLOY.md, "Railway").
#
# Build context: the REPOSITORY ROOT. The crank depends on crates/p256-introspect by path
# (crank/Cargo.toml), so the service's Root Directory stays "/" and its config file is
# /deploy/railway/crank/railway.json, which points at this Dockerfile. Locally:
#   docker build -f deploy/railway/crank/Dockerfile -t hd-crank .
#
# Runtime: debian bookworm-slim (the builder's glibc), no packages added. TLS roots are compiled
# into hd-crank (rustls + webpki-roots). The entrypoint starts as root only to put
# HD_CRANK_KEYPAIR_JSON into a 0600 file on tmpfs and to make the Railway volume at /data
# writable; hd-crank runs as uid 10001 (setpriv, no new privileges), and the key file is deleted
# as soon as hd-crank has loaded it.

ARG RUST_IMAGE=rust:1.97.1-bookworm@sha256:0e2bcaef56d041a486784e54104a81aebe0da44bd03019bd70bc0401e42e4a97
ARG RUNTIME_IMAGE=debian:bookworm-20260918-slim@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251

FROM ${RUST_IMAGE} AS build
# crank/rust-toolchain.toml pins 1.97.1, this image's toolchain. Pinning it here as well keeps
# rustup from fetching anything (the file also asks for clippy, which a build does not need).
ENV RUSTUP_TOOLCHAIN=1.97.1 \
    CARGO_TERM_COLOR=never \
    CARGO_NET_RETRY=5
WORKDIR /src
COPY crates/p256-introspect/ crates/p256-introspect/
COPY crank/Cargo.toml crank/Cargo.lock crank/rust-toolchain.toml crank/
# 1. Dependencies only: cached until the manifests or the path crate change.
RUN mkdir -p crank/src \
 && echo 'fn main() {}' > crank/src/main.rs \
 && touch crank/src/lib.rs \
 && cargo build --release --locked --manifest-path crank/Cargo.toml \
 && rm -rf crank/src
# 2. The crank itself.
COPY crank/src/ crank/src/
RUN touch crank/src/main.rs crank/src/lib.rs \
 && cargo build --release --locked --manifest-path crank/Cargo.toml --bin hd-crank \
 && install -m 0755 crank/target/release/hd-crank /hd-crank

FROM ${RUNTIME_IMAGE} AS runtime
RUN groupadd --system --gid 10001 hd \
 && useradd --system --uid 10001 --gid hd --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin hd \
 && mkdir -p /data/hd-crank /etc/hd-crank \
 && chown -R 10001:10001 /data
COPY --from=build /hd-crank /usr/local/bin/hd-crank
COPY deploy/railway/crank/crank.toml /etc/hd-crank/crank.toml
COPY deploy/railway/crank/entrypoint.sh /usr/local/bin/hd-crank-entrypoint
RUN chmod 0755 /usr/local/bin/hd-crank-entrypoint \
 && chmod 0644 /etc/hd-crank/crank.toml \
 && /usr/local/bin/hd-crank --version
ENV HD_CRANK_CONFIG=/etc/hd-crank/crank.toml \
    RUST_LOG=info,hyper=warn,reqwest=warn \
    PORT=8787
EXPOSE 8787
# No USER line on purpose: the entrypoint drops to uid 10001 itself (see the header).
ENTRYPOINT ["/bin/bash", "/usr/local/bin/hd-crank-entrypoint"]
CMD ["run"]
