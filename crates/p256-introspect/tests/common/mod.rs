//! Shared test helpers.
#![allow(dead_code)]

use p256_introspect::{
    Secp256r1SignatureOffsets, CURRENT_INSTRUCTION, SECP256R1_HALF_ORDER, SECP256R1_PROGRAM_ID,
    SIGNATURE_OFFSETS_START,
};

/// A top-level instruction for building instructions-sysvar bytes.
pub struct Ix {
    pub program_id: [u8; 32],
    /// (is_signer, is_writable, pubkey)
    pub accounts: Vec<(bool, bool, [u8; 32])>,
    pub data: Vec<u8>,
}

impl Ix {
    pub fn new(program_id: [u8; 32], data: Vec<u8>) -> Self {
        Self {
            program_id,
            accounts: vec![],
            data,
        }
    }
    pub fn secp(data: Vec<u8>) -> Self {
        Self::new(SECP256R1_PROGRAM_ID.to_bytes(), data)
    }
}

/// Byte-for-byte reimplementation of `solana-instructions-sysvar` 3.0.1
/// `construct_instructions_data` + `store_current_index_checked`.
pub fn sysvar_bytes(ixs: &[Ix], current: u16) -> Vec<u8> {
    let mut data = Vec::new();
    data.extend_from_slice(&(ixs.len() as u16).to_le_bytes());
    data.resize(2 + 2 * ixs.len(), 0);
    for (i, ix) in ixs.iter().enumerate() {
        let start = data.len() as u16;
        data[2 + 2 * i..4 + 2 * i].copy_from_slice(&start.to_le_bytes());
        data.extend_from_slice(&(ix.accounts.len() as u16).to_le_bytes());
        for (s, w, k) in &ix.accounts {
            data.push((*s as u8) | ((*w as u8) << 1));
            data.extend_from_slice(k);
        }
        data.extend_from_slice(&ix.program_id);
        data.extend_from_slice(&(ix.data.len() as u16).to_le_bytes());
        data.extend_from_slice(&ix.data);
    }
    data.extend_from_slice(&current.to_le_bytes());
    data
}

/// A syntactically valid (in-range, low-S) signature. Not a real signature:
/// parser tests do not need the math, the precompile does that.
pub fn fake_sig(tag: u8) -> [u8; 64] {
    let mut sig = [0u8; 64];
    sig[..32].fill(0x11);
    sig[31] = tag;
    sig[32..].fill(0x22);
    sig[63] = tag;
    sig
}

pub fn fake_pubkey(tag: u8) -> [u8; 33] {
    let mut k = [tag; 33];
    k[0] = 0x02;
    k
}

/// s = n/2 + 1, the smallest high-S value.
pub fn smallest_high_s() -> [u8; 32] {
    let mut s = SECP256R1_HALF_ORDER;
    // HALF_ORDER ends in 0xA8, so +1 does not carry.
    s[31] += 1;
    s
}

/// Build precompile data by hand: entries of (sig, pubkey, message), with the
/// given instruction index written into all three index fields.
pub fn precompile_data(entries: &[([u8; 64], [u8; 33], &[u8])], index: u16) -> Vec<u8> {
    let n = entries.len();
    let header = SIGNATURE_OFFSETS_START + 14 * n;
    let mut data = vec![0u8; header];
    data[0] = n as u8;
    for (i, (sig, pk, msg)) in entries.iter().enumerate() {
        let pk_off = data.len() as u16;
        data.extend_from_slice(pk);
        let sig_off = data.len() as u16;
        data.extend_from_slice(sig);
        let msg_off = data.len() as u16;
        data.extend_from_slice(msg);
        let o = Secp256r1SignatureOffsets {
            signature_offset: sig_off,
            signature_instruction_index: index,
            public_key_offset: pk_off,
            public_key_instruction_index: index,
            message_data_offset: msg_off,
            message_data_size: msg.len() as u16,
            message_instruction_index: index,
        };
        let at = SIGNATURE_OFFSETS_START + 14 * i;
        data[at..at + 14].copy_from_slice(&o.to_bytes());
    }
    data
}

pub fn one_entry(msg: &[u8]) -> Vec<u8> {
    precompile_data(&[(fake_sig(1), fake_pubkey(7), msg)], CURRENT_INSTRUCTION)
}

/// Rewrite offsets record `i` in place.
pub fn patch_offsets(data: &mut [u8], i: usize, f: impl FnOnce(&mut Secp256r1SignatureOffsets)) {
    let at = SIGNATURE_OFFSETS_START + 14 * i;
    let mut o = Secp256r1SignatureOffsets::from_bytes(data[at..at + 14].try_into().unwrap());
    f(&mut o);
    data[at..at + 14].copy_from_slice(&o.to_bytes());
}

/// Tiny deterministic PRNG for fuzz-style loops (no extra dependency).
pub struct XorShift(pub u64);
impl XorShift {
    pub fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    pub fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}
