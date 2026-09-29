//! Android Key Attestation `KeyDescription` extension (OID 1.3.6.1.4.1.11129.2.1.17).
//!
//! Schema: <https://source.android.com/docs/security/features/keystore/attestation#schema>
//!
//! ```text
//! KeyDescription ::= SEQUENCE {
//!     attestationVersion         INTEGER,
//!     attestationSecurityLevel   SecurityLevel,          -- ENUMERATED { Software(0), TrustedEnvironment(1), StrongBox(2) }
//!     keyMintVersion             INTEGER,
//!     keyMintSecurityLevel       SecurityLevel,
//!     attestationChallenge       OCTET_STRING,
//!     uniqueId                   OCTET_STRING,
//!     softwareEnforced           AuthorizationList,
//!     hardwareEnforced           AuthorizationList,
//! }
//! AuthorizationList ::= SEQUENCE { [n] EXPLICIT <type> OPTIONAL, ... }   -- n = KeyMint tag number
//! RootOfTrust ::= SEQUENCE { verifiedBootKey OCTET_STRING, deviceLocked BOOLEAN,
//!                            verifiedBootState ENUMERATED, verifiedBootHash OCTET_STRING (v3+) }
//! AttestationApplicationId ::= SEQUENCE { package_infos SET OF SEQUENCE { package_name OCTET_STRING,
//!                            version INTEGER }, signature_digests SET OF OCTET_STRING }
//! ```
//!
//! Parsing uses `asn1-rs` in DER mode for every TLV header (definite lengths only). It is
//! strict where ambiguity could matter and lenient only where Google's reference verifier
//! (`android/keyattestation`, `Extension.kt`) documents real-device quirks:
//! - **Strict:** exact element counts, exact universal tags, minimal INTEGER/ENUMERATED
//!   encodings, known enum values only, no trailing bytes anywhere, **duplicate
//!   AuthorizationList tags are an error** (Google silently keeps the last one; we refuse to
//!   guess), size limits on every list.
//! - **Lenient (as Google):** AuthorizationList tags out of ascending order (recorded in
//!   [`AuthorizationList::tags_ordered`]); a BOOLEAN `deviceLocked` encoded as a non-`0xFF`
//!   true value (recorded in [`RootOfTrust::device_locked_non_der`]). Both occur on shipping
//!   devices; see `testdata/chains/quirk_*.pem`.
//!
//! Tags this verifier does not use are validated structurally (one explicit element) and
//! skipped, so new KeyMint tags do not break parsing.

use x509_parser::asn1_rs::{Any, Class, FromDer, Tag};

pub const KEY_DESCRIPTION_OID: &str = "1.3.6.1.4.1.11129.2.1.17";

const MAX_AUTH_ENTRIES: usize = 128;
const MAX_SET_ITEMS: usize = 32;
const MAX_PACKAGES: usize = 32;
const MAX_DIGESTS: usize = 32;
const MAX_CHALLENGE: usize = 128;

// KeyMint tag numbers (hardware/interfaces/security/keymint/aidl/.../Tag.aidl, low 28 bits).
pub mod tag {
    pub const PURPOSE: u32 = 1;
    pub const ALGORITHM: u32 = 2;
    pub const KEY_SIZE: u32 = 3;
    pub const DIGEST: u32 = 5;
    pub const EC_CURVE: u32 = 10;
    pub const NO_AUTH_REQUIRED: u32 = 503;
    pub const ORIGIN: u32 = 702;
    pub const ROOT_OF_TRUST: u32 = 704;
    pub const OS_VERSION: u32 = 705;
    pub const OS_PATCH_LEVEL: u32 = 706;
    pub const ATTESTATION_APPLICATION_ID: u32 = 709;
    pub const ATTESTATION_ID_FIRST: u32 = 710; // brand..model: 710..=717
    pub const ATTESTATION_ID_LAST: u32 = 717;
    pub const VENDOR_PATCH_LEVEL: u32 = 718;
    pub const BOOT_PATCH_LEVEL: u32 = 719;
}

// KeyMint enum values.
pub const PURPOSE_SIGN: i64 = 2;
pub const PURPOSE_VERIFY: i64 = 3;
pub const ALGORITHM_EC: i64 = 3;
pub const EC_CURVE_P256: i64 = 1;
pub const ORIGIN_GENERATED: i64 = 0;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, serde::Serialize)]
pub enum SecurityLevel {
    Software = 0,
    TrustedEnvironment = 1,
    StrongBox = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
pub enum VerifiedBootState {
    Verified = 0,
    SelfSigned = 1,
    Unverified = 2,
    Failed = 3,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RootOfTrust {
    pub verified_boot_key: Vec<u8>,
    pub device_locked: bool,
    /// `deviceLocked` used a BER-only encoding of TRUE (a non-zero byte other than 0xFF).
    pub device_locked_non_der: bool,
    pub verified_boot_state: VerifiedBootState,
    pub verified_boot_hash: Option<Vec<u8>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackageInfo {
    pub name: String,
    pub version: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AttestationApplicationId {
    pub packages: Vec<PackageInfo>,
    /// SHA-256 digests of the APK signing certificates.
    pub signature_digests: Vec<Vec<u8>>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AuthorizationList {
    pub purposes: Option<Vec<i64>>,
    pub algorithm: Option<i64>,
    pub key_size: Option<i64>,
    pub digests: Option<Vec<i64>>,
    pub ec_curve: Option<i64>,
    pub no_auth_required: bool,
    pub origin: Option<i64>,
    pub root_of_trust: Option<RootOfTrust>,
    pub os_version: Option<i64>,
    pub os_patch_level: Option<i64>,
    pub attestation_application_id: Option<AttestationApplicationId>,
    pub vendor_patch_level: Option<i64>,
    pub boot_patch_level: Option<i64>,
    /// True if any device-identifier attestation tag (710..=717) is present.
    pub has_device_ids: bool,
    /// Every tag seen, in encounter order.
    pub tags: Vec<u32>,
    /// False when the tags were not in strictly ascending order (a known device quirk).
    pub tags_ordered: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyDescription {
    pub attestation_version: i64,
    pub attestation_security_level: SecurityLevel,
    pub keymint_version: i64,
    pub keymint_security_level: SecurityLevel,
    pub attestation_challenge: Vec<u8>,
    pub unique_id: Vec<u8>,
    pub software_enforced: AuthorizationList,
    pub hardware_enforced: AuthorizationList,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum KeyDescriptionError {
    #[error("DER decoding failed at {0}")]
    Der(&'static str),
    #[error("unexpected ASN.1 type at {0}")]
    UnexpectedType(&'static str),
    #[error("trailing bytes after {0}")]
    Trailing(&'static str),
    #[error("wrong number of elements in {0}")]
    ElementCount(&'static str),
    #[error("integer out of range or not minimally encoded at {0}")]
    BadInteger(&'static str),
    #[error("unknown enum value at {0}")]
    UnknownEnum(&'static str),
    #[error("duplicate AuthorizationList tag {0}")]
    DuplicateTag(u32),
    #[error("too many elements in {0}")]
    TooMany(&'static str),
    #[error("package name is not UTF-8")]
    PackageName,
}

type Result<T> = std::result::Result<T, KeyDescriptionError>;
use KeyDescriptionError as E;

/// Parses one DER element from `input`, returning it and the remaining bytes.
fn next<'a>(input: &'a [u8], ctx: &'static str) -> Result<(Any<'a>, &'a [u8])> {
    let (rest, any) = Any::from_der(input).map_err(|_| E::Der(ctx))?;
    Ok((any, rest))
}

/// Parses exactly one DER element that must fill `input` completely.
fn exactly_one<'a>(input: &'a [u8], ctx: &'static str) -> Result<Any<'a>> {
    let (any, rest) = next(input, ctx)?;
    if !rest.is_empty() {
        return Err(E::Trailing(ctx));
    }
    Ok(any)
}

fn expect_universal(any: &Any, tag: Tag, constructed: bool, ctx: &'static str) -> Result<()> {
    if any.class() != Class::Universal || any.tag() != tag || any.header.is_constructed() != constructed {
        return Err(E::UnexpectedType(ctx));
    }
    Ok(())
}

/// The children of a SEQUENCE or SET, bounded by `max`.
fn children<'a>(any: &Any<'a>, tag: Tag, max: usize, ctx: &'static str) -> Result<Vec<Any<'a>>> {
    expect_universal(any, tag, true, ctx)?;
    let mut out = Vec::new();
    let mut rest = any.data;
    while !rest.is_empty() {
        if out.len() >= max {
            return Err(E::TooMany(ctx));
        }
        let (item, r) = next(rest, ctx)?;
        out.push(item);
        rest = r;
    }
    Ok(out)
}

/// Two's-complement big-endian integer content, minimal encoding, at most 64 bits.
fn decode_i64(content: &[u8], ctx: &'static str) -> Result<i64> {
    let (first, tail) = content.split_first().ok_or(E::BadInteger(ctx))?;
    if let Some(second) = tail.first() {
        // X.690 8.3.2: the first nine bits must not be all zeros or all ones.
        if (*first == 0x00 && second & 0x80 == 0) || (*first == 0xff && second & 0x80 != 0) {
            return Err(E::BadInteger(ctx));
        }
    }
    if content.len() > 8 {
        return Err(E::BadInteger(ctx));
    }
    let mut v: i64 = if first & 0x80 != 0 { -1 } else { 0 };
    for b in content {
        v = v.checked_shl(8).ok_or(E::BadInteger(ctx))? | i64::from(*b);
    }
    Ok(v)
}

fn integer(any: &Any, ctx: &'static str) -> Result<i64> {
    expect_universal(any, Tag::Integer, false, ctx)?;
    decode_i64(any.data, ctx)
}

fn enumerated(any: &Any, ctx: &'static str) -> Result<i64> {
    expect_universal(any, Tag::Enumerated, false, ctx)?;
    decode_i64(any.data, ctx)
}

fn octets<'a>(any: &Any<'a>, ctx: &'static str) -> Result<&'a [u8]> {
    expect_universal(any, Tag::OctetString, false, ctx)?;
    Ok(any.data)
}

/// BOOLEAN, accepting any non-zero content byte as TRUE (BER), and reporting whether the
/// encoding was not DER (`0xFF`).
fn boolean_lenient(any: &Any, ctx: &'static str) -> Result<(bool, bool)> {
    expect_universal(any, Tag::Boolean, false, ctx)?;
    match any.data {
        [0x00] => Ok((false, false)),
        [0xff] => Ok((true, false)),
        [_] => Ok((true, true)),
        _ => Err(E::Der(ctx)),
    }
}

fn null(any: &Any, ctx: &'static str) -> Result<()> {
    expect_universal(any, Tag::Null, false, ctx)?;
    if any.data.is_empty() {
        Ok(())
    } else {
        Err(E::Der(ctx))
    }
}

fn security_level(any: &Any, ctx: &'static str) -> Result<SecurityLevel> {
    match enumerated(any, ctx)? {
        0 => Ok(SecurityLevel::Software),
        1 => Ok(SecurityLevel::TrustedEnvironment),
        2 => Ok(SecurityLevel::StrongBox),
        _ => Err(E::UnknownEnum(ctx)),
    }
}

fn int_set(any: &Any, ctx: &'static str) -> Result<Vec<i64>> {
    children(any, Tag::Set, MAX_SET_ITEMS, ctx)?.iter().map(|i| integer(i, ctx)).collect()
}

fn root_of_trust(any: &Any) -> Result<RootOfTrust> {
    const CTX: &str = "RootOfTrust";
    let items = children(any, Tag::Sequence, 4, CTX)?;
    let (key, locked, state, hash) = match items.as_slice() {
        [k, l, s] => (k, l, s, None),
        [k, l, s, h] => (k, l, s, Some(h)),
        _ => return Err(E::ElementCount(CTX)),
    };
    let (device_locked, device_locked_non_der) = boolean_lenient(locked, "RootOfTrust.deviceLocked")?;
    let verified_boot_state = match enumerated(state, "RootOfTrust.verifiedBootState")? {
        0 => VerifiedBootState::Verified,
        1 => VerifiedBootState::SelfSigned,
        2 => VerifiedBootState::Unverified,
        3 => VerifiedBootState::Failed,
        _ => return Err(E::UnknownEnum("RootOfTrust.verifiedBootState")),
    };
    Ok(RootOfTrust {
        verified_boot_key: octets(key, "RootOfTrust.verifiedBootKey")?.to_vec(),
        device_locked,
        device_locked_non_der,
        verified_boot_state,
        verified_boot_hash: hash.map(|h| octets(h, "RootOfTrust.verifiedBootHash").map(<[u8]>::to_vec)).transpose()?,
    })
}

/// Parses the DER inside the `attestationApplicationId` OCTET STRING.
pub fn attestation_application_id(der: &[u8]) -> Result<AttestationApplicationId> {
    const CTX: &str = "AttestationApplicationId";
    let top = exactly_one(der, CTX)?;
    let items = children(&top, Tag::Sequence, 2, CTX)?;
    let [package_set, digest_set] = items.as_slice() else {
        return Err(E::ElementCount(CTX));
    };
    let mut packages = Vec::new();
    for info in children(package_set, Tag::Set, MAX_PACKAGES, "AttestationApplicationId.package_infos")? {
        let fields = children(&info, Tag::Sequence, 2, "AttestationPackageInfo")?;
        let [name, version] = fields.as_slice() else {
            return Err(E::ElementCount("AttestationPackageInfo"));
        };
        let name = String::from_utf8(octets(name, "AttestationPackageInfo.package_name")?.to_vec())
            .map_err(|_| E::PackageName)?;
        packages.push(PackageInfo { name, version: integer(version, "AttestationPackageInfo.version")? });
    }
    let signature_digests = children(digest_set, Tag::Set, MAX_DIGESTS, "AttestationApplicationId.signature_digests")?
        .iter()
        .map(|d| octets(d, "AttestationApplicationId.signature_digest").map(<[u8]>::to_vec))
        .collect::<Result<Vec<_>>>()?;
    Ok(AttestationApplicationId { packages, signature_digests })
}

fn authorization_list(any: &Any, ctx: &'static str) -> Result<AuthorizationList> {
    let mut list = AuthorizationList { tags_ordered: true, ..Default::default() };
    for entry in children(any, Tag::Sequence, MAX_AUTH_ENTRIES, ctx)? {
        if entry.class() != Class::ContextSpecific || !entry.header.is_constructed() {
            return Err(E::UnexpectedType(ctx));
        }
        let t = entry.tag().0;
        if list.tags.contains(&t) {
            return Err(E::DuplicateTag(t));
        }
        if list.tags.last().is_some_and(|prev| *prev > t) {
            list.tags_ordered = false;
        }
        list.tags.push(t);
        // [t] EXPLICIT: exactly one inner element.
        let inner = exactly_one(entry.data, ctx)?;
        match t {
            tag::PURPOSE => list.purposes = Some(int_set(&inner, "purpose")?),
            tag::ALGORITHM => list.algorithm = Some(integer(&inner, "algorithm")?),
            tag::KEY_SIZE => list.key_size = Some(integer(&inner, "keySize")?),
            tag::DIGEST => list.digests = Some(int_set(&inner, "digest")?),
            tag::EC_CURVE => list.ec_curve = Some(integer(&inner, "ecCurve")?),
            tag::NO_AUTH_REQUIRED => {
                null(&inner, "noAuthRequired")?;
                list.no_auth_required = true;
            }
            tag::ORIGIN => list.origin = Some(integer(&inner, "origin")?),
            tag::ROOT_OF_TRUST => list.root_of_trust = Some(root_of_trust(&inner)?),
            tag::OS_VERSION => list.os_version = Some(integer(&inner, "osVersion")?),
            tag::OS_PATCH_LEVEL => list.os_patch_level = Some(integer(&inner, "osPatchLevel")?),
            tag::ATTESTATION_APPLICATION_ID => {
                let der = octets(&inner, "attestationApplicationId")?;
                list.attestation_application_id = Some(attestation_application_id(der)?);
            }
            tag::VENDOR_PATCH_LEVEL => list.vendor_patch_level = Some(integer(&inner, "vendorPatchLevel")?),
            tag::BOOT_PATCH_LEVEL => list.boot_patch_level = Some(integer(&inner, "bootPatchLevel")?),
            tag::ATTESTATION_ID_FIRST..=tag::ATTESTATION_ID_LAST => list.has_device_ids = true,
            _ => {} // structurally valid, not used by this verifier
        }
    }
    Ok(list)
}

/// Parses the extension value (the contents of the extension's OCTET STRING).
pub fn parse(ext_value: &[u8]) -> Result<KeyDescription> {
    const CTX: &str = "KeyDescription";
    let top = exactly_one(ext_value, CTX)?;
    let items = children(&top, Tag::Sequence, 8, CTX)?;
    let [version, att_level, km_version, km_level, challenge, unique_id, sw, hw] = items.as_slice() else {
        return Err(E::ElementCount(CTX));
    };
    let attestation_challenge = octets(challenge, "attestationChallenge")?.to_vec();
    if attestation_challenge.len() > MAX_CHALLENGE {
        return Err(E::TooMany("attestationChallenge"));
    }
    Ok(KeyDescription {
        attestation_version: integer(version, "attestationVersion")?,
        attestation_security_level: security_level(att_level, "attestationSecurityLevel")?,
        keymint_version: integer(km_version, "keyMintVersion")?,
        keymint_security_level: security_level(km_level, "keyMintSecurityLevel")?,
        attestation_challenge,
        unique_id: octets(unique_id, "uniqueId")?.to_vec(),
        software_enforced: authorization_list(sw, "softwareEnforced")?,
        hardware_enforced: authorization_list(hw, "hardwareEnforced")?,
    })
}

impl KeyDescription {
    /// `attestationApplicationId` is populated by keystore2, normally in `softwareEnforced`.
    /// If both lists carry one they must agree.
    pub fn application_id(&self) -> std::result::Result<Option<&AttestationApplicationId>, ()> {
        match (
            self.software_enforced.attestation_application_id.as_ref(),
            self.hardware_enforced.attestation_application_id.as_ref(),
        ) {
            (Some(a), Some(b)) if a != b => Err(()),
            (Some(a), _) | (None, Some(a)) => Ok(Some(a)),
            (None, None) => Ok(None),
        }
    }
}

/// Minimal DER encoder for building KeyDescription fixtures in tests.
#[cfg(test)]
pub(crate) mod der {
    pub fn tlv(tag: &[u8], content: &[u8]) -> Vec<u8> {
        let mut out = tag.to_vec();
        let len = content.len();
        if len < 0x80 {
            out.push(len as u8);
        } else if len < 0x100 {
            out.extend_from_slice(&[0x81, len as u8]);
        } else {
            out.extend_from_slice(&[0x82, (len >> 8) as u8, len as u8]);
        }
        out.extend_from_slice(content);
        out
    }
    pub fn seq(items: &[Vec<u8>]) -> Vec<u8> {
        tlv(&[0x30], &items.concat())
    }
    pub fn set(items: &[Vec<u8>]) -> Vec<u8> {
        tlv(&[0x31], &items.concat())
    }
    pub fn int(v: i64) -> Vec<u8> {
        let bytes = v.to_be_bytes();
        let mut start = 0;
        while start < 7 {
            let (b, n) = (bytes[start], bytes[start + 1]);
            if (b == 0 && n & 0x80 == 0) || (b == 0xff && n & 0x80 != 0) {
                start += 1;
            } else {
                break;
            }
        }
        tlv(&[0x02], &bytes[start..])
    }
    pub fn enumerated(v: i64) -> Vec<u8> {
        let mut i = int(v);
        i[0] = 0x0a;
        i
    }
    pub fn octets(b: &[u8]) -> Vec<u8> {
        tlv(&[0x04], b)
    }
    pub fn boolean(b: bool) -> Vec<u8> {
        tlv(&[0x01], &[if b { 0xff } else { 0 }])
    }
    pub fn null() -> Vec<u8> {
        vec![0x05, 0x00]
    }
    /// `[n] EXPLICIT` context-specific constructed tag, high-tag-number form when n >= 31.
    pub fn explicit(n: u32, inner: &[u8]) -> Vec<u8> {
        let tag = if n < 31 {
            vec![0xa0 | n as u8]
        } else {
            let mut groups = vec![(n & 0x7f) as u8];
            let mut rest = n >> 7;
            while rest > 0 {
                groups.push(0x80 | (rest & 0x7f) as u8);
                rest >>= 7;
            }
            groups.reverse();
            let mut t = vec![0xbf];
            t.extend(groups);
            t
        };
        tlv(&tag, inner)
    }
}

#[cfg(test)]
mod tests {
    use super::der::*;
    use super::{decode_i64, parse, PackageInfo, SecurityLevel, VerifiedBootState, E};

    fn aaid(pkg: &str, digest: &[u8]) -> Vec<u8> {
        seq(&[set(&[seq(&[octets(pkg.as_bytes()), int(1)])]), set(&[octets(digest)])])
    }

    fn hw_list() -> Vec<u8> {
        seq(&[
            explicit(1, &set(&[int(2)])),
            explicit(2, &int(3)),
            explicit(3, &int(256)),
            explicit(10, &int(1)),
            explicit(503, &null()),
            explicit(702, &int(0)),
            explicit(704, &seq(&[octets(&[1; 32]), boolean(true), enumerated(0), octets(&[2; 32])])),
            explicit(706, &int(202609)),
        ])
    }

    fn key_description(sw: Vec<u8>, hw: Vec<u8>) -> Vec<u8> {
        seq(&[int(300), enumerated(1), int(300), enumerated(1), octets(b"challenge"), octets(b""), sw, hw])
    }

    #[test]
    fn parses_synthetic_description() {
        let sw = seq(&[explicit(709, &octets(&aaid("xyz.headsdown", &[7; 32])))]);
        let kd = parse(&key_description(sw, hw_list())).unwrap();
        assert_eq!(kd.attestation_version, 300);
        assert_eq!(kd.attestation_security_level, SecurityLevel::TrustedEnvironment);
        assert_eq!(kd.attestation_challenge, b"challenge");
        let hw = &kd.hardware_enforced;
        assert_eq!(hw.purposes, Some(vec![2]));
        assert_eq!((hw.algorithm, hw.key_size, hw.ec_curve, hw.origin), (Some(3), Some(256), Some(1), Some(0)));
        assert!(hw.no_auth_required);
        assert!(hw.tags_ordered);
        let rot = hw.root_of_trust.as_ref().unwrap();
        assert!(rot.device_locked && !rot.device_locked_non_der);
        assert_eq!(rot.verified_boot_state, VerifiedBootState::Verified);
        let app = kd.application_id().unwrap().unwrap();
        assert_eq!(app.packages, vec![PackageInfo { name: "xyz.headsdown".into(), version: 1 }]);
        assert_eq!(app.signature_digests, vec![vec![7; 32]]);
    }

    #[test]
    fn duplicate_tags_are_rejected() {
        let hw = seq(&[explicit(2, &int(3)), explicit(2, &int(1))]);
        assert_eq!(parse(&key_description(seq(&[]), hw)), Err(E::DuplicateTag(2)));
    }

    #[test]
    fn unordered_tags_are_tolerated_and_flagged() {
        let hw = seq(&[explicit(10, &int(1)), explicit(2, &int(3))]);
        let kd = parse(&key_description(seq(&[]), hw)).unwrap();
        assert!(!kd.hardware_enforced.tags_ordered);
        assert_eq!(kd.hardware_enforced.tags, vec![10, 2]);
    }

    #[test]
    fn non_der_boolean_is_tolerated_and_flagged() {
        let rot = tlv(&[0x30], &[octets(&[1; 32]), vec![0x01, 0x01, 0x01], enumerated(0)].concat());
        let hw = seq(&[explicit(704, &rot)]);
        let kd = parse(&key_description(seq(&[]), hw)).unwrap();
        let rot = kd.hardware_enforced.root_of_trust.unwrap();
        assert!(rot.device_locked && rot.device_locked_non_der);
        assert_eq!(rot.verified_boot_hash, None);
    }

    #[test]
    fn strictness() {
        let base = key_description(seq(&[]), hw_list());
        // Trailing bytes after the KeyDescription.
        let mut trailing = base.clone();
        trailing.push(0);
        assert_eq!(parse(&trailing), Err(E::Trailing("KeyDescription")));
        // Seven elements.
        let seven = seq(&[int(300), enumerated(1), int(300), enumerated(1), octets(b""), octets(b""), seq(&[])]);
        assert_eq!(parse(&seven), Err(E::ElementCount("KeyDescription")));
        // Unknown security level.
        let bad_level =
            seq(&[int(300), enumerated(7), int(300), enumerated(1), octets(b""), octets(b""), seq(&[]), seq(&[])]);
        assert_eq!(parse(&bad_level), Err(E::UnknownEnum("attestationSecurityLevel")));
        // Security level as INTEGER instead of ENUMERATED.
        let wrong_type = seq(&[int(300), int(1), int(300), enumerated(1), octets(b""), octets(b""), seq(&[]), seq(&[])]);
        assert_eq!(parse(&wrong_type), Err(E::UnexpectedType("attestationSecurityLevel")));
        // Non-minimal integer.
        let hw = seq(&[tlv(&[0xa2], &tlv(&[0x02], &[0x00, 0x03]))]);
        assert_eq!(parse(&key_description(seq(&[]), hw)), Err(E::BadInteger("algorithm")));
        // Explicit tag holding two elements.
        let hw = seq(&[tlv(&[0xa2], &[int(3), int(3)].concat())]);
        assert_eq!(parse(&key_description(seq(&[]), hw)), Err(E::Trailing("hardwareEnforced")));
        // Universal element where a context tag must be.
        let hw = seq(&[int(3)]);
        assert_eq!(parse(&key_description(seq(&[]), hw)), Err(E::UnexpectedType("hardwareEnforced")));
        // Indefinite length is not DER.
        let indefinite = [0x30, 0x80, 0x02, 0x01, 0x01, 0x00, 0x00];
        assert!(matches!(parse(&indefinite), Err(E::Der(_))));
        // Truncated input.
        assert!(parse(&base[..base.len() - 3]).is_err());
        assert!(parse(&[]).is_err());
    }

    #[test]
    fn application_id_conflict_detected() {
        let sw = seq(&[explicit(709, &octets(&aaid("xyz.headsdown", &[7; 32])))]);
        let hw = seq(&[explicit(709, &octets(&aaid("com.evil", &[7; 32])))]);
        let kd = parse(&key_description(sw, hw)).unwrap();
        assert!(kd.application_id().is_err());
    }

    #[test]
    fn integers() {
        for v in [0i64, 1, 127, 128, 255, 256, -1, -128, -129, i64::MAX, i64::MIN, 202609] {
            let enc = int(v);
            assert_eq!(decode_i64(&enc[2..], "t"), Ok(v), "{v}");
        }
        assert!(decode_i64(&[], "t").is_err());
        assert!(decode_i64(&[0, 0, 0, 0, 0, 0, 0, 0, 1], "t").is_err());
        assert!(decode_i64(&[0xff, 0x80], "t").is_err());
    }
}
