//! Events, logged with `sol_log_data` as one buffer whose first byte is the
//! event tag (`INTERFACE.md`, "Events"). All integers little-endian, no
//! padding. The golden bytes are `vectors/events.json`, captured from real
//! LiteSVM runs.
//!
//! | tag | event | layout after the tag | total |
//! |---|---|---|---|
//! | 1 | RigDug | rig 32, round_id u64, lamports u64 (on tiles), mask u32, ema_ev u64 | 61 |
//! | 2 | RigSkipped | rig 32, round_id u64, error u32 | 45 |
//! | 3 | ShiftArmed | rig 32, shift_id u64 | 41 |
//! | 4 | ShiftEnded | rig 32, shift_id u64, dark_rounds u64, rounds_dug u64, lamports u64, reason u8 | 66 |
//! | 5 | SeekerVerified | rig 32, sgt_mint 32, member_number u64 | 73 |
//! | 6 | RigRegistered | rig 32, authority 32, tier u8, attestation_level u8 | 67 |
//! | 7 | RigClosed | rig 32 | 33 |
//! | 8 | HeartbeatsRecorded | rig 32, round_id u64, dark_rounds_added u64 | 49 |
//! | 9 | ShiftBroken | rig 32, shift_id u64, reason u8 | 42 |
//! | 10 | ShiftEndedV2 | ShiftEnded's fields, then start_round u64, end_round u64, mode u8 | 83 |
//!
//! A tag's length never changes: the indexer decodes by exact length, so no
//! field is ever appended to an existing tag. `ShiftEnded` (tag 4) is still
//! emitted unchanged, immediately followed by its superset `ShiftEndedV2`
//! (tag 10) in the same instruction; a consumer that decodes tag 10 ignores
//! tag 4.

use pinocchio::Address;

/// Event tags.
pub mod tag {
    /// RigDug.
    pub const RIG_DUG: u8 = 1;
    /// RigSkipped.
    pub const RIG_SKIPPED: u8 = 2;
    /// ShiftArmed.
    pub const SHIFT_ARMED: u8 = 3;
    /// ShiftEnded (v1; kept byte-for-byte, see ShiftEndedV2).
    pub const SHIFT_ENDED: u8 = 4;
    /// SeekerVerified.
    pub const SEEKER_VERIFIED: u8 = 5;
    /// RigRegistered.
    pub const RIG_REGISTERED: u8 = 6;
    /// RigClosed.
    pub const RIG_CLOSED: u8 = 7;
    /// HeartbeatsRecorded (one per rig accepted by `record_heartbeats`).
    pub const HEARTBEATS_RECORDED: u8 = 8;
    /// ShiftBroken (a BREAK, or a FREEZE that interrupts an open shift).
    pub const SHIFT_BROKEN: u8 = 9;
    /// ShiftEndedV2 (superset of ShiftEnded).
    pub const SHIFT_ENDED_V2: u8 = 10;
}

/// Exact byte length of each event, tag byte included (index = tag; 0 unused).
pub const LEN: [usize; 11] = [0, 61, 45, 41, 66, 73, 67, 33, 49, 42, 83];

struct Buf<const N: usize> {
    b: [u8; N],
    p: usize,
}

impl<const N: usize> Buf<N> {
    fn new(tag: u8) -> Self {
        let mut b = [0u8; N];
        if let Some(first) = b.first_mut() {
            *first = tag;
        }
        Self { b, p: 1 }
    }
    fn put(mut self, bytes: &[u8]) -> Self {
        let end = self.p.saturating_add(bytes.len());
        if let Some(dst) = self.b.get_mut(self.p..end) {
            dst.copy_from_slice(bytes);
        }
        self.p = end;
        self
    }
    fn done(self) -> [u8; N] {
        debug_assert_eq!(self.p, N);
        self.b
    }
}

/// `sol_log_data(&[data])`. A no-op on the host.
#[allow(unsafe_code)]
#[inline]
pub fn log_data(data: &[u8]) {
    #[cfg(target_os = "solana")]
    {
        let slices: [&[u8]; 1] = [data];
        // SAFETY: `slices` is a valid array of one `&[u8]` (ptr, len) pair,
        // which is exactly the layout `sol_log_data` reads, and it outlives
        // the call.
        unsafe {
            pinocchio::syscalls::sol_log_data(slices.as_ptr() as *const u8, 1);
        }
    }
    #[cfg(not(target_os = "solana"))]
    core::hint::black_box(data);
}

/// The fields of a sealed shift ([`shift_ended`] and [`shift_ended_v2`]).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ShiftSummary {
    /// Shift id.
    pub shift_id: u64,
    /// Rounds with a valid lease.
    pub dark_rounds: u64,
    /// Rounds dug.
    pub rounds_dug: u64,
    /// Lamports debited from the Automation (tiles + fees) = `spent_shift`.
    pub lamports: u64,
    /// ShiftLog break reason.
    pub reason: u8,
    /// ORE round at arm.
    pub start_round: u64,
    /// ORE round at end.
    pub end_round: u64,
    /// 0 night, 1 day, 2 focus-only.
    pub mode: u8,
}

// ---- encoders (pure; used by the emitters, host tests and vectors) ----------

/// RigDug bytes.
pub fn rig_dug_bytes(
    rig: &Address,
    round_id: u64,
    lamports: u64,
    mask: u32,
    ema_ev: u64,
) -> [u8; 61] {
    Buf::<61>::new(tag::RIG_DUG)
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&lamports.to_le_bytes())
        .put(&mask.to_le_bytes())
        .put(&ema_ev.to_le_bytes())
        .done()
}

/// RigSkipped bytes.
pub fn rig_skipped_bytes(rig: &Address, round_id: u64, error: u32) -> [u8; 45] {
    Buf::<45>::new(tag::RIG_SKIPPED)
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&error.to_le_bytes())
        .done()
}

/// ShiftArmed bytes.
pub fn shift_armed_bytes(rig: &Address, shift_id: u64) -> [u8; 41] {
    Buf::<41>::new(tag::SHIFT_ARMED)
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .done()
}

/// ShiftEnded (v1) bytes.
pub fn shift_ended_bytes(rig: &Address, s: &ShiftSummary) -> [u8; 66] {
    Buf::<66>::new(tag::SHIFT_ENDED)
        .put(rig.as_ref())
        .put(&s.shift_id.to_le_bytes())
        .put(&s.dark_rounds.to_le_bytes())
        .put(&s.rounds_dug.to_le_bytes())
        .put(&s.lamports.to_le_bytes())
        .put(&[s.reason])
        .done()
}

/// SeekerVerified bytes.
pub fn seeker_verified_bytes(rig: &Address, sgt_mint: &Address, member_number: u64) -> [u8; 73] {
    Buf::<73>::new(tag::SEEKER_VERIFIED)
        .put(rig.as_ref())
        .put(sgt_mint.as_ref())
        .put(&member_number.to_le_bytes())
        .done()
}

/// RigRegistered bytes.
pub fn rig_registered_bytes(
    rig: &Address,
    authority: &Address,
    tier: u8,
    attestation_level: u8,
) -> [u8; 67] {
    Buf::<67>::new(tag::RIG_REGISTERED)
        .put(rig.as_ref())
        .put(authority.as_ref())
        .put(&[tier, attestation_level])
        .done()
}

/// RigClosed bytes.
pub fn rig_closed_bytes(rig: &Address) -> [u8; 33] {
    Buf::<33>::new(tag::RIG_CLOSED).put(rig.as_ref()).done()
}

/// HeartbeatsRecorded bytes.
pub fn heartbeats_recorded_bytes(rig: &Address, round_id: u64, dark_rounds_added: u64) -> [u8; 49] {
    Buf::<49>::new(tag::HEARTBEATS_RECORDED)
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&dark_rounds_added.to_le_bytes())
        .done()
}

/// ShiftBroken bytes.
pub fn shift_broken_bytes(rig: &Address, shift_id: u64, reason: u8) -> [u8; 42] {
    Buf::<42>::new(tag::SHIFT_BROKEN)
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .put(&[reason])
        .done()
}

/// ShiftEndedV2 bytes.
pub fn shift_ended_v2_bytes(rig: &Address, s: &ShiftSummary) -> [u8; 83] {
    Buf::<83>::new(tag::SHIFT_ENDED_V2)
        .put(rig.as_ref())
        .put(&s.shift_id.to_le_bytes())
        .put(&s.dark_rounds.to_le_bytes())
        .put(&s.rounds_dug.to_le_bytes())
        .put(&s.lamports.to_le_bytes())
        .put(&[s.reason])
        .put(&s.start_round.to_le_bytes())
        .put(&s.end_round.to_le_bytes())
        .put(&[s.mode])
        .done()
}

// ---- emitters ---------------------------------------------------------------

/// RigDug{rig, round_id, lamports, mask, ema_ev}.
pub fn rig_dug(rig: &Address, round_id: u64, lamports: u64, mask: u32, ema_ev: u64) {
    log_data(&rig_dug_bytes(rig, round_id, lamports, mask, ema_ev));
}

/// RigSkipped{rig, round_id, error}.
pub fn rig_skipped(rig: &Address, round_id: u64, error: u32) {
    log_data(&rig_skipped_bytes(rig, round_id, error));
}

/// ShiftArmed{rig, shift_id}.
pub fn shift_armed(rig: &Address, shift_id: u64) {
    log_data(&shift_armed_bytes(rig, shift_id));
}

/// ShiftEnded{rig, shift_id, dark_rounds, rounds_dug, lamports, reason}
/// followed by ShiftEndedV2 (the superset).
pub fn shift_ended(rig: &Address, s: &ShiftSummary) {
    log_data(&shift_ended_bytes(rig, s));
    log_data(&shift_ended_v2_bytes(rig, s));
}

/// SeekerVerified{rig, sgt_mint, member_number}.
pub fn seeker_verified(rig: &Address, sgt_mint: &Address, member_number: u64) {
    log_data(&seeker_verified_bytes(rig, sgt_mint, member_number));
}

/// RigRegistered{rig, authority, tier, attestation_level}.
pub fn rig_registered(rig: &Address, authority: &Address, tier: u8, attestation_level: u8) {
    log_data(&rig_registered_bytes(
        rig,
        authority,
        tier,
        attestation_level,
    ));
}

/// RigClosed{rig}.
pub fn rig_closed(rig: &Address) {
    log_data(&rig_closed_bytes(rig));
}

/// HeartbeatsRecorded{rig, round_id, dark_rounds_added}: `round_id` is the
/// live `Board.round_id` the heartbeat was recorded in.
pub fn heartbeats_recorded(rig: &Address, round_id: u64, dark_rounds_added: u64) {
    log_data(&heartbeats_recorded_bytes(rig, round_id, dark_rounds_added));
}

/// ShiftBroken{rig, shift_id, reason}.
pub fn shift_broken(rig: &Address, shift_id: u64, reason: u8) {
    log_data(&shift_broken_bytes(rig, shift_id, reason));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_encoder_fills_its_declared_length_exactly() {
        let rig = Address::new_from_array([7; 32]);
        let other = Address::new_from_array([9; 32]);
        let s = ShiftSummary {
            shift_id: 1,
            dark_rounds: 2,
            rounds_dug: 3,
            lamports: 4,
            reason: 5,
            start_round: 6,
            end_round: 7,
            mode: 1,
        };
        let all: [&[u8]; 10] = [
            &rig_dug_bytes(&rig, 1, 2, 3, 4),
            &rig_skipped_bytes(&rig, 1, 2),
            &shift_armed_bytes(&rig, 1),
            &shift_ended_bytes(&rig, &s),
            &seeker_verified_bytes(&rig, &other, 1),
            &rig_registered_bytes(&rig, &other, 1, 2),
            &rig_closed_bytes(&rig),
            &heartbeats_recorded_bytes(&rig, 1, 2),
            &shift_broken_bytes(&rig, 1, 2),
            &shift_ended_v2_bytes(&rig, &s),
        ];
        for (i, bytes) in all.iter().enumerate() {
            let t = i + 1;
            assert_eq!(usize::from(bytes[0]), t);
            assert_eq!(bytes.len(), LEN[t], "tag {t}");
            assert_eq!(&bytes[1..33], rig.as_ref(), "tag {t} starts with the rig");
        }
        // v2 is v1 plus 17 appended bytes.
        let v1 = shift_ended_bytes(&rig, &s);
        let v2 = shift_ended_v2_bytes(&rig, &s);
        assert_eq!(&v2[1..66], &v1[1..66]);
        assert_eq!(&v2[66..74], &6u64.to_le_bytes());
        assert_eq!(&v2[74..82], &7u64.to_le_bytes());
        assert_eq!(v2[82], 1);
    }
}
