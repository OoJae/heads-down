//! Bounds-checked little-endian readers. Every account decoder goes through these, so a
//! short or hostile buffer yields `None`, never a panic.

use solana_address::Address;

/// `u8` at `off`.
pub fn read_u8(data: &[u8], off: usize) -> Option<u8> {
    data.get(off).copied()
}

/// Little-endian `u16` at `off`.
pub fn read_u16(data: &[u8], off: usize) -> Option<u16> {
    let end = off.checked_add(2)?;
    Some(u16::from_le_bytes(data.get(off..end)?.try_into().ok()?))
}

/// Little-endian `u32` at `off`.
pub fn read_u32(data: &[u8], off: usize) -> Option<u32> {
    let end = off.checked_add(4)?;
    Some(u32::from_le_bytes(data.get(off..end)?.try_into().ok()?))
}

/// Little-endian `u64` at `off`.
pub fn read_u64(data: &[u8], off: usize) -> Option<u64> {
    let end = off.checked_add(8)?;
    Some(u64::from_le_bytes(data.get(off..end)?.try_into().ok()?))
}

/// Little-endian `i64` at `off`.
pub fn read_i64(data: &[u8], off: usize) -> Option<i64> {
    let end = off.checked_add(8)?;
    Some(i64::from_le_bytes(data.get(off..end)?.try_into().ok()?))
}

/// 32-byte address at `off`.
pub fn read_address(data: &[u8], off: usize) -> Option<Address> {
    let end = off.checked_add(32)?;
    let arr: [u8; 32] = data.get(off..end)?.try_into().ok()?;
    Some(Address::new_from_array(arr))
}

/// `N` bytes at `off`.
pub fn read_array<const N: usize>(data: &[u8], off: usize) -> Option<[u8; N]> {
    let end = off.checked_add(N)?;
    data.get(off..end)?.try_into().ok()
}

/// `N` consecutive little-endian `u64`s at `off`.
pub fn read_u64_array<const N: usize>(data: &[u8], off: usize) -> Option<[u64; N]> {
    let mut out = [0u64; N];
    for (i, slot) in out.iter_mut().enumerate() {
        *slot = read_u64(data, off.checked_add(i.checked_mul(8)?)?)?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readers_are_bounds_checked() {
        let d = [1u8, 0, 0, 0, 0, 0, 0, 0, 2];
        assert_eq!(read_u64(&d, 0), Some(1));
        assert_eq!(read_u64(&d, 2), None);
        assert_eq!(read_u64(&d, usize::MAX), None);
        assert_eq!(read_u8(&d, 8), Some(2));
        assert_eq!(read_u8(&d, 9), None);
        assert_eq!(read_address(&d, 0), None);
        assert_eq!(read_u64_array::<2>(&d, 0), None);
        assert_eq!(read_u16(&d, usize::MAX - 1), None);
    }
}
