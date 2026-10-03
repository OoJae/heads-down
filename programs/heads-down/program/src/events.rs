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
//! v1.2 (SKR), additive: tags 11..=23, see [`tag`] and `INTERFACE.md` §11.9.
//! v1.3, additive: tags 24..=27 (governance rotation, ShiftLog close), §12.8.
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
    /// StackOpened (v1.2).
    pub const STACK_OPENED: u8 = 11;
    /// StackJoined (v1.2).
    pub const STACK_JOINED: u8 = 12;
    /// StackCheckin (v1.2): one per seat per `stack_checkin`, with a result code.
    pub const STACK_CHECKIN: u8 = 13;
    /// StackSettled (v1.2).
    pub const STACK_SETTLED: u8 = 14;
    /// StackClaimed (v1.2): a payout (kind 0) or a timeout refund (kind 1).
    pub const STACK_CLAIMED: u8 = 15;
    /// FocusBondLocked (v1.2).
    pub const FOCUS_BOND_LOCKED: u8 = 16;
    /// FocusBondReleased (v1.2).
    pub const FOCUS_BOND_RELEASED: u8 = 17;
    /// FocusBondForfeited (v1.2).
    pub const FOCUS_BOND_FORFEITED: u8 = 18;
    /// GiftCreated (v1.2).
    pub const GIFT_CREATED: u8 = 19;
    /// GiftClaimed (v1.2).
    pub const GIFT_CLAIMED: u8 = 20;
    /// GiftRefunded (v1.2).
    pub const GIFT_REFUNDED: u8 = 21;
    /// BuryLotAdded (v1.2): SKR forfeits entered the Bury auction (it restarts).
    pub const BURY_LOT_ADDED: u8 = 22;
    /// BuryAuctionSold (v1.2): ORE paid, buried through ORE `bury`, SKR sold.
    pub const BURY_AUCTION_SOLD: u8 = 23;
    /// GovernanceProposed (v1.3): a timelocked governance rotation started.
    pub const GOVERNANCE_PROPOSED: u8 = 24;
    /// GovernanceAccepted (v1.3): the new governance took over.
    pub const GOVERNANCE_ACCEPTED: u8 = 25;
    /// GovernanceCancelled (v1.3): the pending rotation was dropped.
    pub const GOVERNANCE_CANCELLED: u8 = 26;
    /// ShiftLogClosed (v1.3): a sealed log's rent went back to its payer.
    pub const SHIFT_LOG_CLOSED: u8 = 27;
}

/// Exact byte length of each event, tag byte included (index = tag; 0 unused).
pub const LEN: [usize; 28] = [
    0, 61, 45, 41, 66, 73, 67, 33, 49, 42, 83, // v1.1
    103, 138, 85, 67, 106, 113, 81, 82, 114, 74, 73, 66, 81, // v1.2 (SKR)
    81, 65, 65, 81, // v1.3
];

/// `StackClaimed.kind`: a settled payout.
pub const CLAIM_PAYOUT: u8 = 0;
/// `StackClaimed.kind`: a timeout refund of the seat's own bond.
pub const CLAIM_REFUND: u8 = 1;
/// `BuryLotAdded.source_kind`: a Stack table's settle.
pub const LOT_FROM_STACK: u8 = 1;
/// `BuryLotAdded.source_kind`: a forfeited Focus Bond.
pub const LOT_FROM_BOND: u8 = 2;
/// `FocusBondForfeited.reason` when the bonded shift can never be sealed
/// (the rig was closed while the shift was open).
pub const BOND_ABANDONED: u8 = 255;

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

// ---- v1.2 (SKR) encoders ------------------------------------------------------

/// The fields of StackOpened.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct StackOpened {
    /// Host-chosen id.
    pub table_id: u64,
    /// SKR base units per seat.
    pub bond: u64,
    /// Window start round.
    pub start_round: u64,
    /// Window end round (inclusive).
    pub end_round: u64,
    /// Grace gaps.
    pub grace_gaps: u32,
    /// `stack_flags`.
    pub flags: u8,
    /// Seat limit.
    pub max_seats: u8,
}

/// StackOpened bytes: `table · host · table_id u64 · bond u64 ·
/// start_round u64 · end_round u64 · grace_gaps u32 · flags u8 · max_seats u8`.
pub fn stack_opened_bytes(table: &Address, host: &Address, o: &StackOpened) -> [u8; 103] {
    Buf::<103>::new(tag::STACK_OPENED)
        .put(table.as_ref())
        .put(host.as_ref())
        .put(&o.table_id.to_le_bytes())
        .put(&o.bond.to_le_bytes())
        .put(&o.start_round.to_le_bytes())
        .put(&o.end_round.to_le_bytes())
        .put(&o.grace_gaps.to_le_bytes())
        .put(&[o.flags, o.max_seats])
        .done()
}

/// StackJoined bytes: `table · rig · authority · sgt_mint (zero if not
/// verified) · bond u64 · seat_index u8`.
pub fn stack_joined_bytes(
    table: &Address,
    rig: &Address,
    authority: &Address,
    sgt_mint: &[u8; 32],
    bond: u64,
    seat_index: u8,
) -> [u8; 138] {
    Buf::<138>::new(tag::STACK_JOINED)
        .put(table.as_ref())
        .put(rig.as_ref())
        .put(authority.as_ref())
        .put(sgt_mint)
        .put(&bond.to_le_bytes())
        .put(&[seat_index])
        .done()
}

/// StackCheckin bytes: `table · rig · round_id u64 (= Board.round_id) ·
/// checked_rounds u64 · result u32 (0 = counted, else the skip code)`.
pub fn stack_checkin_bytes(
    table: &Address,
    rig: &Address,
    round_id: u64,
    checked_rounds: u64,
    result: u32,
) -> [u8; 85] {
    Buf::<85>::new(tag::STACK_CHECKIN)
        .put(table.as_ref())
        .put(rig.as_ref())
        .put(&round_id.to_le_bytes())
        .put(&checked_rounds.to_le_bytes())
        .put(&result.to_le_bytes())
        .done()
}

/// StackSettled bytes: `table · total_bonds u64 · finisher_bonds u64 ·
/// payouts_total u64 · bury_amount u64 · seats u8 · finishers u8`.
pub fn stack_settled_bytes(
    table: &Address,
    total_bonds: u64,
    finisher_bonds: u64,
    payouts_total: u64,
    bury_amount: u64,
    seats: u8,
    finishers: u8,
) -> [u8; 67] {
    Buf::<67>::new(tag::STACK_SETTLED)
        .put(table.as_ref())
        .put(&total_bonds.to_le_bytes())
        .put(&finisher_bonds.to_le_bytes())
        .put(&payouts_total.to_le_bytes())
        .put(&bury_amount.to_le_bytes())
        .put(&[seats, finishers])
        .done()
}

/// StackClaimed bytes: `table · rig · authority · amount u64 · kind u8`.
pub fn stack_claimed_bytes(
    table: &Address,
    rig: &Address,
    authority: &Address,
    amount: u64,
    kind: u8,
) -> [u8; 106] {
    Buf::<106>::new(tag::STACK_CLAIMED)
        .put(table.as_ref())
        .put(rig.as_ref())
        .put(authority.as_ref())
        .put(&amount.to_le_bytes())
        .put(&[kind])
        .done()
}

/// FocusBondLocked bytes: `bond · rig · authority · shift_id u64 · amount u64`.
pub fn focus_bond_locked_bytes(
    bond: &Address,
    rig: &Address,
    authority: &Address,
    shift_id: u64,
    amount: u64,
) -> [u8; 113] {
    Buf::<113>::new(tag::FOCUS_BOND_LOCKED)
        .put(bond.as_ref())
        .put(rig.as_ref())
        .put(authority.as_ref())
        .put(&shift_id.to_le_bytes())
        .put(&amount.to_le_bytes())
        .done()
}

/// FocusBondReleased bytes: `bond · rig · shift_id u64 · amount u64`.
pub fn focus_bond_released_bytes(
    bond: &Address,
    rig: &Address,
    shift_id: u64,
    amount: u64,
) -> [u8; 81] {
    Buf::<81>::new(tag::FOCUS_BOND_RELEASED)
        .put(bond.as_ref())
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .put(&amount.to_le_bytes())
        .done()
}

/// FocusBondForfeited bytes: `bond · rig · shift_id u64 · amount u64 ·
/// reason u8 (the ShiftLog break_reason, or 255 abandoned)`.
pub fn focus_bond_forfeited_bytes(
    bond: &Address,
    rig: &Address,
    shift_id: u64,
    amount: u64,
    reason: u8,
) -> [u8; 82] {
    Buf::<82>::new(tag::FOCUS_BOND_FORFEITED)
        .put(bond.as_ref())
        .put(rig.as_ref())
        .put(&shift_id.to_le_bytes())
        .put(&amount.to_le_bytes())
        .put(&[reason])
        .done()
}

/// GiftCreated bytes: `gift · sender · recipient · lamports u64 ·
/// expiry_ts i64 · recipient_kind u8`.
pub fn gift_created_bytes(
    gift: &Address,
    sender: &Address,
    recipient: &[u8; 32],
    lamports: u64,
    expiry_ts: i64,
    recipient_kind: u8,
) -> [u8; 114] {
    Buf::<114>::new(tag::GIFT_CREATED)
        .put(gift.as_ref())
        .put(sender.as_ref())
        .put(recipient)
        .put(&lamports.to_le_bytes())
        .put(&expiry_ts.to_le_bytes())
        .put(&[recipient_kind])
        .done()
}

/// GiftClaimed bytes: `gift · claimer · lamports u64 · recipient_kind u8`.
pub fn gift_claimed_bytes(
    gift: &Address,
    claimer: &Address,
    lamports: u64,
    recipient_kind: u8,
) -> [u8; 74] {
    Buf::<74>::new(tag::GIFT_CLAIMED)
        .put(gift.as_ref())
        .put(claimer.as_ref())
        .put(&lamports.to_le_bytes())
        .put(&[recipient_kind])
        .done()
}

/// GiftRefunded bytes: `gift · sender · lamports u64`.
pub fn gift_refunded_bytes(gift: &Address, sender: &Address, lamports: u64) -> [u8; 73] {
    Buf::<73>::new(tag::GIFT_REFUNDED)
        .put(gift.as_ref())
        .put(sender.as_ref())
        .put(&lamports.to_le_bytes())
        .done()
}

/// BuryLotAdded bytes: `source (table or bond) · amount u64 · lot_skr u64 ·
/// start_price u64 · start_slot u64 · source_kind u8`.
pub fn bury_lot_added_bytes(
    source: &Address,
    amount: u64,
    lot_skr: u64,
    start_price: u64,
    start_slot: u64,
    source_kind: u8,
) -> [u8; 66] {
    Buf::<66>::new(tag::BURY_LOT_ADDED)
        .put(source.as_ref())
        .put(&amount.to_le_bytes())
        .put(&lot_skr.to_le_bytes())
        .put(&start_price.to_le_bytes())
        .put(&start_slot.to_le_bytes())
        .put(&[source_kind])
        .done()
}

/// The fields of BuryAuctionSold.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BurySale {
    /// SKR base units sold.
    pub skr_amount: u64,
    /// ORE atoms per whole SKR at the sale slot.
    pub price: u64,
    /// ORE atoms paid (all of it went through ORE `bury`).
    pub ore_paid: u64,
    /// ORE atoms burned by `bury` (90%).
    pub ore_burned: u64,
    /// ORE atoms `bury` sent to ORE's stake program (10%).
    pub ore_shared: u64,
    /// SKR left in the lot.
    pub lot_remaining: u64,
}

/// BuryAuctionSold bytes: `buyer · skr_amount u64 · price u64 · ore_paid u64
/// · ore_burned u64 · ore_shared u64 · lot_remaining u64`.
pub fn bury_auction_sold_bytes(buyer: &Address, s: &BurySale) -> [u8; 81] {
    Buf::<81>::new(tag::BURY_AUCTION_SOLD)
        .put(buyer.as_ref())
        .put(&s.skr_amount.to_le_bytes())
        .put(&s.price.to_le_bytes())
        .put(&s.ore_paid.to_le_bytes())
        .put(&s.ore_burned.to_le_bytes())
        .put(&s.ore_shared.to_le_bytes())
        .put(&s.lot_remaining.to_le_bytes())
        .done()
}

// ---- v1.3 encoders ----------------------------------------------------------------

/// GovernanceProposed bytes: `governance (current) · pending_governance ·
/// eta_slot u64 · eta_ts i64` (the successor may accept once both have
/// passed).
pub fn governance_proposed_bytes(
    governance: &Address,
    pending: &[u8; 32],
    eta_slot: u64,
    eta_ts: i64,
) -> [u8; 81] {
    Buf::<81>::new(tag::GOVERNANCE_PROPOSED)
        .put(governance.as_ref())
        .put(pending)
        .put(&eta_slot.to_le_bytes())
        .put(&eta_ts.to_le_bytes())
        .done()
}

/// GovernanceAccepted bytes: `governance (the new one) · previous governance`.
pub fn governance_accepted_bytes(governance: &Address, previous: &[u8; 32]) -> [u8; 65] {
    Buf::<65>::new(tag::GOVERNANCE_ACCEPTED)
        .put(governance.as_ref())
        .put(previous)
        .done()
}

/// GovernanceCancelled bytes: `governance (current) · the pending governance
/// that was dropped`.
pub fn governance_cancelled_bytes(governance: &Address, cancelled: &[u8; 32]) -> [u8; 65] {
    Buf::<65>::new(tag::GOVERNANCE_CANCELLED)
        .put(governance.as_ref())
        .put(cancelled)
        .done()
}

/// ShiftLogClosed bytes: `shift_log · rig · shift_id u64 · lamports u64 (the
/// rent returned)`.
pub fn shift_log_closed_bytes(
    shift_log: &Address,
    rig: &[u8; 32],
    shift_id: u64,
    lamports: u64,
) -> [u8; 81] {
    Buf::<81>::new(tag::SHIFT_LOG_CLOSED)
        .put(shift_log.as_ref())
        .put(rig)
        .put(&shift_id.to_le_bytes())
        .put(&lamports.to_le_bytes())
        .done()
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

    #[test]
    fn every_v12_encoder_fills_its_declared_length_exactly() {
        let a = Address::new_from_array([7; 32]);
        let b = Address::new_from_array([9; 32]);
        let o = StackOpened {
            table_id: 1,
            bond: 2,
            start_round: 3,
            end_round: 4,
            grace_gaps: 5,
            flags: 6,
            max_seats: 7,
        };
        let sale = BurySale {
            skr_amount: 1,
            price: 2,
            ore_paid: 3,
            ore_burned: 4,
            ore_shared: 5,
            lot_remaining: 6,
        };
        let all: [&[u8]; 13] = [
            &stack_opened_bytes(&a, &b, &o),
            &stack_joined_bytes(&a, &b, &a, &[3; 32], 1, 2),
            &stack_checkin_bytes(&a, &b, 1, 2, 3),
            &stack_settled_bytes(&a, 1, 2, 3, 4, 5, 6),
            &stack_claimed_bytes(&a, &b, &a, 1, 1),
            &focus_bond_locked_bytes(&a, &b, &a, 1, 2),
            &focus_bond_released_bytes(&a, &b, 1, 2),
            &focus_bond_forfeited_bytes(&a, &b, 1, 2, 3),
            &gift_created_bytes(&a, &b, &[3; 32], 1, 2, 1),
            &gift_claimed_bytes(&a, &b, 1, 1),
            &gift_refunded_bytes(&a, &b, 1),
            &bury_lot_added_bytes(&a, 1, 2, 3, 4, 1),
            &bury_auction_sold_bytes(&a, &sale),
        ];
        for (i, bytes) in all.iter().enumerate() {
            let t = i + 11;
            assert_eq!(usize::from(bytes[0]), t);
            assert_eq!(bytes.len(), LEN[t], "tag {t}");
            assert_eq!(&bytes[1..33], a.as_ref(), "tag {t} starts with its subject");
        }
        // Spot-check a few field offsets against INTERFACE.md §11.6.
        let c = stack_checkin_bytes(&a, &b, 0x0102, 0x0304, 42);
        assert_eq!(&c[65..73], &0x0102u64.to_le_bytes());
        assert_eq!(&c[73..81], &0x0304u64.to_le_bytes());
        assert_eq!(&c[81..85], &42u32.to_le_bytes());
        let s = bury_auction_sold_bytes(&a, &sale);
        assert_eq!(&s[73..81], &6u64.to_le_bytes());
    }

    #[test]
    fn every_v13_encoder_fills_its_declared_length_exactly() {
        let a = Address::new_from_array([7; 32]);
        let all: [&[u8]; 4] = [
            &governance_proposed_bytes(&a, &[9; 32], 0x0102, 0x0708),
            &governance_accepted_bytes(&a, &[9; 32]),
            &governance_cancelled_bytes(&a, &[9; 32]),
            &shift_log_closed_bytes(&a, &[9; 32], 0x0304, 0x0506),
        ];
        for (i, bytes) in all.iter().enumerate() {
            let t = i + 24;
            assert_eq!(usize::from(bytes[0]), t);
            assert_eq!(bytes.len(), LEN[t], "tag {t}");
            assert_eq!(&bytes[1..33], a.as_ref(), "tag {t} starts with its subject");
            assert_eq!(&bytes[33..65], &[9; 32], "tag {t}: second address");
        }
        assert_eq!(&all[0][65..73], &0x0102u64.to_le_bytes());
        assert_eq!(&all[3][65..73], &0x0304u64.to_le_bytes());
        assert_eq!(&all[3][73..81], &0x0506u64.to_le_bytes());
        assert_eq!(LEN.len(), 28, "tags 1..=27");
    }
}
