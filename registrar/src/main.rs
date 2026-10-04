//! `hd-registrar` binary.
//!
//! ```text
//! hd-registrar [serve]              run the HTTP service (configuration from HD_* env vars)
//! hd-registrar keygen <path>        write a new registrar keypair (Solana CLI JSON, mode 0600)
//! hd-registrar pubkey <path>        print the base58 public key of a keypair file
//! hd-registrar audit-log <path>     re-verify a transparency log offline (hash chain, voucher
//!                                   signatures, challenges, attestation chains to Google roots)
//! hd-registrar healthcheck [addr]   GET /healthz on 127.0.0.1:8080 (for container HEALTHCHECK)
//! ```

#![forbid(unsafe_code)]
#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::io::{Read as _, Write as _};
use std::net::SocketAddr;
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;

use hd_registrar::attest::chain::verify_chain;
use hd_registrar::attest::{challenge, RevocationList, TrustAnchors};
use hd_registrar::clock::{parse_rfc3339, SystemClock};
use hd_registrar::config::{Config, LogFormat, Secrets};
use hd_registrar::http::{router, spawn_maintenance, App, Overrides};
use hd_registrar::util::{b64_decode, decode_address, decode_hex_exact};
use hd_registrar::voucher::RegistrarKey;

fn init_tracing(format: LogFormat) {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,tower_http=warn"));
    let builder = tracing_subscriber::fmt().with_env_filter(filter).with_target(false);
    match format {
        LogFormat::Json => builder.json().flatten_event(true).with_current_span(true).init(),
        LogFormat::Pretty => builder.init(),
    }
}

async fn serve() -> ExitCode {
    let config = match Config::from_env() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("configuration error: {e}");
            return ExitCode::from(2);
        }
    };
    init_tracing(config.log_format);
    let secrets = match Secrets::from_env() {
        Ok(s) => s,
        Err(e) => {
            tracing::error!(error = %e, "secret configuration error");
            return ExitCode::from(2);
        }
    };
    if !config.debug_digests.is_empty() {
        tracing::warn!(
            "HD_APP_DEBUG_CERT_SHA256 is set: debug-signed builds will be vouched for. Never in production."
        );
    }
    let bind = config.bind;
    let app = match App::build(
        config,
        secrets,
        Arc::new(SystemClock),
        Overrides { anchors: None, status: None, slots: None, nonces: None },
    ) {
        Ok(a) => Arc::new(a),
        Err(e) => {
            tracing::error!(error = %e, "startup failed");
            return ExitCode::from(1);
        }
    };
    tracing::info!(
        registrar = %bs58::encode(app.registrar.pubkey()).into_string(),
        anchors = app.verifier.anchors.len(),
        log_size = app.log.len().await,
        %bind,
        "hd-registrar starting"
    );
    let _maintenance = spawn_maintenance(Arc::clone(&app));
    // One getSlot now, beside the start: /healthz makes no network call, so an RPC that gives
    // no slot would otherwise show only as 503 on the first attestation. It stops nothing.
    let probe = Arc::clone(&app);
    tokio::spawn(async move {
        probe.slots.report_at_start().await;
    });
    let listener = match tokio::net::TcpListener::bind(bind).await {
        Ok(l) => l,
        Err(e) => {
            tracing::error!(error = %e, "bind failed");
            return ExitCode::from(1);
        }
    };
    let service = router(app).into_make_service_with_connect_info::<SocketAddr>();
    if let Err(e) = axum::serve(listener, service).with_graceful_shutdown(shutdown_signal()).await {
        tracing::error!(error = %e, "server error");
        return ExitCode::from(1);
    }
    tracing::info!("hd-registrar stopped");
    ExitCode::SUCCESS
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let term = async {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            s.recv().await;
        }
    };
    #[cfg(not(unix))]
    let term = std::future::pending::<()>();
    tokio::select! { () = ctrl_c => {}, () = term => {} }
}

fn keygen(path: &Path) -> ExitCode {
    if path.exists() {
        eprintln!("refusing to overwrite {}", path.display());
        return ExitCode::from(1);
    }
    let key = match RegistrarKey::generate() {
        Ok(k) => k,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(1);
        }
    };
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let written = opts.open(path).and_then(|mut f| f.write_all(key.to_keypair_json().as_bytes()));
    if let Err(e) = written {
        eprintln!("cannot write {}: {e}", path.display());
        return ExitCode::from(1);
    }
    println!("{}", bs58::encode(key.pubkey()).into_string());
    ExitCode::SUCCESS
}

fn pubkey(path: &Path) -> ExitCode {
    match RegistrarKey::from_file(path) {
        Ok(k) => {
            println!("{}", bs58::encode(k.pubkey()).into_string());
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("{e}");
            ExitCode::from(1)
        }
    }
}

/// Re-verifies everything a third party can check from the published log alone.
fn audit_log(path: &Path) -> ExitCode {
    let entries = match hd_registrar::translog::audit(path) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("FAIL: {e}");
            return ExitCode::from(1);
        }
    };
    let anchors = match TrustAnchors::google() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("FAIL: {e}");
            return ExitCode::from(1);
        }
    };
    let no_revocations = RevocationList::empty("audit");
    let mut failures = 0usize;
    for e in &entries {
        let check = || -> Result<(), String> {
            let chain: Vec<Vec<u8>> =
                e.chain.iter().map(|c| b64_decode(c)).collect::<Option<_>>().ok_or("chain encoding")?;
            let at = parse_rfc3339(&e.issued_at).ok_or("issued_at")?;
            let vc = verify_chain(&chain, &anchors, &no_revocations, at).map_err(|err| err.to_string())?;
            let authority = decode_address(&e.authority).ok_or("authority")?;
            let nonce: [u8; 16] = decode_hex_exact(&e.nonce).ok_or("nonce")?;
            if vc.key_description.attestation_challenge != challenge(&authority, &nonce) {
                return Err("challenge does not match authority and nonce".into());
            }
            let leaf = vc.leaf_p256_compressed.ok_or("leaf is not P-256")?;
            if hex::encode(leaf) != e.p256_pubkey {
                return Err("leaf key differs from the vouched key".into());
            }
            Ok(())
        };
        match check() {
            Ok(()) => println!("ok    #{:<6} level={} authority={}", e.index, e.level, e.authority),
            Err(why) => {
                failures = failures.saturating_add(1);
                println!("FAIL  #{:<6} {why}", e.index);
            }
        }
    }
    println!("{} entries, {} failures; hash chain and voucher signatures verified", entries.len(), failures);
    if failures == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}

fn healthcheck(addr: &str) -> ExitCode {
    let Ok(addr) = addr.parse::<SocketAddr>() else {
        return ExitCode::from(2);
    };
    let timeout = std::time::Duration::from_secs(3);
    let Ok(mut stream) = std::net::TcpStream::connect_timeout(&addr, timeout) else {
        return ExitCode::from(1);
    };
    let _ = stream.set_read_timeout(Some(timeout));
    let req = format!("GET /healthz HTTP/1.1\r\nHost: {addr}\r\nConnection: close\r\n\r\n");
    if stream.write_all(req.as_bytes()).is_err() {
        return ExitCode::from(1);
    }
    let mut buf = [0u8; 16];
    match stream.read(&mut buf) {
        Ok(n) if buf.get(..n).is_some_and(|b| b.starts_with(b"HTTP/1.1 200")) => ExitCode::SUCCESS,
        _ => ExitCode::from(1),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let arg = |i: usize| args.get(i).map(String::as_str);
    match (arg(0), arg(1)) {
        (None | Some("serve"), _) => match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
            Ok(rt) => rt.block_on(serve()),
            Err(e) => {
                eprintln!("runtime: {e}");
                ExitCode::from(1)
            }
        },
        (Some("keygen"), Some(p)) => keygen(Path::new(p)),
        (Some("pubkey"), Some(p)) => pubkey(Path::new(p)),
        (Some("audit-log"), Some(p)) => audit_log(Path::new(p)),
        (Some("healthcheck"), addr) => healthcheck(addr.unwrap_or("127.0.0.1:8080")),
        _ => {
            eprintln!(
                "usage: hd-registrar [serve | keygen <path> | pubkey <path> | audit-log <path> | healthcheck [addr]]"
            );
            ExitCode::from(2)
        }
    }
}
