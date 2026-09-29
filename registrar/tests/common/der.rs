//! Minimal DER encoder for building KeyDescription extensions in tests.

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

/// `[n] EXPLICIT`, context-specific constructed, high-tag-number form when n >= 31.
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
