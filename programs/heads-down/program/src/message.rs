//! Signed-message preimages (`INTERFACE.md`, "Signed P-256 messages").
//!
//! The P-256 key signs the 32-byte `SHA-256(preimage)`; the program rebuilds
//! the preimage from its own state plus the instruction data and requires the
//! precompile entry's message to equal that digest byte for byte. Offsets are
//! running sums of the field sizes, no padding. These builders are pure and
//! shared by the program and the host test client, so both sides can never
//! disagree about the layout.

use pinocchio::Address;

/// Domain tag for every P-256 message.
pub const DOMAIN: &[u8; 4] = b"HDv1";
/// Domain tag for the registrar's Ed25519 attestation.
pub const REGISTRAR_DOMAIN: &[u8; 5] = b"HDreg";

/// Message kinds (the byte after `rig`).
pub mod kind {
    /// Heartbeat (DOWN for an ORE round).
    pub const HEARTBEAT: u8 = 1;
    /// BREAK.
    pub const BREAK: u8 = 2;
    /// FREEZE.
    pub const FREEZE: u8 = 3;
    /// PLAN (arm_shift without the wallet).
    pub const PLAN: u8 = 4;
}

/// HEARTBEAT preimage length.
pub const HEARTBEAT_LEN: usize = 94;
/// BREAK / FREEZE preimage length.
pub const SIGNAL_LEN: usize = 86;
/// PLAN preimage length.
pub const PLAN_LEN: usize = 113;
/// Registrar attestation message length.
pub const REGISTRAR_LEN: usize = 111;

/// Little helper that appends into a fixed buffer. Every writer below is
/// sized exactly (checked by `finish`), so no write can go out of bounds.
struct W<const N: usize> {
    buf: [u8; N],
    pos: usize,
}

impl<const N: usize> W<N> {
    const fn new() -> Self {
        Self {
            buf: [0; N],
            pos: 0,
        }
    }
    fn put(&mut self, bytes: &[u8]) {
        let end = self.pos.saturating_add(bytes.len());
        if let Some(dst) = self.buf.get_mut(self.pos..end) {
            dst.copy_from_slice(bytes);
        }
        self.pos = end;
    }
    fn finish(self) -> [u8; N] {
        debug_assert_eq!(self.pos, N);
        self.buf
    }
}

/// `"HDv1"(4) | program_id(32) | rig(32) | kind u8 = 1 | counter u64 |
/// shift_id u64 | round_id u64 | lease_rounds u8` (94 bytes).
pub fn heartbeat_preimage(
    rig: &Address,
    counter: u64,
    shift_id: u64,
    round_id: u64,
    lease_rounds: u8,
) -> [u8; HEARTBEAT_LEN] {
    let mut w = W::<HEARTBEAT_LEN>::new();
    w.put(DOMAIN);
    w.put(crate::ID.as_ref());
    w.put(rig.as_ref());
    w.put(&[kind::HEARTBEAT]);
    w.put(&counter.to_le_bytes());
    w.put(&shift_id.to_le_bytes());
    w.put(&round_id.to_le_bytes());
    w.put(&[lease_rounds]);
    w.finish()
}

/// `"HDv1"(4) | program_id(32) | rig(32) | kind u8 (2 BREAK, 3 FREEZE) |
/// counter u64 | shift_id u64 | reason u8` (86 bytes).
pub fn signal_preimage(
    kind: u8,
    rig: &Address,
    counter: u64,
    shift_id: u64,
    reason: u8,
) -> [u8; SIGNAL_LEN] {
    let mut w = W::<SIGNAL_LEN>::new();
    w.put(DOMAIN);
    w.put(crate::ID.as_ref());
    w.put(rig.as_ref());
    w.put(&[kind]);
    w.put(&counter.to_le_bytes());
    w.put(&shift_id.to_le_bytes());
    w.put(&[reason]);
    w.finish()
}

/// A shift plan (the fields `arm_shift` stores).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Plan {
    /// Gate threshold (lamports per ORE, pot-adjusted).
    pub max_ev_cost: u64,
    /// SOL per dig.
    pub dig_lamports: u64,
    /// Split tiles.
    pub split: u8,
    /// Solo tiles.
    pub solo: u8,
    /// Max lease rounds.
    pub lease: u8,
    /// Flags (bit0 focus-only, bit1 day).
    pub flags: u8,
    /// Window start.
    pub window_start: i64,
    /// Window end.
    pub window_end: i64,
}

/// `"HDv1"(4) | program_id(32) | rig(32) | kind u8 = 4 | counter u64 |
/// max_ev_cost u64 | dig_lamports u64 | split u8 | solo u8 | lease u8 |
/// flags u8 | window_start i64 | window_end i64` (113 bytes).
pub fn plan_preimage(rig: &Address, counter: u64, p: &Plan) -> [u8; PLAN_LEN] {
    let mut w = W::<PLAN_LEN>::new();
    w.put(DOMAIN);
    w.put(crate::ID.as_ref());
    w.put(rig.as_ref());
    w.put(&[kind::PLAN]);
    w.put(&counter.to_le_bytes());
    w.put(&p.max_ev_cost.to_le_bytes());
    w.put(&p.dig_lamports.to_le_bytes());
    w.put(&[p.split, p.solo, p.lease, p.flags]);
    w.put(&p.window_start.to_le_bytes());
    w.put(&p.window_end.to_le_bytes());
    w.finish()
}

/// Registrar attestation (Ed25519, signed raw): `"HDreg"(5) | program(32) |
/// authority(32) | p256(33) | level u8 | expiry_slot u64` (111 bytes).
pub fn registrar_message(
    authority: &Address,
    p256: &[u8; 33],
    level: u8,
    expiry_slot: u64,
) -> [u8; REGISTRAR_LEN] {
    let mut w = W::<REGISTRAR_LEN>::new();
    w.put(REGISTRAR_DOMAIN);
    w.put(crate::ID.as_ref());
    w.put(authority.as_ref());
    w.put(p256);
    w.put(&[level]);
    w.put(&expiry_slot.to_le_bytes());
    w.finish()
}

/// The 32 bytes the P-256 key signs: `SHA-256(preimage)`.
#[inline]
pub fn digest(preimage: &[u8]) -> [u8; 32] {
    crate::hash::sha256(&[preimage])
}
