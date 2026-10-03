//! Keeping secrets out of logs.
//!
//! The RPC and WebSocket URLs are secrets (a Helius key lives in their query string), and
//! the crank has three layers so that key never reaches a log line:
//!
//! 1. Nothing formats a URL: [`redact_url`] keeps only scheme and host, `Config`'s `Debug` and
//!    its startup log use the redacted view, and transport errors are stripped of their URL
//!    ([`scrub`]).
//! 2. The keys themselves are **registered** at start ([`register_secrets`], from the
//!    effective config), and [`redact_secrets`] replaces any occurrence in a string.
//! 3. The log writer ([`RedactingStderr`]) runs every formatted log line through
//!    [`redact_secrets`] before it reaches stderr, so even a future `{:?}` of something that
//!    holds a URL prints `<redacted>` instead of the key.

use std::borrow::Cow;
use std::io::Write;
use std::sync::{OnceLock, RwLock};

/// What a secret is replaced with.
pub const REDACTED: &str = "<redacted>";
/// Shorter strings are not registered: replacing them would mangle ordinary log text.
pub const MIN_SECRET_LEN: usize = 6;

fn registry() -> &'static RwLock<Vec<String>> {
    static SECRETS: OnceLock<RwLock<Vec<String>>> = OnceLock::new();
    SECRETS.get_or_init(|| RwLock::new(Vec::new()))
}

/// Register strings that must never be logged (API keys). Idempotent; strings shorter than
/// [`MIN_SECRET_LEN`] are ignored.
pub fn register_secrets(secrets: impl IntoIterator<Item = String>) {
    let mut reg = match registry().write() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    for s in secrets {
        if s.len() >= MIN_SECRET_LEN && !reg.contains(&s) {
            reg.push(s);
        }
    }
    // Longest first, so a secret that contains another is replaced whole.
    reg.sort_by_key(|s| std::cmp::Reverse(s.len()));
}

/// `s` with every registered secret replaced by `<redacted>`.
pub fn redact_secrets(s: &str) -> Cow<'_, str> {
    let reg = match registry().read() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if !reg.iter().any(|k| s.contains(k.as_str())) {
        return Cow::Borrowed(s);
    }
    let mut out = s.to_string();
    for k in reg.iter() {
        if out.contains(k.as_str()) {
            out = out.replace(k.as_str(), REDACTED);
        }
    }
    Cow::Owned(out)
}

/// Keep only scheme and host: providers put keys in the query (`?api-key=`), the path
/// (`/<token>/`) or userinfo (`user:pass@`), so everything else is replaced.
pub fn redact_url(url: &str) -> String {
    let Some((scheme, rest)) = url.split_once("://") else {
        return REDACTED.to_string();
    };
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let (authority, tail) = rest.split_at(end);
    let host = match authority.rsplit_once('@') {
        Some((_, h)) => format!("{REDACTED}@{h}"),
        None => authority.to_string(),
    };
    if tail.is_empty() || tail == "/" {
        format!("{scheme}://{host}{tail}")
    } else {
        format!("{scheme}://{host}/{REDACTED}")
    }
}

/// Remove `secret_url`, any `api-key=` value and every registered secret from an error
/// message before logging it: some transport errors echo the request URL.
pub fn scrub(message: &str, secret_url: &str) -> String {
    let mut s = if secret_url.is_empty() { message.to_string() } else { message.replace(secret_url, &redact_url(secret_url)) };
    let mut from = 0usize;
    while let Some(i) = s[from..].to_ascii_lowercase().find("api-key=") {
        let start = from + i + "api-key=".len();
        let end = s[start..].find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '_')).map_or(s.len(), |j| start + j);
        s.replace_range(start..end, REDACTED);
        from = start + REDACTED.len();
        if from >= s.len() {
            break;
        }
    }
    redact_secrets(&s).into_owned()
}

/// A stderr writer for the log subscriber: each event is buffered and written once, with every
/// registered secret replaced. `make_writer` hands out one per event.
#[derive(Clone, Copy, Debug, Default)]
pub struct RedactingStderr;

/// One event's buffer (see [`RedactingStderr`]).
#[derive(Debug, Default)]
pub struct RedactingLine {
    buf: Vec<u8>,
}

impl RedactingLine {
    fn emit(&mut self, out: &mut dyn Write) -> std::io::Result<()> {
        if self.buf.is_empty() {
            return Ok(());
        }
        let text = String::from_utf8_lossy(&self.buf);
        let r = out.write_all(redact_secrets(&text).as_bytes());
        self.buf.clear();
        r
    }

    /// The redacted text this line would write (tests).
    pub fn redacted(&self) -> String {
        redact_secrets(&String::from_utf8_lossy(&self.buf)).into_owned()
    }
}

impl Write for RedactingLine {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.buf.extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.emit(&mut std::io::stderr().lock())
    }
}

impl Drop for RedactingLine {
    fn drop(&mut self) {
        let _ = self.emit(&mut std::io::stderr().lock());
    }
}

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for RedactingStderr {
    type Writer = RedactingLine;

    fn make_writer(&'a self) -> Self::Writer {
        RedactingLine::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redaction_hides_keys() {
        assert_eq!(redact_url("https://mainnet.helius-rpc.com/?api-key=SECRET"), "https://mainnet.helius-rpc.com/<redacted>");
        assert_eq!(redact_url("https://x.quiknode.pro/SECRET/"), "https://x.quiknode.pro/<redacted>");
        assert_eq!(redact_url("wss://user:pw@host.example/ws"), "wss://<redacted>@host.example/<redacted>");
        assert_eq!(redact_url("http://127.0.0.1:8899"), "http://127.0.0.1:8899");
        assert_eq!(redact_url("https://api.mainnet-beta.solana.com/"), "https://api.mainnet-beta.solana.com/");
        assert_eq!(redact_url("garbage"), "<redacted>");
        let url = "wss://mainnet.helius-rpc.com/?api-key=abc-123";
        let msg = format!("connect to {url} failed; retry api-key=abc-123&x=1 and API-KEY=zzz9");
        let s = scrub(&msg, url);
        assert!(!s.contains("abc-123") && !s.contains("zzz9"), "{s}");
        assert!(s.contains("mainnet.helius-rpc.com"));
        assert_eq!(scrub("nothing secret here", ""), "nothing secret here");
        assert_eq!(scrub("ends with api-key=", ""), "ends with api-key=<redacted>");
    }

    #[test]
    fn registered_secrets_never_reach_a_log_line() {
        // Unique strings: the registry is process-wide and other tests run alongside.
        let key = "t3st-0nly-helius-key-7f3a9c";
        let other = "t3st-0nly-s3cond-key";
        register_secrets([key.to_string(), other.to_string(), "abc".to_string()]);
        register_secrets([key.to_string()]);
        let line = format!("ws connect failed url=wss://mainnet.helius-rpc.com/?api-key={key} again {key} and {other}");
        let out = redact_secrets(&line);
        assert!(!out.contains(key) && !out.contains(other), "{out}");
        assert_eq!(out.matches(REDACTED).count(), 3);
        assert!(matches!(redact_secrets("plain abc text"), Cow::Borrowed(_)), "short strings are never registered");
        // The log writer: whatever is formatted into an event is redacted before stderr.
        let mut w = RedactingLine::default();
        write!(w, "{{\"level\":\"WARN\",\"error\":\"HTTP error: 401 for url (https://mainnet.helius-rpc.com/?api-key=").unwrap();
        write!(w, "{key})\"}}").unwrap(); // even when the key arrives in a second write
        let text = w.redacted();
        assert!(!text.contains(key), "{text}");
        assert!(text.contains("api-key=<redacted>"), "{text}");
        let mut sink = Vec::new();
        w.emit(&mut sink).unwrap();
        assert_eq!(String::from_utf8(sink).unwrap(), text);
        assert!(w.buf.is_empty(), "written once");
        // scrub() applies the registry too (an error that quotes the key without `api-key=`).
        assert!(!scrub(&format!("bad token {key}"), "").contains(key));
    }

    #[test]
    fn the_subscriber_writer_redacts_real_events() {
        use tracing_subscriber::fmt::MakeWriter;
        let key = "t3st-0nly-subscriber-key-51c2";
        register_secrets([key.to_string()]);
        let mut w = RedactingStderr.make_writer();
        writeln!(w, "starting hd-crank ws=wss://atlas-mainnet.helius-rpc.com/?api-key={key}").unwrap();
        assert!(!w.redacted().contains(key));
        assert!(w.redacted().contains("atlas-mainnet.helius-rpc.com"));
        w.buf.clear(); // keep the test's stderr clean
    }
}
