//! Bounded, panic-free TLV parsing.
//!
//! The walk mirrors Token-2022's own `get_tlv_data_info` so the verifier and
//! the token program always agree on which extensions an account has:
//!
//! - fewer than 2 bytes left: the end (Token-2022 leaves realloc slack);
//! - type `0` (`Uninitialized`): the end, and nothing after it is read;
//! - a type without a full 2-byte length, or a value running past the end of
//!   the data: malformed, reject.
//!
//! On top of that this module is *stricter* than Token-2022 in two ways that
//! can only reject data Token-2022 never writes: a repeated extension type is
//! an error (so "first match wins" can never hide a second, different value),
//! and at most [`MAX_TLV_ENTRIES`] entries are walked, which bounds compute.
//!
//! Every read is a checked slice operation; nothing here can panic, whatever
//! the input bytes are.

use crate::{
    bytes::u16_le,
    error::SgtError,
    layout::{extension_type, TLV_HEADER_LEN},
};

/// Upper bound on TLV entries. Token-2022 defines fewer than 32 extension types
/// and never writes one twice, so no real account comes close.
pub const MAX_TLV_ENTRIES: usize = 32;

/// One TLV entry.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TlvEntry<'a> {
    /// `ExtensionType` discriminant.
    pub extension_type: u16,
    /// The value bytes, exactly `length` long.
    pub value: &'a [u8],
}

/// Iterator over the TLV entries of an extended mint or account. After it
/// yields an error it yields nothing more.
#[derive(Clone, Debug)]
pub struct TlvIter<'a> {
    rest: &'a [u8],
    done: bool,
}

impl<'a> TlvIter<'a> {
    /// Iterate over `tlv`, the bytes starting at [`crate::layout::TLV_START`].
    pub const fn new(tlv: &'a [u8]) -> Self {
        Self {
            rest: tlv,
            done: false,
        }
    }

    fn fail(&mut self) -> Option<Result<TlvEntry<'a>, SgtError>> {
        self.done = true;
        Some(Err(SgtError::MalformedTlv))
    }
}

impl<'a> Iterator for TlvIter<'a> {
    type Item = Result<TlvEntry<'a>, SgtError>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        let Some(extension_type) = u16_le(self.rest, 0) else {
            // Not enough room for another type: end of data.
            self.done = true;
            return None;
        };
        if extension_type == extension_type::UNINITIALIZED {
            self.done = true;
            return None;
        }
        let Some(len) = u16_le(self.rest, 2) else {
            return self.fail();
        };
        // 4 + len cannot overflow usize (len <= u16::MAX); checked anyway.
        let Some(end) = usize::from(len).checked_add(TLV_HEADER_LEN) else {
            return self.fail();
        };
        let (Some(value), Some(rest)) = (self.rest.get(TLV_HEADER_LEN..end), self.rest.get(end..))
        else {
            return self.fail();
        };
        self.rest = rest;
        Some(Ok(TlvEntry {
            extension_type,
            value,
        }))
    }
}

/// A TLV region that has been fully walked and found well-formed, with no
/// repeated extension type and at most [`MAX_TLV_ENTRIES`] entries.
#[derive(Clone, Copy, Debug)]
pub struct Extensions<'a> {
    tlv: &'a [u8],
    count: usize,
}

impl<'a> Extensions<'a> {
    /// Validate `tlv` completely before anything is read from it.
    pub fn parse(tlv: &'a [u8]) -> Result<Self, SgtError> {
        let mut seen = [0u16; MAX_TLV_ENTRIES];
        let mut count = 0usize;
        for entry in TlvIter::new(tlv) {
            let entry = entry?;
            let Some(filled) = seen.get(..count) else {
                return Err(SgtError::TooManyExtensions);
            };
            if filled.contains(&entry.extension_type) {
                return Err(SgtError::DuplicateExtension);
            }
            let Some(slot) = seen.get_mut(count) else {
                return Err(SgtError::TooManyExtensions);
            };
            *slot = entry.extension_type;
            count = count.checked_add(1).ok_or(SgtError::TooManyExtensions)?;
        }
        Ok(Self { tlv, count })
    }

    /// Number of extensions present.
    pub const fn len(&self) -> usize {
        self.count
    }

    /// `true` if there are no extensions.
    pub const fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// The value of extension `extension_type`, if present.
    pub fn get(&self, extension_type: u16) -> Option<&'a [u8]> {
        TlvIter::new(self.tlv)
            // Already validated in `parse`, so no entry is an error; stop at
            // one anyway rather than trust that.
            .map_while(Result::ok)
            .find(|e| e.extension_type == extension_type)
            .map(|e| e.value)
    }

    /// The value of a fixed-size extension, which must be exactly `N` bytes.
    /// `Ok(None)` if absent.
    pub fn get_sized<const N: usize>(
        &self,
        extension_type: u16,
    ) -> Result<Option<&'a [u8; N]>, SgtError> {
        match self.get(extension_type) {
            None => Ok(None),
            Some(value) => value
                .try_into()
                .map(Some)
                .map_err(|_| SgtError::InvalidExtensionLength),
        }
    }

    /// Iterate over all entries.
    pub fn iter(&self) -> impl Iterator<Item = TlvEntry<'a>> + 'a {
        TlvIter::new(self.tlv).map_while(Result::ok)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(ty: u16, value: &[u8]) -> [u8; 4] {
        let len = u16::try_from(value.len()).unwrap();
        let mut h = [0u8; 4];
        h[..2].copy_from_slice(&ty.to_le_bytes());
        h[2..].copy_from_slice(&len.to_le_bytes());
        h
    }

    #[test]
    fn empty_is_valid() {
        let ext = Extensions::parse(&[]).unwrap();
        assert!(ext.is_empty());
    }

    #[test]
    fn single_trailing_byte_is_slack() {
        let ext = Extensions::parse(&[7]).unwrap();
        assert!(ext.is_empty());
    }

    #[test]
    fn stops_at_uninitialized_like_token_2022() {
        // An entry hidden after a zero type must be invisible, exactly as
        // Token-2022's get_extension would not find it.
        let mut buf = [0u8; 16];
        buf[4..8].copy_from_slice(&entry(23, &[1, 2, 3, 4]));
        buf[8..12].copy_from_slice(&[1, 2, 3, 4]);
        let ext = Extensions::parse(&buf).unwrap();
        assert!(ext.is_empty());
        assert_eq!(ext.get(23), None);
    }

    #[test]
    fn type_without_length_is_malformed() {
        assert_eq!(
            Extensions::parse(&[23, 0, 72]).unwrap_err(),
            SgtError::MalformedTlv
        );
    }

    #[test]
    fn value_past_end_is_malformed() {
        let mut buf = [0u8; 8];
        buf[..4].copy_from_slice(&entry(23, &[0; 72]));
        assert_eq!(
            Extensions::parse(&buf).unwrap_err(),
            SgtError::MalformedTlv
        );
    }

    #[test]
    fn max_length_value_does_not_overflow() {
        let buf = [23, 0, 0xff, 0xff, 0, 0];
        assert_eq!(
            Extensions::parse(&buf).unwrap_err(),
            SgtError::MalformedTlv
        );
    }

    #[test]
    fn duplicate_type_is_rejected() {
        let mut buf = [0u8; 8];
        buf[..4].copy_from_slice(&entry(7, &[]));
        buf[4..].copy_from_slice(&entry(7, &[]));
        assert_eq!(
            Extensions::parse(&buf).unwrap_err(),
            SgtError::DuplicateExtension
        );
    }

    #[test]
    fn entry_cap_is_enforced() {
        let mut buf = [0u8; 4 * (MAX_TLV_ENTRIES + 1)];
        for (i, chunk) in buf.chunks_mut(4).enumerate() {
            chunk.copy_from_slice(&entry(100 + i as u16, &[]));
        }
        assert_eq!(
            Extensions::parse(&buf).unwrap_err(),
            SgtError::TooManyExtensions
        );
        assert_eq!(
            Extensions::parse(&buf[..4 * MAX_TLV_ENTRIES]).unwrap().len(),
            MAX_TLV_ENTRIES
        );
    }

    #[test]
    fn sized_get_checks_length() {
        let mut buf = [0u8; 4 + 31];
        buf[..4].copy_from_slice(&entry(12, &[0; 31]));
        let ext = Extensions::parse(&buf).unwrap();
        assert_eq!(
            ext.get_sized::<32>(12).unwrap_err(),
            SgtError::InvalidExtensionLength
        );
        assert_eq!(ext.get_sized::<32>(3).unwrap(), None);
    }
}
