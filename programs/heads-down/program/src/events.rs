//! Events, logged with `sol_log_data` as one buffer whose first byte is the
//! event tag (`INTERFACE.md`, "Events"). All integers little-endian.
//!
//! | tag | event | layout after the tag | total |
//! |---|---|---|---|
//! | 1 | RigDug | rig 32, round_id u64, lamports u64 (on tiles), mask u32, ema_ev u64 | 61 |
//! | 2 | RigSkipped | rig 32, round_id u64, error u32 | 45 |
//! | 3 | ShiftArmed | rig 32, shift_id u64 | 41 |
//! | 4 | ShiftEnded | rig 32, shift_id u64, dark_rounds u64, rounds_dug u64, lamports u64, reason u8 | 66 |
//! | 5 | SeekerVerified | rig 32, sgt_mint 32, member_number u64 | 73 |

use pinocchio::Address;

/// Event tags.
pub mod tag {
    /// RigDug.
    pub const RIG_DUG: u8 = 1;
    /// RigSkipped.
    pub const RIG_SKIPPED: u8 = 2;
    /// ShiftArmed.
    pub const SHIFT_ARMED: u8 = 3;
    /// ShiftEnded.
    pub const SHIFT_ENDED: u8 = 4;
    /// SeekerVerified.
    pub const SEEKER_VERIFIED: u8 = 5;
}

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
    fn emit(self) {
        debug_assert_eq!(self.p, N);
        log_data(&self.b);
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

/// RigDug{rig, round_id, lamports, mask, ema_ev}.
pub fn rig_dug(rig: &Address, round_id: u64, lamports: u64, mask: u32, ema_ev: u64) {
    Buf::<61>::new(tag::RIG_DUG)
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&lamports.to_le_bytes())
        .put(&mask.to_le_bytes())
        .put(&ema_ev.to_le_bytes())
        .emit();
}

/// RigSkipped{rig, round_id, error}.
pub fn rig_skipped(rig: &Address, round_id: u64, error: u32) {
    Buf::<45>::new(tag::RIG_SKIPPED)
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&error.to_le_bytes())
        .emit();
}

/// ShiftArmed{rig, shift_id}.
pub fn shift_armed(rig: &Address, shift_id: u64) {
    Buf::<41>::new(tag::SHIFT_ARMED)
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .emit();
}

/// ShiftEnded{rig, shift_id, dark_rounds, rounds_dug, lamports, reason}.
pub fn shift_ended(
    rig: &Address,
    shift_id: u64,
    dark_rounds: u64,
    rounds_dug: u64,
    lamports: u64,
    reason: u8,
) {
    Buf::<66>::new(tag::SHIFT_ENDED)
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .put(&dark_rounds.to_le_bytes())
        .put(&rounds_dug.to_le_bytes())
        .put(&lamports.to_le_bytes())
        .put(&[reason])
        .emit();
}

/// SeekerVerified{rig, sgt_mint, member_number}.
pub fn seeker_verified(rig: &Address, sgt_mint: &Address, member_number: u64) {
    Buf::<73>::new(tag::SEEKER_VERIFIED)
        .put(rig.as_ref())
        .put(sgt_mint.as_ref())
        .put(&member_number.to_le_bytes())
        .emit();
}
