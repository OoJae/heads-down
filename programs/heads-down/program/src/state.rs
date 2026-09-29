//! Zero-copy account layouts, byte-for-byte as `INTERFACE.md` specifies.
//!
//! Every struct is `#[repr(C)]` and built only from byte arrays, so its
//! alignment is 1 and `bytemuck` can view any account buffer of the exact
//! length as the struct without `unsafe` and without alignment assumptions.
//! Multi-byte integers are little-endian wrappers ([`U16`], [`U32`], [`U64`],
//! [`I64`]). Every field offset is pinned by a `const` assertion below, so a
//! layout edit that drifts from the contract fails to compile.
//!
//! Loading always checks, in this order: owner == heads_down, exact length,
//! tag, version. Only then are bytes interpreted (type-cosplay defence).

use core::mem::{offset_of, size_of};

use bytemuck::{Pod, Zeroable};
use pinocchio::{
    account::{Ref, RefMut},
    AccountView, Address,
};

use crate::{error::HdError, ID};

macro_rules! le_int {
    ($name:ident, $ty:ty, $n:literal) => {
        #[doc = concat!("Little-endian `", stringify!($ty), "` with alignment 1.")]
        #[repr(transparent)]
        #[derive(Clone, Copy, Default, PartialEq, Eq, Pod, Zeroable, Debug)]
        pub struct $name(pub [u8; $n]);

        impl $name {
            /// Read the value.
            #[inline(always)]
            pub const fn get(self) -> $ty {
                <$ty>::from_le_bytes(self.0)
            }
            /// Write the value.
            #[inline(always)]
            pub fn set(&mut self, v: $ty) {
                self.0 = v.to_le_bytes();
            }
            /// Construct from a value.
            #[inline(always)]
            pub const fn new(v: $ty) -> Self {
                Self(v.to_le_bytes())
            }
        }
    };
}

le_int!(U16, u16, 2);
le_int!(U32, u32, 4);
le_int!(U64, u64, 8);
le_int!(I64, i64, 8);

/// Account tags (`header[0]`).
pub mod tag {
    /// [`super::Config`].
    pub const CONFIG: u8 = 1;
    /// [`super::Rig`].
    pub const RIG: u8 = 2;
    /// [`super::SeekerSeat`].
    pub const SEEKER_SEAT: u8 = 3;
    /// [`super::ShiftLog`].
    pub const SHIFT_LOG: u8 = 4;
}

/// Layout version written into every header.
pub const VERSION: u8 = 1;

/// `[0] tag | [1] version | [2] bump | [3..8] reserved`.
#[repr(C)]
#[derive(Clone, Copy, Default, PartialEq, Eq, Pod, Zeroable, Debug)]
pub struct Header {
    /// Account type tag.
    pub tag: u8,
    /// Layout version (1).
    pub version: u8,
    /// Canonical bump of this account's PDA.
    pub bump: u8,
    /// Reserved, zero.
    pub reserved: [u8; 5],
}

impl Header {
    /// A fresh header.
    pub const fn new(tag: u8, bump: u8) -> Self {
        Self {
            tag,
            version: VERSION,
            bump,
            reserved: [0; 5],
        }
    }
}

/// Rig states (`Rig::state`).
pub mod rig_state {
    /// No shift open.
    pub const IDLE: u8 = 0;
    /// Shift armed, no heartbeat lease yet.
    pub const ARMED: u8 = 1;
    /// Shift armed and the phone is down (a heartbeat lease was granted).
    pub const DOWN: u8 = 2;
    /// Soft break (pickup / screen-on): a fresh heartbeat resumes the shift.
    pub const COOLING: u8 = 3;
    /// Hard break: only `end_shift` moves the rig on.
    pub const BROKEN: u8 = 4;
    /// Frozen: nothing digs; only the wallet can unfreeze.
    pub const FROZEN: u8 = 5;
}

/// `Rig::plan_flags` bits.
pub mod plan_flags {
    /// The shift never deploys (focus-only; heartbeats still recorded).
    pub const FOCUS_ONLY: u8 = 0b01;
    /// Day shift (ShiftLog mode 1) instead of night (mode 0). Extension.
    pub const DAY: u8 = 0b10;
    /// Every defined bit.
    pub const ALL: u8 = FOCUS_ONLY | DAY;
}

/// ShiftLog break reasons.
pub mod break_reason {
    /// Completed normally.
    pub const COMPLETED: u8 = 0;
    /// Phone picked up.
    pub const PICKUP: u8 = 1;
    /// Screen turned on / unlocked.
    pub const SCREEN_ON: u8 = 2;
    /// Frozen.
    pub const FREEZE: u8 = 3;
    /// No heartbeat lease was ever granted in the shift.
    pub const LEASE_LAPSE: u8 = 4;
    /// Budget exhausted.
    pub const BUDGET: u8 = 5;
    /// Ended early by the wallet, or a manual break.
    pub const MANUAL: u8 = 6;
}

/// Global configuration, PDA `[b"config"]`, 256 bytes.
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq, Pod, Zeroable, Debug)]
pub struct Config {
    /// Header (tag 1).
    pub header: Header,
    /// Squads vault that proposes config changes.
    pub governance: [u8; 32],
    /// Ed25519 key attesting hardware-backed P-256 keys.
    pub registrar: [u8; 32],
    /// Lamports reimbursed to the cranker per rig-round dug.
    pub crank_fee: U64,
    /// The Discretionary `fee` every rig's Automation must use.
    pub executor_fee: U64,
    /// Share of executor surplus routed to Bury (bps).
    pub bury_bps: U16,
    /// 1 = `dig` disabled.
    pub paused: u8,
    /// Canonical bump of `[b"executor"]`, stored at init.
    pub executor_bump: u8,
    /// Padding.
    pub _pad0: [u8; 4],
    /// sha256 of the pinned ORE layout description ([`crate::ore::LAYOUT_PREIMAGE`]).
    pub ore_layout_hash: [u8; 32],
    /// 1 while a proposal is pending.
    pub pending_exists: u8,
    /// Padding.
    pub _pad1: [u8; 7],
    /// First slot at which the pending proposal may be applied.
    pub pending_eta_slot: U64,
    /// Proposed registrar.
    pub pending_registrar: [u8; 32],
    /// Proposed crank fee.
    pub pending_crank_fee: U64,
    /// Proposed bury bps.
    pub pending_bury_bps: U16,
    /// Proposed paused flag.
    pub pending_paused: u8,
    /// Padding to 256.
    pub _pad2: [u8; 69],
}

/// A rig, PDA `[b"rig", authority]`, 384 bytes.
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq, Pod, Zeroable, Debug)]
pub struct Rig {
    /// Header (tag 2).
    pub header: Header,
    /// Wallet that owns the ORE Automation.
    pub authority: [u8; 32],
    /// SEC1-compressed Keystore P-256 key.
    pub p256_pubkey: [u8; 33],
    /// 0 none, 1 TEE, 2 StrongBox (registrar-attested).
    pub attestation_level: u8,
    /// 0 guest, 1 seeker.
    pub tier: u8,
    /// See [`rig_state`].
    pub state: u8,
    /// Padding.
    pub _pad0: [u8; 4],
    /// SGT mint (zero for guests).
    pub sgt_mint: [u8; 32],
    /// Slot after which the attestation is stale.
    pub attestation_expiry_slot: U64,
    /// Lamports per week (wallet-signed).
    pub cap_week: U64,
    /// Lamports per shift.
    pub cap_shift: U64,
    /// Lamports per round (all tiles, including the Automation fee).
    pub cap_round: U64,
    /// Ceiling on the pot-adjusted production cost `ema_ev` (lamports / ORE).
    pub cap_max_cost: U64,
    /// Unix seconds; caps are invalid after this.
    pub caps_expiry_ts: I64,
    /// Plan gate threshold (<= cap_max_cost).
    pub plan_max_ev_cost: U64,
    /// SOL per dig (<= cap_round).
    pub plan_dig_lamports: U64,
    /// Split tiles per dig (0..=15).
    pub plan_split_tiles: u8,
    /// Solo tiles per dig (0..=10).
    pub plan_solo_tiles: u8,
    /// Max heartbeat lease (1..=3).
    pub plan_lease_rounds: u8,
    /// See [`plan_flags`].
    pub plan_flags: u8,
    /// Padding.
    pub _pad1: [u8; 4],
    /// Plan window start (unix s).
    pub plan_window_start_ts: I64,
    /// Plan window end (unix s).
    pub plan_window_end_ts: I64,
    /// Increments on every arm.
    pub shift_id: U64,
    /// Highest accepted P-256 counter (all message kinds).
    pub hb_counter: U64,
    /// First ORE round the current lease covers.
    pub lease_from_round: U64,
    /// Last ORE round the current lease covers (0 = no lease this shift).
    pub lease_to_round: U64,
    /// Rounds in the shift with no valid lease.
    pub gap_count: U32,
    /// Padding.
    pub _pad2: [u8; 4],
    /// Debited from the Automation this shift (deploys + fees).
    pub spent_shift: U64,
    /// Debited this week.
    pub spent_week: U64,
    /// Start of the current 7-day spend week (unix s).
    pub week_start_ts: I64,
    /// Idempotency: last ORE round this rig dug.
    pub last_dug_round: U64,
    /// ORE round at arm.
    pub shift_start_round: U64,
    /// Rounds with a valid lease in this shift.
    pub shift_dark_rounds: U64,
    /// Rounds dug this shift.
    pub shift_rounds_dug: U64,
    /// Lifetime dark rounds (ended shifts).
    pub lifetime_dark_rounds: U64,
    /// Lifetime rounds dug.
    pub lifetime_rounds_dug: U64,
    /// Lifetime lamports placed on ORE tiles.
    pub lifetime_lamports_deployed: U64,
    /// Consecutive-day streak.
    pub streak: U32,
    /// Streak freezes left (refill to 2 every 30-day period).
    pub freezes_left: u8,
    /// Padding.
    pub _pad3: [u8; 3],
    /// Unix day of the last qualifying shift.
    pub last_shift_day: I64,
    // ---- reserved[48] at 336; the first 16 bytes are extensions -------------
    /// Extension: 1 while a shift is open (armed and not yet ended).
    pub shift_open: u8,
    /// Extension: break reason recorded by break/freeze, used by end_shift.
    pub break_reason: u8,
    /// Padding.
    pub _pad4: [u8; 6],
    /// Extension: unix time the shift was armed.
    pub shift_start_ts: I64,
    /// Reserved, zero.
    pub reserved: [u8; 32],
}

/// One verified rig per SGT mint, PDA `[b"seeker", sgt_mint]`, 128 bytes.
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq, Pod, Zeroable, Debug)]
pub struct SeekerSeat {
    /// Header (tag 3).
    pub header: Header,
    /// The SGT mint.
    pub sgt_mint: [u8; 32],
    /// The rig this seat currently verifies.
    pub rig: [u8; 32],
    /// Wallet that proved holding at `verified_slot`.
    pub authority: [u8; 32],
    /// `TokenGroupMember.member_number`.
    pub member_number: U64,
    /// Slot of the last verification.
    pub verified_slot: U64,
    /// Reserved.
    pub reserved: [u8; 8],
}

/// Sealed record of one shift, PDA `[b"shift", rig, shift_id u64 LE]`, 128 bytes.
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq, Pod, Zeroable, Debug)]
pub struct ShiftLog {
    /// Header (tag 4).
    pub header: Header,
    /// The rig.
    pub rig: [u8; 32],
    /// Shift id.
    pub shift_id: U64,
    /// ORE round at arm.
    pub start_round: U64,
    /// ORE round at end.
    pub end_round: U64,
    /// Rounds with a valid lease.
    pub dark_rounds: U64,
    /// Rounds dug.
    pub rounds_dug: U64,
    /// Lamports debited from the Automation (tiles + fees).
    pub lamports_deployed: U64,
    /// See [`break_reason`].
    pub break_reason: u8,
    /// 0 night, 1 day, 2 focus-only.
    pub mode: u8,
    /// Padding.
    pub _pad: [u8; 6],
    /// Unix time armed.
    pub start_ts: I64,
    /// Unix time ended.
    pub end_ts: I64,
    /// Reserved.
    pub reserved: [u8; 16],
}

// ---- layout pins (INTERFACE.md) --------------------------------------------

const _: () = {
    assert!(size_of::<Header>() == 8);
    assert!(core::mem::align_of::<Config>() == 1);
    assert!(core::mem::align_of::<Rig>() == 1);
    assert!(core::mem::align_of::<SeekerSeat>() == 1);
    assert!(core::mem::align_of::<ShiftLog>() == 1);

    assert!(size_of::<Config>() == 256);
    assert!(offset_of!(Config, governance) == 8);
    assert!(offset_of!(Config, registrar) == 40);
    assert!(offset_of!(Config, crank_fee) == 72);
    assert!(offset_of!(Config, executor_fee) == 80);
    assert!(offset_of!(Config, bury_bps) == 88);
    assert!(offset_of!(Config, paused) == 90);
    assert!(offset_of!(Config, executor_bump) == 91);
    assert!(offset_of!(Config, ore_layout_hash) == 96);
    assert!(offset_of!(Config, pending_exists) == 128);
    assert!(offset_of!(Config, pending_eta_slot) == 136);
    assert!(offset_of!(Config, pending_registrar) == 144);
    assert!(offset_of!(Config, pending_crank_fee) == 176);
    assert!(offset_of!(Config, pending_bury_bps) == 184);
    assert!(offset_of!(Config, pending_paused) == 186);
    assert!(offset_of!(Config, _pad2) == 187);

    assert!(size_of::<Rig>() == 384);
    assert!(offset_of!(Rig, authority) == 8);
    assert!(offset_of!(Rig, p256_pubkey) == 40);
    assert!(offset_of!(Rig, attestation_level) == 73);
    assert!(offset_of!(Rig, tier) == 74);
    assert!(offset_of!(Rig, state) == 75);
    assert!(offset_of!(Rig, sgt_mint) == 80);
    assert!(offset_of!(Rig, attestation_expiry_slot) == 112);
    assert!(offset_of!(Rig, cap_week) == 120);
    assert!(offset_of!(Rig, cap_shift) == 128);
    assert!(offset_of!(Rig, cap_round) == 136);
    assert!(offset_of!(Rig, cap_max_cost) == 144);
    assert!(offset_of!(Rig, caps_expiry_ts) == 152);
    assert!(offset_of!(Rig, plan_max_ev_cost) == 160);
    assert!(offset_of!(Rig, plan_dig_lamports) == 168);
    assert!(offset_of!(Rig, plan_split_tiles) == 176);
    assert!(offset_of!(Rig, plan_solo_tiles) == 177);
    assert!(offset_of!(Rig, plan_lease_rounds) == 178);
    assert!(offset_of!(Rig, plan_flags) == 179);
    assert!(offset_of!(Rig, plan_window_start_ts) == 184);
    assert!(offset_of!(Rig, plan_window_end_ts) == 192);
    assert!(offset_of!(Rig, shift_id) == 200);
    assert!(offset_of!(Rig, hb_counter) == 208);
    assert!(offset_of!(Rig, lease_from_round) == 216);
    assert!(offset_of!(Rig, lease_to_round) == 224);
    assert!(offset_of!(Rig, gap_count) == 232);
    assert!(offset_of!(Rig, spent_shift) == 240);
    assert!(offset_of!(Rig, spent_week) == 248);
    assert!(offset_of!(Rig, week_start_ts) == 256);
    assert!(offset_of!(Rig, last_dug_round) == 264);
    assert!(offset_of!(Rig, shift_start_round) == 272);
    assert!(offset_of!(Rig, shift_dark_rounds) == 280);
    assert!(offset_of!(Rig, shift_rounds_dug) == 288);
    assert!(offset_of!(Rig, lifetime_dark_rounds) == 296);
    assert!(offset_of!(Rig, lifetime_rounds_dug) == 304);
    assert!(offset_of!(Rig, lifetime_lamports_deployed) == 312);
    assert!(offset_of!(Rig, streak) == 320);
    assert!(offset_of!(Rig, freezes_left) == 324);
    assert!(offset_of!(Rig, last_shift_day) == 328);
    // Extensions inside reserved[48] @336.
    assert!(offset_of!(Rig, shift_open) == 336);
    assert!(offset_of!(Rig, break_reason) == 337);
    assert!(offset_of!(Rig, shift_start_ts) == 344);
    assert!(offset_of!(Rig, reserved) == 352);

    assert!(size_of::<SeekerSeat>() == 128);
    assert!(offset_of!(SeekerSeat, sgt_mint) == 8);
    assert!(offset_of!(SeekerSeat, rig) == 40);
    assert!(offset_of!(SeekerSeat, authority) == 72);
    assert!(offset_of!(SeekerSeat, member_number) == 104);
    assert!(offset_of!(SeekerSeat, verified_slot) == 112);
    assert!(offset_of!(SeekerSeat, reserved) == 120);

    assert!(size_of::<ShiftLog>() == 128);
    assert!(offset_of!(ShiftLog, rig) == 8);
    assert!(offset_of!(ShiftLog, shift_id) == 40);
    assert!(offset_of!(ShiftLog, start_round) == 48);
    assert!(offset_of!(ShiftLog, end_round) == 56);
    assert!(offset_of!(ShiftLog, dark_rounds) == 64);
    assert!(offset_of!(ShiftLog, rounds_dug) == 72);
    assert!(offset_of!(ShiftLog, lamports_deployed) == 80);
    assert!(offset_of!(ShiftLog, break_reason) == 88);
    assert!(offset_of!(ShiftLog, mode) == 89);
    assert!(offset_of!(ShiftLog, start_ts) == 96);
    assert!(offset_of!(ShiftLog, end_ts) == 104);
    assert!(offset_of!(ShiftLog, reserved) == 112);
};

/// A heads_down account type.
pub trait Account: Pod {
    /// Header tag.
    const TAG: u8;
    /// Exact data length.
    const LEN: usize = size_of::<Self>();
    /// The header.
    fn header(&self) -> &Header;
}

impl Account for Config {
    const TAG: u8 = tag::CONFIG;
    fn header(&self) -> &Header {
        &self.header
    }
}
impl Account for Rig {
    const TAG: u8 = tag::RIG;
    fn header(&self) -> &Header {
        &self.header
    }
}
impl Account for SeekerSeat {
    const TAG: u8 = tag::SEEKER_SEAT;
    fn header(&self) -> &Header {
        &self.header
    }
}
impl Account for ShiftLog {
    const TAG: u8 = tag::SHIFT_LOG;
    fn header(&self) -> &Header {
        &self.header
    }
}

/// Check owner and length (before borrowing any bytes).
#[inline]
fn check_shape<T: Account>(account: &AccountView) -> Result<(), HdError> {
    if !account.owned_by(&ID) || account.data_len() != T::LEN {
        return Err(HdError::InvalidAccountTag);
    }
    Ok(())
}

#[inline]
fn check_header(h: &Header, tag: u8) -> Result<(), HdError> {
    if h.tag != tag || h.version != VERSION {
        return Err(HdError::InvalidAccountTag);
    }
    Ok(())
}

/// `true` if `account` is an initialized heads_down account of type `T`.
pub fn is_initialized<T: Account>(account: &AccountView) -> bool {
    load::<T>(account).is_ok()
}

/// Borrow `account` as `T` after owner / length / tag / version checks.
pub fn load<T: Account>(account: &AccountView) -> Result<Ref<'_, T>, pinocchio::error::ProgramError> {
    check_shape::<T>(account)?;
    let data = account.try_borrow()?;
    let view = Ref::try_map(data, |d| {
        bytemuck::try_from_bytes::<T>(d).map_err(|_| HdError::InvalidAccountTag)
    })
    .map_err(|(_, e)| e)?;
    check_header(view.header(), T::TAG)?;
    Ok(view)
}

/// Mutably borrow `account` as `T` after owner / length / tag / version
/// checks. The account must be writable.
pub fn load_mut<T: Account>(
    account: &mut AccountView,
) -> Result<RefMut<'_, T>, pinocchio::error::ProgramError> {
    if !account.is_writable() {
        return Err(pinocchio::error::ProgramError::InvalidAccountData);
    }
    check_shape::<T>(account)?;
    let data = account.try_borrow_mut()?;
    let view = RefMut::try_map(data, |d| {
        bytemuck::try_from_bytes_mut::<T>(d).map_err(|_| HdError::InvalidAccountTag)
    })
    .map_err(|(_, e)| e)?;
    check_header(view.header(), T::TAG)?;
    Ok(view)
}

/// Mutably borrow a freshly created (all-zero, program-owned, exact length)
/// account as `T` so the caller can initialize it. Refuses anything with a
/// non-zero header (no re-initialization).
pub fn load_uninit_mut<T: Account>(
    account: &mut AccountView,
) -> Result<RefMut<'_, T>, pinocchio::error::ProgramError> {
    check_shape::<T>(account)?;
    let data = account.try_borrow_mut()?;
    let view = RefMut::try_map(data, |d| {
        bytemuck::try_from_bytes_mut::<T>(d).map_err(|_| HdError::InvalidAccountTag)
    })
    .map_err(|(_, e)| e)?;
    if view.header().tag != 0 {
        return Err(pinocchio::error::ProgramError::AccountAlreadyInitialized);
    }
    Ok(view)
}

/// Address helpers.
pub fn addr(bytes: &[u8; 32]) -> Address {
    Address::new_from_array(*bytes)
}

/// View raw account bytes (exact length) as `T`, for off-chain clients,
/// indexers and tests. No owner / tag checks: callers do those.
pub fn view<T: Account>(data: &[u8]) -> Option<&T> {
    bytemuck::try_from_bytes::<T>(data).ok()
}
