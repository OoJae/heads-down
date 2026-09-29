# syntax=docker/dockerfile:1.7
#
# Heads Down registrar. Build from this directory:
#   docker build -t hd-registrar registrar/
# Run (registrar key mounted read-only, state on a volume):
#   docker run -p 8080:8080 \
#     -e HD_SESSION_SECRET=... -e HD_APP_RELEASE_CERT_SHA256=... \
#     -e HD_REGISTRAR_KEYPAIR=/run/secrets/registrar-keypair.json \
#     -v "$PWD/registrar-keypair.json:/run/secrets/registrar-keypair.json:ro" \
#     -v hd-registrar-data:/data hd-registrar

ARG RUST_VERSION=1.96

FROM rust:${RUST_VERSION}-bookworm AS build
WORKDIR /src

# 1. Dependencies only (cached until Cargo.toml / Cargo.lock change).
COPY Cargo.toml Cargo.lock ./
RUN mkdir src \
 && echo 'fn main() {}' > src/main.rs \
 && touch src/lib.rs \
 && cargo build --release --locked \
 && rm -rf src

# 2. The service. Trust anchors are compiled in from roots/.
COPY src ./src
COPY roots ./roots
RUN touch src/main.rs src/lib.rs \
 && cargo build --release --locked --bin hd-registrar \
 && mkdir -p /data

# Runtime: distroless (glibc + libgcc, no shell, no package manager), non-root uid 65532.
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=build /src/target/release/hd-registrar /usr/local/bin/hd-registrar
COPY --from=build --chown=65532:65532 /data /data

ENV HD_BIND=0.0.0.0:8080 \
    HD_NONCE_STORE=sqlite:/data/nonces.db \
    HD_TRANSPARENCY_LOG=/data/attestations.jsonl \
    HD_LOG_FORMAT=json \
    RUST_LOG=info

USER 65532:65532
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/usr/local/bin/hd-registrar", "healthcheck", "127.0.0.1:8080"]
ENTRYPOINT ["/usr/local/bin/hd-registrar"]
CMD ["serve"]
