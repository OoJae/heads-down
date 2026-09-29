//! Sign-In-With-Solana message text (the CAIP-122 profile used by Solana wallets).
//!
//! The byte format is the one produced by `createSignInMessageText` in
//! `@solana/wallet-standard-util` and by MWA's `SignInWithSolana.Payload.prepareMessage()`
//! (clientlib 2.0.3, verified by disassembly):
//!
//! ```text
//! ${domain} wants you to sign in with your Solana account:
//! ${address}
//!
//! ${statement}                      <- optional, with its blank line
//!
//! URI: ${uri}                       <- every field optional, in exactly this order
//! Version: ${version}
//! Chain ID: ${chainId}
//! Nonce: ${nonce}
//! Issued At: ${issuedAt}
//! Expiration Time: ${expirationTime}
//! Not Before: ${notBefore}
//! Request ID: ${requestId}
//! Resources:
//! - ${resources[0]}
//! ```
//!
//! Parsing is **canonical**: after parsing, the message is re-serialized and must equal the
//! input byte for byte. So there is exactly one accepted spelling of any sign-in, and the
//! fields the server checks are exactly the fields the wallet displayed and signed. CR, NUL,
//! duplicated or reordered fields, trailing newlines and empty values are all rejected.

pub const HEADER_SUFFIX: &str = " wants you to sign in with your Solana account:";

const URI: &str = "URI: ";
const VERSION: &str = "Version: ";
const CHAIN_ID: &str = "Chain ID: ";
const NONCE: &str = "Nonce: ";
const ISSUED_AT: &str = "Issued At: ";
const EXPIRATION_TIME: &str = "Expiration Time: ";
const NOT_BEFORE: &str = "Not Before: ";
const REQUEST_ID: &str = "Request ID: ";
const RESOURCES: &str = "Resources:";
const RESOURCE_ITEM: &str = "- ";

/// Field prefixes in their only permitted order.
const FIELD_ORDER: [&str; 8] = [URI, VERSION, CHAIN_ID, NONCE, ISSUED_AT, EXPIRATION_TIME, NOT_BEFORE, REQUEST_ID];

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SiwsMessage {
    pub domain: String,
    pub address: String,
    pub statement: Option<String>,
    pub uri: Option<String>,
    pub version: Option<String>,
    pub chain_id: Option<String>,
    pub nonce: Option<String>,
    pub issued_at: Option<String>,
    pub expiration_time: Option<String>,
    pub not_before: Option<String>,
    pub request_id: Option<String>,
    pub resources: Option<Vec<String>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum ParseError {
    #[error("message contains a forbidden control character")]
    ForbiddenCharacter,
    #[error("missing or malformed header line")]
    BadHeader,
    #[error("missing address line")]
    MissingAddress,
    #[error("unexpected line")]
    UnexpectedLine,
    #[error("field duplicated or out of order")]
    FieldOrder,
    #[error("empty field value")]
    EmptyValue,
    #[error("message is not in canonical form")]
    NotCanonical,
}

fn is_field_line(line: &str) -> bool {
    line == RESOURCES || FIELD_ORDER.iter().any(|p| line.starts_with(p))
}

fn non_empty(v: &str) -> Result<String, ParseError> {
    if v.is_empty() {
        Err(ParseError::EmptyValue)
    } else {
        Ok(v.to_owned())
    }
}

impl SiwsMessage {
    pub fn parse(text: &str) -> Result<Self, ParseError> {
        // Only '\n' separates lines. Any other C0 control (CR, NUL, TAB, ESC...) or DEL could make
        // what a wallet shows differ from what is signed; reject them all.
        if text.chars().any(|c| (c.is_control() && c != '\n') || c == '\u{2028}' || c == '\u{2029}') {
            return Err(ParseError::ForbiddenCharacter);
        }
        let lines: Vec<&str> = text.split('\n').collect();
        let (header, rest) = lines.split_first().ok_or(ParseError::BadHeader)?;
        let domain = header.strip_suffix(HEADER_SUFFIX).ok_or(ParseError::BadHeader)?;
        if domain.is_empty() || domain.chars().any(char::is_whitespace) {
            return Err(ParseError::BadHeader);
        }
        let (address, rest) = rest.split_first().ok_or(ParseError::MissingAddress)?;
        if address.is_empty() {
            return Err(ParseError::MissingAddress);
        }

        let mut msg = SiwsMessage { domain: domain.to_owned(), address: (*address).to_owned(), ..Default::default() };

        // After the address: nothing, or a blank line followed by a statement and/or fields.
        let fields: &[&str] = match rest.split_first() {
            None => &[],
            Some((&"", after_blank)) => match after_blank.split_first() {
                None => return Err(ParseError::UnexpectedLine),
                Some((first, _)) if is_field_line(first) => after_blank,
                Some((statement, after_statement)) => {
                    msg.statement = Some(non_empty(statement)?);
                    match after_statement.split_first() {
                        None => &[],
                        Some((&"", fields)) if !fields.is_empty() => fields,
                        Some(_) => return Err(ParseError::UnexpectedLine),
                    }
                }
            },
            Some(_) => return Err(ParseError::UnexpectedLine),
        };

        let mut next_allowed = 0usize;
        let mut iter = fields.iter();
        while let Some(line) = iter.next() {
            if *line == RESOURCES {
                let mut resources = Vec::new();
                for item in iter.by_ref() {
                    let value = item.strip_prefix(RESOURCE_ITEM).ok_or(ParseError::UnexpectedLine)?;
                    resources.push(non_empty(value)?);
                }
                msg.resources = Some(resources);
                break;
            }
            let (idx, prefix) = FIELD_ORDER
                .iter()
                .enumerate()
                .find(|(_, p)| line.starts_with(**p))
                .ok_or(ParseError::UnexpectedLine)?;
            if idx < next_allowed {
                return Err(ParseError::FieldOrder);
            }
            next_allowed = idx.checked_add(1).ok_or(ParseError::FieldOrder)?;
            let value = non_empty(line.get(prefix.len()..).ok_or(ParseError::EmptyValue)?)?;
            let slot = match idx {
                0 => &mut msg.uri,
                1 => &mut msg.version,
                2 => &mut msg.chain_id,
                3 => &mut msg.nonce,
                4 => &mut msg.issued_at,
                5 => &mut msg.expiration_time,
                6 => &mut msg.not_before,
                _ => &mut msg.request_id,
            };
            *slot = Some(value);
        }

        if msg.to_text() != text {
            return Err(ParseError::NotCanonical);
        }
        Ok(msg)
    }

    /// Canonical serialization (identical to `createSignInMessageText`).
    pub fn to_text(&self) -> String {
        let mut out = format!("{}{}\n{}", self.domain, HEADER_SUFFIX, self.address);
        if let Some(statement) = self.statement.as_deref().filter(|s| !s.is_empty()) {
            out.push_str("\n\n");
            out.push_str(statement);
        }
        let mut fields: Vec<String> = Vec::new();
        let pairs = [
            (URI, &self.uri),
            (VERSION, &self.version),
            (CHAIN_ID, &self.chain_id),
            (NONCE, &self.nonce),
            (ISSUED_AT, &self.issued_at),
            (EXPIRATION_TIME, &self.expiration_time),
            (NOT_BEFORE, &self.not_before),
            (REQUEST_ID, &self.request_id),
        ];
        for (prefix, value) in pairs {
            if let Some(v) = value {
                fields.push(format!("{prefix}{v}"));
            }
        }
        if let Some(resources) = &self.resources {
            fields.push(RESOURCES.to_owned());
            for r in resources {
                fields.push(format!("{RESOURCE_ITEM}{r}"));
            }
        }
        if !fields.is_empty() {
            out.push_str("\n\n");
            out.push_str(&fields.join("\n"));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn full() -> SiwsMessage {
        SiwsMessage {
            domain: "headsdown.xyz".into(),
            address: "9aE476sH92Vz7DMPyq5WLPkrKWivxeuTKEFKd2sZZcde".into(),
            statement: Some("Clock in to Heads Down.".into()),
            uri: Some("https://headsdown.xyz".into()),
            version: Some("1".into()),
            chain_id: Some("solana:mainnet".into()),
            nonce: Some("0123456789abcdef0123456789abcdef".into()),
            issued_at: Some("2026-09-29T12:00:00Z".into()),
            expiration_time: Some("2026-09-29T12:10:00Z".into()),
            not_before: None,
            request_id: None,
            resources: None,
        }
    }

    #[test]
    fn golden_text_matches_wallet_standard() {
        let expected = "headsdown.xyz wants you to sign in with your Solana account:\n\
9aE476sH92Vz7DMPyq5WLPkrKWivxeuTKEFKd2sZZcde\n\
\n\
Clock in to Heads Down.\n\
\n\
URI: https://headsdown.xyz\n\
Version: 1\n\
Chain ID: solana:mainnet\n\
Nonce: 0123456789abcdef0123456789abcdef\n\
Issued At: 2026-09-29T12:00:00Z\n\
Expiration Time: 2026-09-29T12:10:00Z";
        assert_eq!(full().to_text(), expected);
        assert_eq!(SiwsMessage::parse(expected).unwrap(), full());
    }

    #[test]
    fn round_trips_every_shape() {
        let mut m = full();
        m.not_before = Some("2026-09-29T12:00:00Z".into());
        m.request_id = Some("req-1".into());
        m.resources = Some(vec!["https://headsdown.xyz/tos".into(), "ipfs://x".into()]);
        assert_eq!(SiwsMessage::parse(&m.to_text()).unwrap(), m);

        let mut no_statement = full();
        no_statement.statement = None;
        assert_eq!(SiwsMessage::parse(&no_statement.to_text()).unwrap(), no_statement);

        let minimal = SiwsMessage { domain: "a.b".into(), address: "addr".into(), ..Default::default() };
        assert_eq!(SiwsMessage::parse(&minimal.to_text()).unwrap(), minimal);

        let statement_only = SiwsMessage { statement: Some("hi".into()), ..minimal.clone() };
        assert_eq!(SiwsMessage::parse(&statement_only.to_text()).unwrap(), statement_only);

        let empty_resources = SiwsMessage { resources: Some(vec![]), ..minimal };
        assert_eq!(SiwsMessage::parse(&empty_resources.to_text()).unwrap(), empty_resources);
    }

    #[test]
    fn rejects_non_canonical_and_malformed() {
        let good = full().to_text();
        let cases: Vec<(String, ParseError)> = vec![
            (good.replace('\n', "\r\n"), ParseError::ForbiddenCharacter),
            (format!("{good}\n"), ParseError::UnexpectedLine),
            (format!("{good}\u{0}"), ParseError::ForbiddenCharacter),
            (good.replace("Nonce: ", "Nonce:\u{1b}[8m "), ParseError::ForbiddenCharacter),
            (good.replace("headsdown.xyz wants", "evil.xyz\u{2028}headsdown.xyz wants"), ParseError::ForbiddenCharacter),
            (good.replace(" wants you to sign in", " wants to sign in"), ParseError::BadHeader),
            (good.replace("headsdown.xyz wants", " wants"), ParseError::BadHeader),
            (good.replace("headsdown.xyz wants", "heads down.xyz wants"), ParseError::BadHeader),
            // Swap two fields.
            (
                good.replace("Version: 1\nChain ID: solana:mainnet", "Chain ID: solana:mainnet\nVersion: 1"),
                ParseError::FieldOrder,
            ),
            // Duplicate a field.
            (good.replace("Version: 1\n", "Version: 1\nVersion: 1\n"), ParseError::FieldOrder),
            // Unknown field.
            (good.replace("Version: 1\n", "Version: 1\nColor: red\n"), ParseError::UnexpectedLine),
            // Empty value.
            (good.replace("Version: 1", "Version: "), ParseError::EmptyValue),
            // Missing blank line before fields.
            (good.replace("Heads Down.\n\nURI", "Heads Down.\nURI"), ParseError::UnexpectedLine),
            // Double blank line.
            (good.replace("Heads Down.\n\nURI", "Heads Down.\n\n\nURI"), ParseError::UnexpectedLine),
            // Multi-line statement.
            (good.replace("Clock in", "Clock\nin"), ParseError::UnexpectedLine),
        ];
        for (text, err) in cases {
            assert_eq!(SiwsMessage::parse(&text), Err(err), "input: {text:?}");
        }
        assert_eq!(SiwsMessage::parse(""), Err(ParseError::BadHeader));
        assert_eq!(
            SiwsMessage::parse("headsdown.xyz wants you to sign in with your Solana account:"),
            Err(ParseError::MissingAddress)
        );
        assert_eq!(
            SiwsMessage::parse("headsdown.xyz wants you to sign in with your Solana account:\n"),
            Err(ParseError::MissingAddress)
        );
        let bad_resource = format!("{good}\nResources:\n* nope");
        assert_eq!(SiwsMessage::parse(&bad_resource), Err(ParseError::UnexpectedLine));
    }
}
