//! Checked little-endian reads. Each returns `None` instead of panicking when
//! the requested range is out of bounds.

/// `&data[offset..offset + N]` as an array reference.
#[inline(always)]
pub(crate) fn array<const N: usize>(data: &[u8], offset: usize) -> Option<&[u8; N]> {
    let end = offset.checked_add(N)?;
    data.get(offset..end)?.try_into().ok()
}

/// Little-endian `u16` at `offset`.
#[inline(always)]
pub(crate) fn u16_le(data: &[u8], offset: usize) -> Option<u16> {
    array::<2>(data, offset).map(|b| u16::from_le_bytes(*b))
}

/// Little-endian `u64` at `offset`.
#[inline(always)]
pub(crate) fn u64_le(data: &[u8], offset: usize) -> Option<u64> {
    array::<8>(data, offset).map(|b| u64::from_le_bytes(*b))
}

/// The byte at `offset`.
#[inline(always)]
pub(crate) fn byte(data: &[u8], offset: usize) -> Option<u8> {
    data.get(offset).copied()
}
