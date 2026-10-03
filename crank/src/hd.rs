//! The `heads_down` program interface, built strictly from the frozen contract
//! `programs/heads-down/INTERFACE.md` (the v1.1 core plus the additive v1.2 SKR section, §11)
//! and its machine-checked golden vectors (`programs/heads-down/vectors/`, cross-checked byte
//! for byte in `tests/golden.rs`): PDAs, the Config / Rig / ShiftLog layouts, the signed P-256
//! preimages, the instruction builders the crank sends (`dig`, `record_heartbeats`,
//! `break_shift` / `freeze_rig` on the P-256 path, `end_shift`), error names (0..=48) and
//! every event (tags 1..=23). The v1.2 accounts and instructions (Stack, Focus Bond, Gift a
//! Rig, the Bury vault) are in [`crate::skr`]. The program is not imported: the vectors are
//! the contract.

use sha2::{Digest, Sha256};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

use crate::bytes::{read_address, read_array, read_i64, read_u16, read_u32, read_u64, read_u8};
use crate::ore;

/// `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`.
pub const PROGRAM_ID: Address =
    Address::from_str_const("HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p");
/// SIMD-0075 precompile.
pub const SECP256R1_PROGRAM_ID: Address =
    Address::from_str_const("Secp256r1SigVerify1111111111111111111111111");
/// Instructions sysvar.
pub const INSTRUCTIONS_SYSVAR_ID: Address =
    Address::from_str_const("Sysvar1nstructions1111111111111111111111111");

/// Account tags (header byte 0).
pub const TAG_CONFIG: u8 = 1;
/// Rig tag.
pub const TAG_RIG: u8 = 2;
/// SeekerSeat tag.
pub const TAG_SEEKER_SEAT: u8 = 3;
/// ShiftLog tag.
pub const TAG_SHIFT_LOG: u8 = 4;
/// Header version byte.
pub const ACCOUNT_VERSION: u8 = 1;
/// Config size.
pub const CONFIG_LEN: usize = 256;
/// Rig size.
pub const RIG_LEN: usize = 384;
/// ShiftLog size.
pub const SHIFT_LOG_LEN: usize = 128;

/// `dig` instruction tag.
pub const IX_DIG: u8 = 6;
/// `record_heartbeats` instruction tag.
pub const IX_RECORD_HEARTBEATS: u8 = 7;
/// `break_shift` instruction tag.
pub const IX_BREAK_SHIFT: u8 = 8;
/// `freeze_rig` instruction tag.
pub const IX_FREEZE_RIG: u8 = 9;
/// `end_shift` instruction tag.
pub const IX_END_SHIFT: u8 = 11;
/// Authorization mode byte of `arm_shift` / `break_shift` / `freeze_rig`: the P-256 path.
pub const MODE_P256: u8 = 1;
/// `hb_ix` value meaning "reuse the rig's current on-chain lease".
pub const HB_REUSE_LEASE: u8 = 0xFF;
/// Size of one per-rig `dig` / `record_heartbeats` entry.
pub const DIG_ENTRY_LEN: usize = 20;
/// Fixed accounts before the per-rig groups.
pub const DIG_FIXED_ACCOUNTS: usize = 12;
/// Accounts per rig.
pub const DIG_ACCOUNTS_PER_RIG: usize = 4;
/// Rigs per `dig` / `record_heartbeats` instruction at most (`n` 1..=32).
pub const MAX_RIGS_PER_IX: usize = 32;
/// Largest lease a heartbeat may request (`plan_lease_rounds` is 1..=3).
pub const MAX_LEASE_ROUNDS: u8 = 3;

/// Domain tag at the start of every signed message.
pub const DOMAIN: [u8; 4] = *b"HDv1";
/// HEARTBEAT kind byte.
pub const KIND_HEARTBEAT: u8 = 1;
/// BREAK kind byte.
pub const KIND_BREAK: u8 = 2;
/// FREEZE kind byte.
pub const KIND_FREEZE: u8 = 3;
/// PLAN kind byte.
pub const KIND_PLAN: u8 = 4;
/// HEARTBEAT preimage length.
pub const HEARTBEAT_PREIMAGE_LEN: usize = 94;
/// BREAK/FREEZE preimage length.
pub const BREAK_PREIMAGE_LEN: usize = 86;
/// PLAN preimage length.
pub const PLAN_PREIMAGE_LEN: usize = 113;

/// `ShiftLog.break_reason` values (also the BREAK / FREEZE `reason` byte).
pub mod reason {
    /// Completed normally.
    pub const COMPLETED: u8 = 0;
    /// Phone picked up (BREAK → Cooling).
    pub const PICKUP: u8 = 1;
    /// Screen turned on (BREAK → Cooling).
    pub const SCREEN_ON: u8 = 2;
    /// Frozen (the reason a FREEZE carries).
    pub const FREEZE: u8 = 3;
    /// No lease (BREAK → Broken; also `end_shift` without a dark round).
    pub const LEASE_LAPSE: u8 = 4;
    /// Budget (BREAK → Broken).
    pub const BUDGET: u8 = 5;
    /// Manual (BREAK → Broken).
    pub const MANUAL: u8 = 6;
    /// Charger unplugged (BREAK → Cooling). v1.1.
    pub const UNPLUGGED: u8 = 7;
    /// Device unlocked (BREAK → Broken). v1.1.
    pub const UNLOCKED: u8 = 8;
    /// Every reason `break_shift` accepts.
    pub const BREAK_REASONS: [u8; 7] = [PICKUP, SCREEN_ON, LEASE_LAPSE, BUDGET, MANUAL, UNPLUGGED, UNLOCKED];
}

/// `[b"config"]`.
pub fn config_pda(program_id: &Address) -> (Address, u8) {
    Address::find_program_address(&[b"config"], program_id)
}

/// `[b"executor"]` (System-owned, data-less).
pub fn executor_pda(program_id: &Address) -> (Address, u8) {
    Address::find_program_address(&[b"executor"], program_id)
}

/// `[b"rig", authority]`.
pub fn rig_pda(program_id: &Address, authority: &Address) -> (Address, u8) {
    Address::find_program_address(&[b"rig", authority.as_ref()], program_id)
}

/// `[b"shift", rig, shift_id u64 LE]`.
pub fn shift_log_pda(program_id: &Address, rig: &Address, shift_id: u64) -> (Address, u8) {
    Address::find_program_address(&[b"shift", rig.as_ref(), &shift_id.to_le_bytes()], program_id)
}

/// Why a heads_down account could not be decoded.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum AccountError {
    /// Not owned by the program.
    #[error("owner {0} is not heads_down")]
    WrongOwner(Address),
    /// Wrong size.
    #[error("size {got} != {expected}")]
    WrongSize {
        /// Expected.
        expected: usize,
        /// Observed.
        got: usize,
    },
    /// Wrong tag or version.
    #[error("tag/version {tag}/{version} unexpected")]
    WrongTag {
        /// Observed tag.
        tag: u8,
        /// Observed version.
        version: u8,
    },
}

pub(crate) fn check_header(
    program_id: &Address,
    owner: &Address,
    data: &[u8],
    tag: u8,
    len: usize,
) -> Result<(), AccountError> {
    if owner != program_id {
        return Err(AccountError::WrongOwner(*owner));
    }
    if data.len() != len {
        return Err(AccountError::WrongSize { expected: len, got: data.len() });
    }
    let (t, v) = (data.first().copied().unwrap_or(0), data.get(1).copied().unwrap_or(0));
    if t != tag || v != ACCOUNT_VERSION {
        return Err(AccountError::WrongTag { tag: t, version: v });
    }
    Ok(())
}

/// `Config` (256 bytes).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HdConfig {
    /// PDA bump.
    pub bump: u8,
    /// Squads vault.
    pub governance: Address,
    /// Attestation registrar key.
    pub registrar: Address,
    /// Lamports reimbursed to the cranker per real deploy.
    pub crank_fee: u64,
    /// The Discretionary `fee` every rig's Automation must use.
    pub executor_fee: u64,
    /// Bury share.
    pub bury_bps: u16,
    /// 1 = `dig` fails with `Paused`.
    pub paused: bool,
    /// Canonical bump of `[b"executor"]`.
    pub executor_bump: u8,
    /// sha256 of pinned ORE sizes and discriminators.
    pub ore_layout_hash: [u8; 32],
}

impl HdConfig {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        check_header(program_id, owner, data, TAG_CONFIG, CONFIG_LEN)?;
        let short = || AccountError::WrongSize { expected: CONFIG_LEN, got: data.len() };
        Ok(HdConfig {
            bump: read_u8(data, 2).ok_or_else(short)?,
            governance: read_address(data, 8).ok_or_else(short)?,
            registrar: read_address(data, 40).ok_or_else(short)?,
            crank_fee: read_u64(data, 72).ok_or_else(short)?,
            executor_fee: read_u64(data, 80).ok_or_else(short)?,
            bury_bps: read_u16(data, 88).ok_or_else(short)?,
            paused: read_u8(data, 90).ok_or_else(short)? != 0,
            executor_bump: read_u8(data, 91).ok_or_else(short)?,
            ore_layout_hash: read_array::<32>(data, 96).ok_or_else(short)?,
        })
    }

    /// Serialize (tests, LiteSVM fixtures). Pending-proposal fields are zero.
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; CONFIG_LEN];
        d[0] = TAG_CONFIG;
        d[1] = ACCOUNT_VERSION;
        d[2] = self.bump;
        d[8..40].copy_from_slice(self.governance.as_ref());
        d[40..72].copy_from_slice(self.registrar.as_ref());
        d[72..80].copy_from_slice(&self.crank_fee.to_le_bytes());
        d[80..88].copy_from_slice(&self.executor_fee.to_le_bytes());
        d[88..90].copy_from_slice(&self.bury_bps.to_le_bytes());
        d[90] = u8::from(self.paused);
        d[91] = self.executor_bump;
        d[96..128].copy_from_slice(&self.ore_layout_hash);
        d
    }
}

/// Rig state machine (`Rig.state`, INTERFACE §6.7).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum RigState {
    /// 0
    Idle,
    /// 1
    Armed,
    /// 2
    Down,
    /// 3: soft break; digs again only with a fresh heartbeat in the same entry.
    Cooling,
    /// 4
    Broken,
    /// 5
    Frozen,
    /// Any other byte: treated as not diggable.
    Unknown(u8),
}

impl RigState {
    /// From the wire byte.
    pub fn from_u8(v: u8) -> Self {
        match v {
            0 => RigState::Idle,
            1 => RigState::Armed,
            2 => RigState::Down,
            3 => RigState::Cooling,
            4 => RigState::Broken,
            5 => RigState::Frozen,
            other => RigState::Unknown(other),
        }
    }

    /// To the wire byte.
    pub fn as_u8(self) -> u8 {
        match self {
            RigState::Idle => 0,
            RigState::Armed => 1,
            RigState::Down => 2,
            RigState::Cooling => 3,
            RigState::Broken => 4,
            RigState::Frozen => 5,
            RigState::Unknown(v) => v,
        }
    }

    /// Only Armed and Down dig by reusing a lease (`hb_ix = 0xFF`).
    pub fn diggable(self) -> bool {
        matches!(self, RigState::Armed | RigState::Down)
    }

    /// States a fresh HEARTBEAT is verified in (`dig` and `record_heartbeats`): Armed, Down,
    /// Cooling. A verified heartbeat moves Armed / Cooling to Down.
    pub fn accepts_heartbeat(self) -> bool {
        matches!(self, RigState::Armed | RigState::Down | RigState::Cooling)
    }

    /// States `break_shift` accepts.
    pub fn breakable(self) -> bool {
        self.accepts_heartbeat()
    }

    /// Snake-case name for logs and captions.
    pub fn name(self) -> &'static str {
        match self {
            RigState::Idle => "idle",
            RigState::Armed => "armed",
            RigState::Down => "down",
            RigState::Cooling => "cooling",
            RigState::Broken => "broken",
            RigState::Frozen => "frozen",
            RigState::Unknown(_) => "unknown",
        }
    }
}

/// `Rig` (384 bytes): every field of INTERFACE v1.1 §3.2 and §3.3.
#[derive(Clone, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct Rig {
    pub bump: u8,
    pub authority: Address,
    pub p256_pubkey: [u8; 33],
    pub attestation_level: u8,
    pub tier: u8,
    pub state: RigState,
    pub sgt_mint: Address,
    pub attestation_expiry_slot: u64,
    pub cap_week: u64,
    pub cap_shift: u64,
    pub cap_round: u64,
    pub cap_max_cost: u64,
    pub caps_expiry_ts: i64,
    pub plan_max_ev_cost: u64,
    pub plan_dig_lamports: u64,
    pub plan_split_tiles: u8,
    pub plan_solo_tiles: u8,
    pub plan_lease_rounds: u8,
    pub plan_flags: u8,
    pub plan_window_start_ts: i64,
    pub plan_window_end_ts: i64,
    pub shift_id: u64,
    pub hb_counter: u64,
    pub lease_from_round: u64,
    pub lease_to_round: u64,
    pub gap_count: u32,
    pub spent_shift: u64,
    pub spent_week: u64,
    pub week_start_ts: i64,
    pub last_dug_round: u64,
    pub shift_start_round: u64,
    pub shift_dark_rounds: u64,
    pub shift_rounds_dug: u64,
    pub lifetime_dark_rounds: u64,
    pub lifetime_rounds_dug: u64,
    pub lifetime_lamports_deployed: u64,
    pub streak: u32,
    pub freezes_left: u8,
    pub last_shift_day: i64,
    /// v1.1 @336: 1 from `arm_shift` to `end_shift`.
    pub shift_open: bool,
    /// v1.1 @337: reason recorded by BREAK / a shift-interrupting FREEZE.
    pub break_reason: u8,
    /// v1.1 @338: canonical bump of ORE `["automation", authority]` (`dig` re-derives with it).
    pub ore_automation_bump: u8,
    /// v1.1 @339: canonical bump of ORE `["miner", authority]`.
    pub ore_miner_bump: u8,
    /// v1.1 @344: unix time of arm (becomes `ShiftLog.start_ts`).
    pub shift_start_ts: i64,
}

impl Default for Rig {
    fn default() -> Self {
        Rig {
            bump: 0,
            authority: Address::default(),
            p256_pubkey: [0; 33],
            attestation_level: 0,
            tier: 0,
            state: RigState::Idle,
            sgt_mint: Address::default(),
            attestation_expiry_slot: 0,
            cap_week: 0,
            cap_shift: 0,
            cap_round: 0,
            cap_max_cost: 0,
            caps_expiry_ts: 0,
            plan_max_ev_cost: 0,
            plan_dig_lamports: 0,
            plan_split_tiles: 0,
            plan_solo_tiles: 0,
            plan_lease_rounds: 0,
            plan_flags: 0,
            plan_window_start_ts: 0,
            plan_window_end_ts: 0,
            shift_id: 0,
            hb_counter: 0,
            lease_from_round: 0,
            lease_to_round: 0,
            gap_count: 0,
            spent_shift: 0,
            spent_week: 0,
            week_start_ts: 0,
            last_dug_round: 0,
            shift_start_round: 0,
            shift_dark_rounds: 0,
            shift_rounds_dug: 0,
            lifetime_dark_rounds: 0,
            lifetime_rounds_dug: 0,
            lifetime_lamports_deployed: 0,
            streak: 0,
            freezes_left: 0,
            last_shift_day: 0,
            shift_open: false,
            break_reason: 0,
            ore_automation_bump: 0,
            ore_miner_bump: 0,
            shift_start_ts: 0,
        }
    }
}

/// `plan_flags` bit 0: focus-only, never deploys (heartbeats still recorded).
pub const PLAN_FLAG_FOCUS_ONLY: u8 = 1;
/// `plan_flags` bit 1: day shift (`ShiftLog.mode` 1).
pub const PLAN_FLAG_DAY: u8 = 2;
/// Byte offset of `Rig.state` (memcmp filter target).
pub const RIG_STATE_OFFSET: usize = 75;
/// Byte offset of `Rig.shift_open` (memcmp filter target).
pub const RIG_SHIFT_OPEN_OFFSET: usize = 336;

impl Rig {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        check_header(program_id, owner, data, TAG_RIG, RIG_LEN)?;
        let e = || AccountError::WrongSize { expected: RIG_LEN, got: data.len() };
        Ok(Rig {
            bump: read_u8(data, 2).ok_or_else(e)?,
            authority: read_address(data, 8).ok_or_else(e)?,
            p256_pubkey: read_array::<33>(data, 40).ok_or_else(e)?,
            attestation_level: read_u8(data, 73).ok_or_else(e)?,
            tier: read_u8(data, 74).ok_or_else(e)?,
            state: RigState::from_u8(read_u8(data, RIG_STATE_OFFSET).ok_or_else(e)?),
            sgt_mint: read_address(data, 80).ok_or_else(e)?,
            attestation_expiry_slot: read_u64(data, 112).ok_or_else(e)?,
            cap_week: read_u64(data, 120).ok_or_else(e)?,
            cap_shift: read_u64(data, 128).ok_or_else(e)?,
            cap_round: read_u64(data, 136).ok_or_else(e)?,
            cap_max_cost: read_u64(data, 144).ok_or_else(e)?,
            caps_expiry_ts: read_i64(data, 152).ok_or_else(e)?,
            plan_max_ev_cost: read_u64(data, 160).ok_or_else(e)?,
            plan_dig_lamports: read_u64(data, 168).ok_or_else(e)?,
            plan_split_tiles: read_u8(data, 176).ok_or_else(e)?,
            plan_solo_tiles: read_u8(data, 177).ok_or_else(e)?,
            plan_lease_rounds: read_u8(data, 178).ok_or_else(e)?,
            plan_flags: read_u8(data, 179).ok_or_else(e)?,
            plan_window_start_ts: read_i64(data, 184).ok_or_else(e)?,
            plan_window_end_ts: read_i64(data, 192).ok_or_else(e)?,
            shift_id: read_u64(data, 200).ok_or_else(e)?,
            hb_counter: read_u64(data, 208).ok_or_else(e)?,
            lease_from_round: read_u64(data, 216).ok_or_else(e)?,
            lease_to_round: read_u64(data, 224).ok_or_else(e)?,
            gap_count: read_u32(data, 232).ok_or_else(e)?,
            spent_shift: read_u64(data, 240).ok_or_else(e)?,
            spent_week: read_u64(data, 248).ok_or_else(e)?,
            week_start_ts: read_i64(data, 256).ok_or_else(e)?,
            last_dug_round: read_u64(data, 264).ok_or_else(e)?,
            shift_start_round: read_u64(data, 272).ok_or_else(e)?,
            shift_dark_rounds: read_u64(data, 280).ok_or_else(e)?,
            shift_rounds_dug: read_u64(data, 288).ok_or_else(e)?,
            lifetime_dark_rounds: read_u64(data, 296).ok_or_else(e)?,
            lifetime_rounds_dug: read_u64(data, 304).ok_or_else(e)?,
            lifetime_lamports_deployed: read_u64(data, 312).ok_or_else(e)?,
            streak: read_u32(data, 320).ok_or_else(e)?,
            freezes_left: read_u8(data, 324).ok_or_else(e)?,
            last_shift_day: read_i64(data, 328).ok_or_else(e)?,
            shift_open: read_u8(data, RIG_SHIFT_OPEN_OFFSET).ok_or_else(e)? != 0,
            break_reason: read_u8(data, 337).ok_or_else(e)?,
            ore_automation_bump: read_u8(data, 338).ok_or_else(e)?,
            ore_miner_bump: read_u8(data, 339).ok_or_else(e)?,
            shift_start_ts: read_i64(data, 344).ok_or_else(e)?,
        })
    }

    /// Serialize to the 384-byte layout (tests, LiteSVM fixtures).
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; RIG_LEN];
        d[0] = TAG_RIG;
        d[1] = ACCOUNT_VERSION;
        d[2] = self.bump;
        let mut put = |off: usize, b: &[u8]| d[off..off + b.len()].copy_from_slice(b);
        put(8, self.authority.as_ref());
        put(40, &self.p256_pubkey);
        put(73, &[self.attestation_level, self.tier, self.state.as_u8()]);
        put(80, self.sgt_mint.as_ref());
        put(112, &self.attestation_expiry_slot.to_le_bytes());
        put(120, &self.cap_week.to_le_bytes());
        put(128, &self.cap_shift.to_le_bytes());
        put(136, &self.cap_round.to_le_bytes());
        put(144, &self.cap_max_cost.to_le_bytes());
        put(152, &self.caps_expiry_ts.to_le_bytes());
        put(160, &self.plan_max_ev_cost.to_le_bytes());
        put(168, &self.plan_dig_lamports.to_le_bytes());
        put(
            176,
            &[self.plan_split_tiles, self.plan_solo_tiles, self.plan_lease_rounds, self.plan_flags],
        );
        put(184, &self.plan_window_start_ts.to_le_bytes());
        put(192, &self.plan_window_end_ts.to_le_bytes());
        put(200, &self.shift_id.to_le_bytes());
        put(208, &self.hb_counter.to_le_bytes());
        put(216, &self.lease_from_round.to_le_bytes());
        put(224, &self.lease_to_round.to_le_bytes());
        put(232, &self.gap_count.to_le_bytes());
        put(240, &self.spent_shift.to_le_bytes());
        put(248, &self.spent_week.to_le_bytes());
        put(256, &self.week_start_ts.to_le_bytes());
        put(264, &self.last_dug_round.to_le_bytes());
        put(272, &self.shift_start_round.to_le_bytes());
        put(280, &self.shift_dark_rounds.to_le_bytes());
        put(288, &self.shift_rounds_dug.to_le_bytes());
        put(296, &self.lifetime_dark_rounds.to_le_bytes());
        put(304, &self.lifetime_rounds_dug.to_le_bytes());
        put(312, &self.lifetime_lamports_deployed.to_le_bytes());
        put(320, &self.streak.to_le_bytes());
        put(324, &[self.freezes_left]);
        put(328, &self.last_shift_day.to_le_bytes());
        put(
            RIG_SHIFT_OPEN_OFFSET,
            &[u8::from(self.shift_open), self.break_reason, self.ore_automation_bump, self.ore_miner_bump],
        );
        put(344, &self.shift_start_ts.to_le_bytes());
        d
    }

    /// True if a lease granted in this shift covers `round_id`: `lease_to != 0 &&
    /// lease_from ≤ round ≤ lease_to` (the program's `lease_covers`).
    pub fn lease_covers(&self, round_id: u64) -> bool {
        self.lease_to_round != 0 && self.lease_from_round <= round_id && round_id <= self.lease_to_round
    }

    /// `plan_flags` bit 0.
    pub fn focus_only(&self) -> bool {
        self.plan_flags & PLAN_FLAG_FOCUS_ONLY != 0
    }

    /// `split + solo` as requested by the plan (u16, cannot overflow). The squares actually
    /// chosen, `k = popcount(mask)`, can be fewer: see [`ore::tiles_available`].
    pub fn tiles(&self) -> u16 {
        u16::from(self.plan_split_tiles) + u16::from(self.plan_solo_tiles)
    }

    /// `ShiftLog.mode` this shift will get: 2 focus-only (wins), 1 day, 0 night.
    pub fn mode(&self) -> u8 {
        if self.focus_only() {
            2
        } else if self.plan_flags & PLAN_FLAG_DAY != 0 {
            1
        } else {
            0
        }
    }
}

/// `ShiftLog` (128 bytes, `["shift", rig, shift_id]`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct ShiftLog {
    pub rig: Address,
    pub shift_id: u64,
    pub start_round: u64,
    pub end_round: u64,
    pub dark_rounds: u64,
    pub rounds_dug: u64,
    /// `spent_shift`: squares plus fees.
    pub lamports_deployed: u64,
    pub break_reason: u8,
    /// 0 night, 1 day, 2 focus-only.
    pub mode: u8,
    pub start_ts: i64,
    pub end_ts: i64,
}

impl ShiftLog {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        check_header(program_id, owner, data, TAG_SHIFT_LOG, SHIFT_LOG_LEN)?;
        let e = || AccountError::WrongSize { expected: SHIFT_LOG_LEN, got: data.len() };
        Ok(ShiftLog {
            rig: read_address(data, 8).ok_or_else(e)?,
            shift_id: read_u64(data, 40).ok_or_else(e)?,
            start_round: read_u64(data, 48).ok_or_else(e)?,
            end_round: read_u64(data, 56).ok_or_else(e)?,
            dark_rounds: read_u64(data, 64).ok_or_else(e)?,
            rounds_dug: read_u64(data, 72).ok_or_else(e)?,
            lamports_deployed: read_u64(data, 80).ok_or_else(e)?,
            break_reason: read_u8(data, 88).ok_or_else(e)?,
            mode: read_u8(data, 89).ok_or_else(e)?,
            start_ts: read_i64(data, 96).ok_or_else(e)?,
            end_ts: read_i64(data, 104).ok_or_else(e)?,
        })
    }
}

/// Fields of a HEARTBEAT that the phone chooses (the rest comes from state).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct HeartbeatFields {
    /// Strictly increasing per rig across all kinds.
    pub counter: u64,
    /// Must equal `rig.shift_id`.
    pub shift_id: u64,
    /// ORE `Board.round_id` at signing.
    pub round_id: u64,
    /// Requested lease length (effective: `min(lease_rounds, plan_lease_rounds)`).
    pub lease_rounds: u8,
}

/// `"HDv1" | program_id | rig | kind=1 | counter u64 | shift_id u64 | round_id u64 | lease_rounds u8`.
pub fn heartbeat_preimage(
    program_id: &Address,
    rig: &Address,
    hb: &HeartbeatFields,
) -> [u8; HEARTBEAT_PREIMAGE_LEN] {
    let mut m = [0u8; HEARTBEAT_PREIMAGE_LEN];
    m[0..4].copy_from_slice(&DOMAIN);
    m[4..36].copy_from_slice(program_id.as_ref());
    m[36..68].copy_from_slice(rig.as_ref());
    m[68] = KIND_HEARTBEAT;
    m[69..77].copy_from_slice(&hb.counter.to_le_bytes());
    m[77..85].copy_from_slice(&hb.shift_id.to_le_bytes());
    m[85..93].copy_from_slice(&hb.round_id.to_le_bytes());
    m[93] = hb.lease_rounds;
    m
}

/// `"HDv1" | program_id | rig | kind (2 BREAK, 3 FREEZE) | counter u64 | shift_id u64 | reason u8`.
pub fn break_preimage(
    program_id: &Address,
    rig: &Address,
    kind: u8,
    counter: u64,
    shift_id: u64,
    reason: u8,
) -> [u8; BREAK_PREIMAGE_LEN] {
    let mut m = [0u8; BREAK_PREIMAGE_LEN];
    m[0..4].copy_from_slice(&DOMAIN);
    m[4..36].copy_from_slice(program_id.as_ref());
    m[36..68].copy_from_slice(rig.as_ref());
    m[68] = kind;
    m[69..77].copy_from_slice(&counter.to_le_bytes());
    m[77..85].copy_from_slice(&shift_id.to_le_bytes());
    m[85] = reason;
    m
}

/// PLAN fields (`arm_shift` without the wallet).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct PlanFields {
    pub counter: u64,
    pub max_ev_cost: u64,
    pub dig_lamports: u64,
    pub split: u8,
    pub solo: u8,
    pub lease: u8,
    pub flags: u8,
    pub window_start: i64,
    pub window_end: i64,
}

/// `"HDv1" | program_id | rig | kind=4 | counter u64 | max_ev_cost u64 | dig_lamports u64 |
/// split u8 | solo u8 | lease u8 | flags u8 | window_start i64 | window_end i64`.
pub fn plan_preimage(program_id: &Address, rig: &Address, p: &PlanFields) -> [u8; PLAN_PREIMAGE_LEN] {
    let mut m = [0u8; PLAN_PREIMAGE_LEN];
    m[0..4].copy_from_slice(&DOMAIN);
    m[4..36].copy_from_slice(program_id.as_ref());
    m[36..68].copy_from_slice(rig.as_ref());
    m[68] = KIND_PLAN;
    m[69..77].copy_from_slice(&p.counter.to_le_bytes());
    m[77..85].copy_from_slice(&p.max_ev_cost.to_le_bytes());
    m[85..93].copy_from_slice(&p.dig_lamports.to_le_bytes());
    m[93..97].copy_from_slice(&[p.split, p.solo, p.lease, p.flags]);
    m[97..105].copy_from_slice(&p.window_start.to_le_bytes());
    m[105..113].copy_from_slice(&p.window_end.to_le_bytes());
    m
}

/// The 32-byte message the phone signs and the precompile verifies: `SHA-256(preimage)`.
pub fn digest(preimage: &[u8]) -> [u8; 32] {
    Sha256::digest(preimage).into()
}

/// The lease a heartbeat grants: `[round_id, round_id + min(lease_rounds, plan_lease_rounds) − 1]`.
/// `None` when the effective length is 0 (an empty lease) or on overflow.
pub fn lease_range(hb_round_id: u64, lease_rounds: u8, plan_lease_rounds: u8) -> Option<(u64, u64)> {
    let len = lease_rounds.min(plan_lease_rounds);
    if len == 0 {
        return None;
    }
    let to = hb_round_id.checked_add(u64::from(len).checked_sub(1)?)?;
    Some((hb_round_id, to))
}

/// What applying a verified heartbeat does to the rig's lease (INTERFACE §6.3, the program's
/// `logic::grant_lease`, integer for integer).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LeaseGrant {
    /// New `lease_from_round`.
    pub from: u64,
    /// New `lease_to_round`.
    pub to: u64,
    /// Rounds newly covered inside the shift (added to the dark rounds).
    pub dark_added: u64,
    /// Uncovered rounds skipped over inside the shift (added to the gaps).
    pub gap_added: u64,
}

/// Grant `[hb_round, hb_round + lease − 1]` on top of `[cur_from, cur_to]`. Leases only move
/// forward: a heartbeat whose lease ends at or before `cur_to` changes nothing (its counter is
/// still consumed). `cur_to == 0` means no lease yet in this shift. `lease` is the effective
/// length `min(lease_rounds, plan_lease_rounds)`; `None` for 0 (InvalidHeartbeat) or overflow.
pub fn grant_lease(cur_from: u64, cur_to: u64, shift_start: u64, hb_round: u64, lease: u8) -> Option<LeaseGrant> {
    let span = u64::from(lease).checked_sub(1)?;
    let new_to = hb_round.checked_add(span)?;
    if cur_to != 0 && new_to <= cur_to {
        return Some(LeaseGrant { from: cur_from, to: cur_to, dark_added: 0, gap_added: 0 });
    }
    let covered_end = if cur_to != 0 && cur_to >= shift_start { cur_to } else { shift_start.saturating_sub(1) };
    let start = hb_round.max(shift_start);
    let first_new = start.max(covered_end.saturating_add(1));
    let dark_added = if new_to >= first_new { new_to.saturating_sub(first_new).saturating_add(1) } else { 0 };
    let gap_added = start.saturating_sub(covered_end.saturating_add(1));
    Some(LeaseGrant { from: hb_round, to: new_to, dark_added, gap_added })
}

/// The lease the rig holds after `hb` is applied (`None` if `hb` is invalid for it).
pub fn lease_after(rig: &Rig, hb: &HeartbeatFields) -> Option<LeaseGrant> {
    grant_lease(
        rig.lease_from_round,
        rig.lease_to_round,
        rig.shift_start_round,
        hb.round_id,
        hb.lease_rounds.min(rig.plan_lease_rounds),
    )
}

/// One per-rig `dig` / `record_heartbeats` entry (20 bytes):
/// `hb_ix u8 | hb_sig_index u8 | counter u64 | round_id u64 | lease_rounds u8 | _pad u8`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DigEntry {
    /// Absolute top-level index of the Secp256r1SigVerify instruction, or [`HB_REUSE_LEASE`].
    pub hb_ix: u8,
    /// Entry within that precompile instruction.
    pub hb_sig_index: u8,
    /// Heartbeat counter (0 when reusing the lease).
    pub counter: u64,
    /// Heartbeat round id (0 when reusing the lease).
    pub round_id: u64,
    /// Heartbeat lease length (0 when reusing the lease).
    pub lease_rounds: u8,
}

impl DigEntry {
    /// An entry that reuses the rig's current lease.
    pub const fn reuse_lease() -> Self {
        DigEntry { hb_ix: HB_REUSE_LEASE, hb_sig_index: 0, counter: 0, round_id: 0, lease_rounds: 0 }
    }

    /// Wire form.
    pub fn encode(&self) -> [u8; DIG_ENTRY_LEN] {
        let mut b = [0u8; DIG_ENTRY_LEN];
        b[0] = self.hb_ix;
        b[1] = self.hb_sig_index;
        b[2..10].copy_from_slice(&self.counter.to_le_bytes());
        b[10..18].copy_from_slice(&self.round_id.to_le_bytes());
        b[18] = self.lease_rounds;
        b[19] = 0;
        b
    }

    /// Parse one entry.
    pub fn decode(b: &[u8]) -> Option<Self> {
        if b.len() != DIG_ENTRY_LEN {
            return None;
        }
        Some(DigEntry {
            hb_ix: read_u8(b, 0)?,
            hb_sig_index: read_u8(b, 1)?,
            counter: read_u64(b, 2)?,
            round_id: read_u64(b, 10)?,
            lease_rounds: read_u8(b, 18)?,
        })
    }

    /// Parse `n u8 | n × entry` (the data after the tag of `dig` / `record_heartbeats`).
    pub fn decode_list(body: &[u8]) -> Option<Vec<DigEntry>> {
        let (&n, rest) = body.split_first()?;
        if n == 0 || rest.len() != usize::from(n) * DIG_ENTRY_LEN {
            return None;
        }
        rest.chunks_exact(DIG_ENTRY_LEN).map(DigEntry::decode).collect()
    }
}

/// The four accounts `dig` takes per rig, in order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct RigAccounts {
    /// Rig PDA (w).
    pub rig: Address,
    /// `rig.authority` (w) — always from the Rig account, never from anything else.
    pub authority: Address,
    /// `["automation", authority]` under ORE (w).
    pub automation: Address,
    /// `["miner", authority]` under ORE (w).
    pub miner: Address,
}

impl RigAccounts {
    /// Derive the ORE accounts from the rig's authority.
    pub fn derive(rig: Address, authority: Address) -> Self {
        RigAccounts {
            rig,
            authority,
            automation: ore::automation_pda(&authority),
            miner: ore::miner_pda(&authority),
        }
    }
}

/// Errors building a batched instruction.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum DigBuildError {
    /// No rigs.
    #[error("empty batch")]
    Empty,
    /// More rigs than one instruction carries.
    #[error("too many rigs for one instruction: {0}")]
    TooMany(usize),
    /// A rig appears twice (the program fails the tx with `DuplicateRig`).
    #[error("duplicate rig {0}")]
    DuplicateRig(Address),
}

fn check_batch<'a>(rigs: impl ExactSizeIterator<Item = &'a Address>, max: usize) -> Result<u8, DigBuildError> {
    let len = rigs.len();
    if len == 0 {
        return Err(DigBuildError::Empty);
    }
    if len > max {
        return Err(DigBuildError::TooMany(len));
    }
    let n = u8::try_from(len).map_err(|_| DigBuildError::TooMany(len))?;
    let mut seen = std::collections::HashSet::with_capacity(len);
    for a in rigs {
        if !seen.insert(*a) {
            return Err(DigBuildError::DuplicateRig(*a));
        }
    }
    Ok(n)
}

/// Build `heads_down::dig` (tag 6) with the fixed account order of INTERFACE §5:
///
/// ```text
/// 0 cranker (s,w)   4 ore_config (w)   8 ore_program         12.. per rig i:
/// 1 config          5 round (w)        9 entropy_var (w)          rig (w), authority (w),
/// 2 executor (w)    6 treasury (w)    10 entropy_program          automation (w), miner (w)
/// 3 board (w)       7 system_program  11 instructions sysvar
/// ```
///
/// `round` is the Round PDA for the `Board.round_id` the tx is meant to land in. The program
/// takes 1..=32 rigs; the builder refuses more than 255 (the `u8` count) so callers can size
/// batches themselves.
pub fn dig_ix(
    program_id: &Address,
    cranker: &Address,
    round: &Address,
    rigs: &[(RigAccounts, DigEntry)],
) -> Result<Instruction, DigBuildError> {
    let n = check_batch(rigs.iter().map(|(a, _)| &a.rig), usize::from(u8::MAX))?;
    let mut accounts = Vec::with_capacity(DIG_FIXED_ACCOUNTS + DIG_ACCOUNTS_PER_RIG * rigs.len());
    accounts.extend([
        AccountMeta::new(*cranker, true),
        AccountMeta::new_readonly(config_pda(program_id).0, false),
        AccountMeta::new(executor_pda(program_id).0, false),
        AccountMeta::new(ore::BOARD_ADDRESS, false),
        AccountMeta::new(ore::CONFIG_ADDRESS, false),
        AccountMeta::new(*round, false),
        AccountMeta::new(ore::TREASURY_ADDRESS, false),
        AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        AccountMeta::new_readonly(ore::ORE_PROGRAM_ID, false),
        AccountMeta::new(ore::VAR_ADDRESS, false),
        AccountMeta::new_readonly(ore::ENTROPY_PROGRAM_ID, false),
        AccountMeta::new_readonly(INSTRUCTIONS_SYSVAR_ID, false),
    ]);
    let mut data = Vec::with_capacity(2 + DIG_ENTRY_LEN * rigs.len());
    data.push(IX_DIG);
    data.push(n);
    for (a, e) in rigs {
        accounts.extend([
            AccountMeta::new(a.rig, false),
            AccountMeta::new(a.authority, false),
            AccountMeta::new(a.automation, false),
            AccountMeta::new(a.miner, false),
        ]);
        data.extend_from_slice(&e.encode());
    }
    Ok(Instruction { program_id: *program_id, accounts, data })
}

/// Build `heads_down::record_heartbeats` (tag 7): `0 [] ORE Board | 1 [] instructions sysvar |
/// 2.. [w] rig_i`, data `7 | n | n × entry` (every entry must name a precompile, never 0xFF).
pub fn record_heartbeats_ix(program_id: &Address, rigs: &[(Address, DigEntry)]) -> Result<Instruction, DigBuildError> {
    let n = check_batch(rigs.iter().map(|(a, _)| a), MAX_RIGS_PER_IX)?;
    let mut accounts = Vec::with_capacity(2 + rigs.len());
    accounts.push(AccountMeta::new_readonly(ore::BOARD_ADDRESS, false));
    accounts.push(AccountMeta::new_readonly(INSTRUCTIONS_SYSVAR_ID, false));
    let mut data = Vec::with_capacity(2 + DIG_ENTRY_LEN * rigs.len());
    data.push(IX_RECORD_HEARTBEATS);
    data.push(n);
    for (rig, e) in rigs {
        accounts.push(AccountMeta::new(*rig, false));
        data.extend_from_slice(&e.encode());
    }
    Ok(Instruction { program_id: *program_id, accounts, data })
}

/// A phone-signed shift signal (the P-256 path of `break_shift` / `freeze_rig`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SignalKind {
    /// BREAK: `break_shift` (tag 8), message kind 2.
    Break,
    /// FREEZE: `freeze_rig` (tag 9), message kind 3.
    Freeze,
}

impl SignalKind {
    /// The preimage `kind` byte.
    pub fn message_kind(self) -> u8 {
        match self {
            SignalKind::Break => KIND_BREAK,
            SignalKind::Freeze => KIND_FREEZE,
        }
    }

    /// The instruction tag.
    pub fn ix_tag(self) -> u8 {
        match self {
            SignalKind::Break => IX_BREAK_SHIFT,
            SignalKind::Freeze => IX_FREEZE_RIG,
        }
    }

    /// `break` / `freeze` (also the intake frame `type`).
    pub fn name(self) -> &'static str {
        match self {
            SignalKind::Break => "break",
            SignalKind::Freeze => "freeze",
        }
    }

    /// Whether `reason` is valid for this kind: BREAK takes 1, 2, 4, 5, 6, 7, 8 (the program
    /// refuses anything else); a FREEZE frame carries 3 (contract A; the program binds any byte).
    pub fn reason_ok(self, r: u8) -> bool {
        match self {
            SignalKind::Break => reason::BREAK_REASONS.contains(&r),
            SignalKind::Freeze => r == reason::FREEZE,
        }
    }
}

/// `break_shift` / `freeze_rig` on the P-256 path (INTERFACE §5, vectors `break_shift_p256` /
/// `freeze_rig_p256`): accounts `0 [w] rig | 1 [] authority (= rig.authority, not a signer) |
/// 2 [] instructions sysvar`, data `tag | mode 1 | reason | counter u64 | p256_ix | p256_sig_index`
/// (13 bytes, counter first). The fee payer is not an instruction account.
#[allow(clippy::too_many_arguments)]
pub fn signal_p256_ix(
    program_id: &Address,
    kind: SignalKind,
    rig: &Address,
    authority: &Address,
    reason: u8,
    counter: u64,
    p256_ix: u8,
    p256_sig_index: u8,
) -> Instruction {
    let mut data = Vec::with_capacity(13);
    data.extend_from_slice(&[kind.ix_tag(), MODE_P256, reason]);
    data.extend_from_slice(&counter.to_le_bytes());
    data.extend_from_slice(&[p256_ix, p256_sig_index]);
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*rig, false),
            AccountMeta::new_readonly(*authority, false),
            AccountMeta::new_readonly(INSTRUCTIONS_SYSVAR_ID, false),
        ],
        data,
    }
}

/// `end_shift` (tag 11): `0 [s,w] caller (pays the ShiftLog rent) | 1 [w] rig |
/// 2 [w] ShiftLog ["shift", rig, shift_id] | 3 [] ORE Board | 4 [] System`. Anyone may call it
/// once `now > plan_window_end_ts` and `lease_to_round < Board.round_id`.
pub fn end_shift_ix(program_id: &Address, caller: &Address, rig: &Address, shift_id: u64) -> Instruction {
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*caller, true),
            AccountMeta::new(*rig, false),
            AccountMeta::new(shift_log_pda(program_id, rig, shift_id).0, false),
            AccountMeta::new_readonly(ore::BOARD_ADDRESS, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data: vec![IX_END_SHIFT],
    }
}

/// `ProgramError::Custom` codes the crank acts on (INTERFACE §8 and §11.10).
pub mod code {
    /// A heartbeat for a future round, or `lease_rounds == 0`.
    pub const INVALID_HEARTBEAT: u32 = 6;
    /// P-256 counter at or below `rig.hb_counter`.
    pub const STALE_HEARTBEAT: u32 = 7;
    /// No lease covers the round (a check-in: no heartbeat for this round landed in it).
    pub const LEASE_EXPIRED: u32 = 8;
    /// Idle, Broken, a closed rig, or no open shift.
    pub const RIG_NOT_ARMED: u32 = 13;
    /// A duplicate rig or seat in one batch.
    pub const DUPLICATE_RIG: u32 = 22;
    /// The instruction does not fit the rig's state.
    pub const INVALID_RIG_STATE: u32 = 24;
    /// `settle_stack` while `Board.round_id <= end_round`.
    pub const STACK_NOT_ENDED: u32 = 37;
    /// A check-in outside the window or on a table that is not Open; settle twice.
    pub const INVALID_STACK_STATE: u32 = 38;
    /// A seat of another table or rig; a missing or repeated seat at settle.
    pub const STACK_SEAT_MISMATCH: u32 = 39;
    /// Check-in result: the rig is in another shift than the seat's.
    pub const STACK_SHIFT_MISMATCH: u32 = 40;
    /// Check-in result: the rig's plan allows leases of more than one round.
    pub const STACK_LEASE_TOO_LONG: u32 = 41;
    /// Check-in result: the bound shift recorded a BREAK or FREEZE.
    pub const STACK_SEAT_BROKEN: u32 = 42;
    /// A release or forfeit with the other outcome, or before the shift is sealed.
    pub const BOND_NOT_RESOLVABLE: u32 = 43;
    /// A claim at or after `expiry_ts`; a refund before it.
    pub const GIFT_EXPIRY: u32 = 45;
}

/// `ProgramError::Custom` codes of `heads_down` (INTERFACE §8, and §11.10 for the v1.2 SKR
/// codes 32..=48), including the precise `p256-introspect` codes (`0x2560_00xx`) that
/// `RigSkipped.error` and `StackCheckin.result` carry unchanged, and the builtin-error
/// encoding `u32::MAX - k` of `skip_code`.
pub fn error_name(code: u32) -> &'static str {
    match code {
        0 => "InvalidInstruction",
        1 => "CostGate",
        2 => "InvalidExecutor",
        3 => "InvalidOreAccount",
        4 => "InvalidAccountTag",
        5 => "Unauthorized",
        6 => "InvalidHeartbeat",
        7 => "StaleHeartbeat",
        8 => "LeaseExpired",
        9 => "AlreadyDugRound",
        10 => "CapsExpired",
        11 => "OutsideWindow",
        12 => "BudgetExhausted",
        13 => "RigNotArmed",
        14 => "RigFrozen",
        15 => "PlanExceedsCaps",
        16 => "InvalidSgt",
        17 => "SeatTaken",
        18 => "Paused",
        19 => "TimelockNotElapsed",
        20 => "MathOverflow",
        21 => "InvalidAttestation",
        22 => "DuplicateRig",
        23 => "StrategyMismatch",
        24 => "InvalidRigState",
        25 => "RoundNotActive",
        26 => "MinerNotCheckpointed",
        27 => "MotherlodeCondition",
        28 => "InsufficientAutomationBalance",
        29 => "OreNoOp",
        30 => "FocusOnly",
        31 => "ExecutorUnderfunded",
        // v1.2 (SKR), INTERFACE §11.10.
        32 => "InvalidTokenAccount",
        33 => "AmountOutOfRange",
        34 => "InvalidStackParams",
        35 => "StackJoinClosed",
        36 => "StackIneligible",
        37 => "StackNotEnded",
        38 => "InvalidStackState",
        39 => "StackSeatMismatch",
        40 => "StackShiftMismatch",
        41 => "StackLeaseTooLong",
        42 => "StackSeatBroken",
        43 => "BondNotResolvable",
        44 => "GiftNotClaimable",
        45 => "GiftExpiry",
        46 => "AuctionEmpty",
        47 => "PriceAboveMax",
        48 => "BuryMismatch",
        0x2560_0001 => "P256InvalidInstructionsSysvar",
        0x2560_0002 => "P256MalformedInstructionsSysvar",
        0x2560_0003 => "P256InstructionIndexOutOfBounds",
        0x2560_0004 => "P256NotSecp256r1Instruction",
        0x2560_0005 => "P256InvalidSignatureCount",
        0x2560_0006 => "P256TruncatedOffsets",
        0x2560_0007 => "P256ForeignInstructionIndex",
        0x2560_0008 => "P256OffsetOutOfBounds",
        0x2560_0009 => "P256SignatureIndexOutOfBounds",
        0x2560_000a => "P256HighS",
        0x2560_000b => "P256ScalarOutOfRange",
        0x2560_000c => "P256InvalidPublicKeyEncoding",
        0x2560_000d => "P256PublicKeyMismatch",
        0x2560_000e => "P256MessageMismatch",
        c if c & 0xFFFF_0000 == 0x2560_0000 => "P256Introspect",
        c if c & 0xFFFF_0000 == 0x5347_0000 => "SgtVerify",
        0xFFFF_FFFE => "BuiltinInvalidArgument",
        0xFFFF_FFFD => "BuiltinInvalidInstructionData",
        0xFFFF_FFFC => "BuiltinInvalidAccountData",
        0xFFFF_FFFB => "BuiltinAccountBorrowFailed",
        0xFFFF_FFFA => "BuiltinMissingRequiredSignature",
        0xFFFF_FFF9 => "BuiltinArithmeticOverflow",
        0xFFFF_FFFF => "Builtin",
        _ => "Unknown",
    }
}

/// `ShiftLog.break_reason` / BREAK reason name.
pub fn reason_name(r: u8) -> &'static str {
    match r {
        reason::COMPLETED => "completed",
        reason::PICKUP => "pickup",
        reason::SCREEN_ON => "screen_on",
        reason::FREEZE => "freeze",
        reason::LEASE_LAPSE => "lease_lapse",
        reason::BUDGET => "budget",
        reason::MANUAL => "manual",
        reason::UNPLUGGED => "unplugged",
        reason::UNLOCKED => "unlocked",
        _ => "unknown",
    }
}

/// `FocusBondForfeited.reason`: the ShiftLog reason, or 255 `abandoned` (the bonded shift
/// can never be sealed).
pub fn bond_reason_name(r: u8) -> &'static str {
    if r == BOND_ABANDONED {
        "abandoned"
    } else {
        reason_name(r)
    }
}

/// `StackCheckin.result`: `counted` for 0, else the error name of the reason the round did
/// not count (INTERFACE §11.5).
pub fn checkin_result_name(result: u32) -> &'static str {
    if result == 0 {
        "counted"
    } else {
        error_name(result)
    }
}

/// `ShiftLog.mode` name.
pub fn mode_name(m: u8) -> &'static str {
    match m {
        0 => "night",
        1 => "day",
        2 => "focus_only",
        _ => "unknown",
    }
}

/// Events logged with `sol_log_data` (one slice, byte 0 = tag), INTERFACE §7 (tags 1..=10)
/// and §11.9 (the v1.2 SKR tags 11..=23). A tag's length never changes, so each tag is
/// decoded by its exact length.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HdEvent {
    /// Tag 1 (61 bytes).
    RigDug {
        /// Rig.
        rig: Address,
        /// Live `Board.round_id`.
        round_id: u64,
        /// SOL placed on squares, **without** the Automation fee.
        lamports: u64,
        /// Squares deployed (= squares credited).
        mask: u32,
        /// Gate value at dig time (saturated to u64).
        ema_ev: u64,
    },
    /// Tag 2 (45 bytes).
    RigSkipped {
        /// Rig.
        rig: Address,
        /// Live `Board.round_id`.
        round_id: u64,
        /// Precise error code (heads_down 0..=48 or a shared crate's code).
        error: u32,
    },
    /// Tag 3 (41 bytes).
    ShiftArmed {
        /// Rig.
        rig: Address,
        /// New shift id.
        shift_id: u64,
    },
    /// Tag 4 (66 bytes). Followed by [`HdEvent::ShiftEndedV2`] in the same instruction;
    /// [`events_from_logs`] drops it when the V2 is present.
    ShiftEnded {
        /// Rig.
        rig: Address,
        /// Shift.
        shift_id: u64,
        /// Dark rounds.
        dark_rounds: u64,
        /// Rounds dug.
        rounds_dug: u64,
        /// `spent_shift` (squares plus fees).
        lamports: u64,
        /// Break reason.
        reason: u8,
    },
    /// Tag 5 (73 bytes).
    SeekerVerified {
        /// Rig.
        rig: Address,
        /// SGT mint.
        sgt_mint: Address,
        /// Member number.
        member_number: u64,
    },
    /// Tag 6 (67 bytes).
    RigRegistered {
        /// Rig.
        rig: Address,
        /// Wallet.
        authority: Address,
        /// Tier.
        tier: u8,
        /// Attestation level.
        attestation_level: u8,
    },
    /// Tag 7 (33 bytes).
    RigClosed {
        /// Rig.
        rig: Address,
    },
    /// Tag 8 (49 bytes), one per rig `record_heartbeats` accepted.
    HeartbeatsRecorded {
        /// Rig.
        rig: Address,
        /// Live `Board.round_id`.
        round_id: u64,
        /// Dark rounds the lease added.
        dark_rounds_added: u64,
    },
    /// Tag 9 (42 bytes): a BREAK, or a FREEZE that interrupted an open shift (reason 3).
    ShiftBroken {
        /// Rig.
        rig: Address,
        /// Shift.
        shift_id: u64,
        /// Reason.
        reason: u8,
    },
    /// Tag 10 (83 bytes): tag 4's fields plus the rounds and the mode.
    ShiftEndedV2 {
        /// Rig.
        rig: Address,
        /// Shift.
        shift_id: u64,
        /// Dark rounds.
        dark_rounds: u64,
        /// Rounds dug.
        rounds_dug: u64,
        /// `spent_shift` (squares plus fees).
        lamports: u64,
        /// Break reason.
        reason: u8,
        /// `Board.round_id` at arm.
        start_round: u64,
        /// `Board.round_id` at `end_shift`.
        end_round: u64,
        /// 0 night, 1 day, 2 focus-only.
        mode: u8,
    },
    /// Tag 11 (103 bytes), v1.2: `open_stack`.
    StackOpened {
        /// StackTable PDA.
        table: Address,
        /// Host wallet.
        host: Address,
        /// Host-chosen id.
        table_id: u64,
        /// SKR base units per seat.
        bond: u64,
        /// First ORE round of the window.
        start_round: u64,
        /// Last ORE round of the window (inclusive).
        end_round: u64,
        /// Window rounds a seat may miss.
        grace_gaps: u32,
        /// bit0 remote, bit1 bury-only, bit2 attested-only.
        flags: u8,
        /// Seat limit (2..=8).
        max_seats: u8,
    },
    /// Tag 12 (138 bytes), v1.2: `join_stack`.
    StackJoined {
        /// StackTable.
        table: Address,
        /// Seated rig.
        rig: Address,
        /// `rig.authority` at join.
        authority: Address,
        /// SGT mint re-verified at join (zero when the join was not SGT-verified).
        sgt_mint: Address,
        /// SKR bonded.
        bond: u64,
        /// Join order.
        seat_index: u8,
    },
    /// Tag 13 (85 bytes), v1.2: one per seat per `stack_checkin`.
    StackCheckin {
        /// StackTable.
        table: Address,
        /// Seated rig.
        rig: Address,
        /// Live `Board.round_id`.
        round_id: u64,
        /// Window rounds the seat has counted so far.
        checked_rounds: u64,
        /// 0 = the round counted; else the reason it did not (INTERFACE §11.5).
        result: u32,
    },
    /// Tag 14 (67 bytes), v1.2: `settle_stack`.
    StackSettled {
        /// StackTable.
        table: Address,
        /// `B`: every seat's bond.
        total_bonds: u64,
        /// `W`: the finishers' bonds.
        finisher_bonds: u64,
        /// Sum of the payouts.
        payouts_total: u64,
        /// SKR moved to the Bury lot.
        bury_amount: u64,
        /// Seats at the table.
        seats: u8,
        /// Seats that finished.
        finishers: u8,
    },
    /// Tag 15 (106 bytes), v1.2: `claim_stack`.
    StackClaimed {
        /// StackTable.
        table: Address,
        /// The seat's rig.
        rig: Address,
        /// The seat's wallet (the only possible recipient).
        authority: Address,
        /// SKR paid.
        amount: u64,
        /// 0 payout, 1 timeout refund.
        kind: u8,
    },
    /// Tag 16 (113 bytes), v1.2: `lock_focus_bond`.
    FocusBondLocked {
        /// FocusBond PDA.
        bond: Address,
        /// Rig.
        rig: Address,
        /// Owner wallet.
        authority: Address,
        /// The bonded shift.
        shift_id: u64,
        /// SKR locked.
        amount: u64,
    },
    /// Tag 17 (81 bytes), v1.2: `release_focus_bond`.
    FocusBondReleased {
        /// FocusBond PDA.
        bond: Address,
        /// Rig.
        rig: Address,
        /// The bonded shift.
        shift_id: u64,
        /// SKR returned to the owner.
        amount: u64,
    },
    /// Tag 18 (82 bytes), v1.2: `forfeit_focus_bond`.
    FocusBondForfeited {
        /// FocusBond PDA.
        bond: Address,
        /// Rig.
        rig: Address,
        /// The bonded shift.
        shift_id: u64,
        /// SKR moved to the Bury lot.
        amount: u64,
        /// The ShiftLog reason, or 255 "abandoned".
        reason: u8,
    },
    /// Tag 19 (114 bytes), v1.2: `create_gift`.
    GiftCreated {
        /// GiftEscrow PDA.
        gift: Address,
        /// Sender wallet.
        sender: Address,
        /// A wallet, or an SGT mint.
        recipient: Address,
        /// Lamports escrowed.
        lamports: u64,
        /// Claims before, refunds from.
        expiry_ts: i64,
        /// 0 wallet, 1 SGT mint.
        recipient_kind: u8,
    },
    /// Tag 20 (74 bytes), v1.2: `claim_gift`.
    GiftClaimed {
        /// GiftEscrow PDA.
        gift: Address,
        /// Who received the lamports.
        claimer: Address,
        /// Lamports paid.
        lamports: u64,
        /// 0 wallet, 1 SGT mint.
        recipient_kind: u8,
    },
    /// Tag 21 (73 bytes), v1.2: `refund_gift`.
    GiftRefunded {
        /// GiftEscrow PDA.
        gift: Address,
        /// The stored sender (the only possible recipient).
        sender: Address,
        /// The gift's lamports (the escrow rent returns as well).
        lamports: u64,
    },
    /// Tag 22 (66 bytes), v1.2: SKR forfeits entered the Bury lot (`settle_stack`,
    /// `forfeit_focus_bond`).
    BuryLotAdded {
        /// The table or the bond the SKR came from.
        source: Address,
        /// SKR added.
        amount: u64,
        /// The lot after the deposit.
        lot_skr: u64,
        /// Restarted auction price (ORE atoms per whole SKR).
        start_price: u64,
        /// Slot the price decay restarted.
        start_slot: u64,
        /// 1 stack, 2 bond.
        source_kind: u8,
    },
    /// Tag 23 (81 bytes), v1.2: `bury_auction_buy`.
    BuryAuctionSold {
        /// Buyer wallet.
        buyer: Address,
        /// SKR sold.
        skr_amount: u64,
        /// Price paid (ORE atoms per whole SKR).
        price: u64,
        /// ORE paid, all of it through ORE `bury`.
        ore_paid: u64,
        /// The burned 90%.
        ore_burned: u64,
        /// The 10% sent to ORE's stake program.
        ore_shared: u64,
        /// SKR left in the lot.
        lot_remaining: u64,
    },
    /// Any newer tag: kept raw.
    Other {
        /// Event tag.
        tag: u8,
        /// Raw bytes after the tag.
        body: Vec<u8>,
    },
}

impl HdEvent {
    /// The event's name as INTERFACE §7 spells it.
    pub fn name(&self) -> &'static str {
        match self {
            HdEvent::RigDug { .. } => "RigDug",
            HdEvent::RigSkipped { .. } => "RigSkipped",
            HdEvent::ShiftArmed { .. } => "ShiftArmed",
            HdEvent::ShiftEnded { .. } => "ShiftEnded",
            HdEvent::SeekerVerified { .. } => "SeekerVerified",
            HdEvent::RigRegistered { .. } => "RigRegistered",
            HdEvent::RigClosed { .. } => "RigClosed",
            HdEvent::HeartbeatsRecorded { .. } => "HeartbeatsRecorded",
            HdEvent::ShiftBroken { .. } => "ShiftBroken",
            HdEvent::ShiftEndedV2 { .. } => "ShiftEndedV2",
            HdEvent::StackOpened { .. } => "StackOpened",
            HdEvent::StackJoined { .. } => "StackJoined",
            HdEvent::StackCheckin { .. } => "StackCheckin",
            HdEvent::StackSettled { .. } => "StackSettled",
            HdEvent::StackClaimed { .. } => "StackClaimed",
            HdEvent::FocusBondLocked { .. } => "FocusBondLocked",
            HdEvent::FocusBondReleased { .. } => "FocusBondReleased",
            HdEvent::FocusBondForfeited { .. } => "FocusBondForfeited",
            HdEvent::GiftCreated { .. } => "GiftCreated",
            HdEvent::GiftClaimed { .. } => "GiftClaimed",
            HdEvent::GiftRefunded { .. } => "GiftRefunded",
            HdEvent::BuryLotAdded { .. } => "BuryLotAdded",
            HdEvent::BuryAuctionSold { .. } => "BuryAuctionSold",
            HdEvent::Other { .. } => "Other",
        }
    }

    /// The event's tag byte.
    pub fn tag(&self) -> u8 {
        match self {
            HdEvent::RigDug { .. } => 1,
            HdEvent::RigSkipped { .. } => 2,
            HdEvent::ShiftArmed { .. } => 3,
            HdEvent::ShiftEnded { .. } => 4,
            HdEvent::SeekerVerified { .. } => 5,
            HdEvent::RigRegistered { .. } => 6,
            HdEvent::RigClosed { .. } => 7,
            HdEvent::HeartbeatsRecorded { .. } => 8,
            HdEvent::ShiftBroken { .. } => 9,
            HdEvent::ShiftEndedV2 { .. } => 10,
            HdEvent::StackOpened { .. } => 11,
            HdEvent::StackJoined { .. } => 12,
            HdEvent::StackCheckin { .. } => 13,
            HdEvent::StackSettled { .. } => 14,
            HdEvent::StackClaimed { .. } => 15,
            HdEvent::FocusBondLocked { .. } => 16,
            HdEvent::FocusBondReleased { .. } => 17,
            HdEvent::FocusBondForfeited { .. } => 18,
            HdEvent::GiftCreated { .. } => 19,
            HdEvent::GiftClaimed { .. } => 20,
            HdEvent::GiftRefunded { .. } => 21,
            HdEvent::BuryLotAdded { .. } => 22,
            HdEvent::BuryAuctionSold { .. } => 23,
            HdEvent::Other { tag, .. } => *tag,
        }
    }

    /// The rig the event is about, when it names one. Every v1.1 tag (1..=10) starts with
    /// it; of the v1.2 tags, the seat and bond events carry it, the table, gift and Bury
    /// events do not.
    pub fn rig(&self) -> Option<Address> {
        match self {
            HdEvent::RigDug { rig, .. }
            | HdEvent::RigSkipped { rig, .. }
            | HdEvent::ShiftArmed { rig, .. }
            | HdEvent::ShiftEnded { rig, .. }
            | HdEvent::SeekerVerified { rig, .. }
            | HdEvent::RigRegistered { rig, .. }
            | HdEvent::RigClosed { rig }
            | HdEvent::HeartbeatsRecorded { rig, .. }
            | HdEvent::ShiftBroken { rig, .. }
            | HdEvent::ShiftEndedV2 { rig, .. }
            | HdEvent::StackJoined { rig, .. }
            | HdEvent::StackCheckin { rig, .. }
            | HdEvent::StackClaimed { rig, .. }
            | HdEvent::FocusBondLocked { rig, .. }
            | HdEvent::FocusBondReleased { rig, .. }
            | HdEvent::FocusBondForfeited { rig, .. } => Some(*rig),
            HdEvent::StackOpened { .. }
            | HdEvent::StackSettled { .. }
            | HdEvent::GiftCreated { .. }
            | HdEvent::GiftClaimed { .. }
            | HdEvent::GiftRefunded { .. }
            | HdEvent::BuryLotAdded { .. }
            | HdEvent::BuryAuctionSold { .. }
            | HdEvent::Other { .. } => None,
        }
    }

    /// The StackTable the event is about (tags 11..=15).
    pub fn table(&self) -> Option<Address> {
        match self {
            HdEvent::StackOpened { table, .. }
            | HdEvent::StackJoined { table, .. }
            | HdEvent::StackCheckin { table, .. }
            | HdEvent::StackSettled { table, .. }
            | HdEvent::StackClaimed { table, .. } => Some(*table),
            _ => None,
        }
    }
}

/// Exact length of each known tag, tag byte included (index = tag): v1.1 tags 1..=10, then
/// the v1.2 SKR tags 11..=23 (INTERFACE §7 and §11.9).
pub const EVENT_LEN: [usize; 24] = [
    0, 61, 45, 41, 66, 73, 67, 33, 49, 42, 83, // v1.1
    103, 138, 85, 67, 106, 113, 81, 82, 114, 74, 73, 66, 81, // v1.2 (SKR)
];

/// `StackClaimed.kind`: a settled payout.
pub const CLAIM_PAYOUT: u8 = 0;
/// `StackClaimed.kind`: a timeout refund of the seat's own bond.
pub const CLAIM_REFUND: u8 = 1;
/// `BuryLotAdded.source_kind`: a Stack table's settle.
pub const LOT_FROM_STACK: u8 = 1;
/// `BuryLotAdded.source_kind`: a forfeited Focus Bond.
pub const LOT_FROM_BOND: u8 = 2;
/// `FocusBondForfeited.reason` when the bonded shift can never be sealed.
pub const BOND_ABANDONED: u8 = 255;

/// Parse one `sol_log_data` payload. A known tag with the wrong length is `None` (ignored).
pub fn parse_event(b: &[u8]) -> Option<HdEvent> {
    let (&tag, body) = b.split_first()?;
    if let Some(&len) = EVENT_LEN.get(usize::from(tag)) {
        if tag != 0 && b.len() != len {
            return None;
        }
    }
    let rig = || read_address(body, 0);
    Some(match tag {
        1 => HdEvent::RigDug {
            rig: rig()?,
            round_id: read_u64(body, 32)?,
            lamports: read_u64(body, 40)?,
            mask: read_u32(body, 48)?,
            ema_ev: read_u64(body, 52)?,
        },
        2 => HdEvent::RigSkipped { rig: rig()?, round_id: read_u64(body, 32)?, error: read_u32(body, 40)? },
        3 => HdEvent::ShiftArmed { rig: rig()?, shift_id: read_u64(body, 32)? },
        4 => HdEvent::ShiftEnded {
            rig: rig()?,
            shift_id: read_u64(body, 32)?,
            dark_rounds: read_u64(body, 40)?,
            rounds_dug: read_u64(body, 48)?,
            lamports: read_u64(body, 56)?,
            reason: read_u8(body, 64)?,
        },
        5 => HdEvent::SeekerVerified {
            rig: rig()?,
            sgt_mint: read_address(body, 32)?,
            member_number: read_u64(body, 64)?,
        },
        6 => HdEvent::RigRegistered {
            rig: rig()?,
            authority: read_address(body, 32)?,
            tier: read_u8(body, 64)?,
            attestation_level: read_u8(body, 65)?,
        },
        7 => HdEvent::RigClosed { rig: rig()? },
        8 => HdEvent::HeartbeatsRecorded {
            rig: rig()?,
            round_id: read_u64(body, 32)?,
            dark_rounds_added: read_u64(body, 40)?,
        },
        9 => HdEvent::ShiftBroken { rig: rig()?, shift_id: read_u64(body, 32)?, reason: read_u8(body, 40)? },
        10 => HdEvent::ShiftEndedV2 {
            rig: rig()?,
            shift_id: read_u64(body, 32)?,
            dark_rounds: read_u64(body, 40)?,
            rounds_dug: read_u64(body, 48)?,
            lamports: read_u64(body, 56)?,
            reason: read_u8(body, 64)?,
            start_round: read_u64(body, 65)?,
            end_round: read_u64(body, 73)?,
            mode: read_u8(body, 81)?,
        },
        // ---- v1.2 (SKR), INTERFACE §11.9. Offsets below are body offsets (contract − 1). ----
        11 => HdEvent::StackOpened {
            table: read_address(body, 0)?,
            host: read_address(body, 32)?,
            table_id: read_u64(body, 64)?,
            bond: read_u64(body, 72)?,
            start_round: read_u64(body, 80)?,
            end_round: read_u64(body, 88)?,
            grace_gaps: read_u32(body, 96)?,
            flags: read_u8(body, 100)?,
            max_seats: read_u8(body, 101)?,
        },
        12 => HdEvent::StackJoined {
            table: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            authority: read_address(body, 64)?,
            sgt_mint: read_address(body, 96)?,
            bond: read_u64(body, 128)?,
            seat_index: read_u8(body, 136)?,
        },
        13 => HdEvent::StackCheckin {
            table: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            round_id: read_u64(body, 64)?,
            checked_rounds: read_u64(body, 72)?,
            result: read_u32(body, 80)?,
        },
        14 => HdEvent::StackSettled {
            table: read_address(body, 0)?,
            total_bonds: read_u64(body, 32)?,
            finisher_bonds: read_u64(body, 40)?,
            payouts_total: read_u64(body, 48)?,
            bury_amount: read_u64(body, 56)?,
            seats: read_u8(body, 64)?,
            finishers: read_u8(body, 65)?,
        },
        15 => HdEvent::StackClaimed {
            table: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            authority: read_address(body, 64)?,
            amount: read_u64(body, 96)?,
            kind: read_u8(body, 104)?,
        },
        16 => HdEvent::FocusBondLocked {
            bond: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            authority: read_address(body, 64)?,
            shift_id: read_u64(body, 96)?,
            amount: read_u64(body, 104)?,
        },
        17 => HdEvent::FocusBondReleased {
            bond: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            shift_id: read_u64(body, 64)?,
            amount: read_u64(body, 72)?,
        },
        18 => HdEvent::FocusBondForfeited {
            bond: read_address(body, 0)?,
            rig: read_address(body, 32)?,
            shift_id: read_u64(body, 64)?,
            amount: read_u64(body, 72)?,
            reason: read_u8(body, 80)?,
        },
        19 => HdEvent::GiftCreated {
            gift: read_address(body, 0)?,
            sender: read_address(body, 32)?,
            recipient: read_address(body, 64)?,
            lamports: read_u64(body, 96)?,
            expiry_ts: read_i64(body, 104)?,
            recipient_kind: read_u8(body, 112)?,
        },
        20 => HdEvent::GiftClaimed {
            gift: read_address(body, 0)?,
            claimer: read_address(body, 32)?,
            lamports: read_u64(body, 64)?,
            recipient_kind: read_u8(body, 72)?,
        },
        21 => HdEvent::GiftRefunded {
            gift: read_address(body, 0)?,
            sender: read_address(body, 32)?,
            lamports: read_u64(body, 64)?,
        },
        22 => HdEvent::BuryLotAdded {
            source: read_address(body, 0)?,
            amount: read_u64(body, 32)?,
            lot_skr: read_u64(body, 40)?,
            start_price: read_u64(body, 48)?,
            start_slot: read_u64(body, 56)?,
            source_kind: read_u8(body, 64)?,
        },
        23 => HdEvent::BuryAuctionSold {
            buyer: read_address(body, 0)?,
            skr_amount: read_u64(body, 32)?,
            price: read_u64(body, 40)?,
            ore_paid: read_u64(body, 48)?,
            ore_burned: read_u64(body, 56)?,
            ore_shared: read_u64(body, 64)?,
            lot_remaining: read_u64(body, 72)?,
        },
        0 => return None,
        _ => HdEvent::Other { tag, body: body.to_vec() },
    })
}

/// Extract `heads_down` events from transaction logs. Only `Program data:` lines emitted
/// while `program_id` is the innermost executing program are considered, so an event-shaped
/// payload logged by ORE, the entropy program or anything else is ignored.
///
/// `end_shift` emits `ShiftEnded` (tag 4) and then its superset `ShiftEndedV2` (tag 10): a
/// tag 4 is dropped when a tag 10 for the same rig and shift follows in the same heads_down
/// instruction, so a shift is never counted twice (a v1 program's lone tag 4 is kept).
pub fn events_from_logs(program_id: &Address, logs: &[String]) -> Vec<HdEvent> {
    use base64::Engine;
    let pid = program_id.to_string();
    let mut stack: Vec<String> = Vec::new();
    let mut invocation = 0usize;
    let mut out: Vec<(usize, HdEvent)> = Vec::new();
    for line in logs {
        if let Some(rest) = line.strip_prefix("Program ") {
            let mut parts = rest.split_whitespace();
            let first = parts.next().unwrap_or("");
            let second = parts.next().unwrap_or("");
            if second == "invoke" {
                if first == pid {
                    invocation += 1;
                }
                stack.push(first.to_string());
                continue;
            }
            if second == "success" || second == "failed:" || rest.ends_with(" failed") {
                if stack.last().map(String::as_str) == Some(first) {
                    stack.pop();
                }
                continue;
            }
            if first == "data:" && stack.last() == Some(&pid) {
                for field in rest.trim_start_matches("data:").split_whitespace() {
                    if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(field) {
                        if let Some(ev) = parse_event(&bytes) {
                            out.push((invocation, ev));
                        }
                    }
                }
            }
        }
    }
    let superseded = |inv: usize, r: &Address, s: u64| {
        out.iter().any(|(i, e)| {
            *i == inv && matches!(e, HdEvent::ShiftEndedV2 { rig, shift_id, .. } if rig == r && *shift_id == s)
        })
    };
    out.iter()
        .filter(|(inv, e)| match e {
            HdEvent::ShiftEnded { rig, shift_id, .. } => !superseded(*inv, rig, *shift_id),
            _ => true,
        })
        .map(|(_, e)| e.clone())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn program_id_bytes() {
        // Cross-checked against Python base58 in test-fixtures/vectors/interface.json.
        assert_eq!(
            hex::encode(PROGRAM_ID.as_ref()),
            "f100eb3136753bd65e861b529d37246aab554604f23f78cde60526d14233f7d7"
        );
    }

    #[test]
    fn dig_entry_roundtrip_and_layout() {
        let e = DigEntry { hb_ix: 3, hb_sig_index: 7, counter: 0x0102, round_id: 422_601, lease_rounds: 2 };
        let b = e.encode();
        assert_eq!(b.len(), 20);
        assert_eq!(b[0], 3);
        assert_eq!(b[1], 7);
        assert_eq!(&b[2..10], &0x0102u64.to_le_bytes());
        assert_eq!(&b[10..18], &422_601u64.to_le_bytes());
        assert_eq!(b[18], 2);
        assert_eq!(b[19], 0);
        assert_eq!(DigEntry::decode(&b), Some(e));
        assert_eq!(DigEntry::decode(&b[..19]), None);
        assert_eq!(DigEntry::reuse_lease().encode()[0], 0xFF);
        let mut list = vec![2u8];
        list.extend_from_slice(&b);
        list.extend_from_slice(&DigEntry::reuse_lease().encode());
        assert_eq!(DigEntry::decode_list(&list), Some(vec![e, DigEntry::reuse_lease()]));
        assert_eq!(DigEntry::decode_list(&list[..40]), None);
        assert_eq!(DigEntry::decode_list(&[0]), None);
    }

    #[test]
    fn dig_ix_account_order_and_errors() {
        let cranker = Address::new_from_array([9; 32]);
        let round = ore::round_pda(10);
        let a = RigAccounts::derive(Address::new_from_array([1; 32]), Address::new_from_array([2; 32]));
        let b = RigAccounts::derive(Address::new_from_array([3; 32]), Address::new_from_array([4; 32]));
        let ix = dig_ix(&PROGRAM_ID, &cranker, &round, &[(a, DigEntry::reuse_lease()), (b, DigEntry::reuse_lease())])
            .unwrap();
        assert_eq!(ix.accounts.len(), 12 + 8);
        assert_eq!(ix.data.len(), 2 + 40);
        assert_eq!(&ix.data[..2], &[6, 2]);
        let keys: Vec<Address> = ix.accounts.iter().map(|m| m.pubkey).collect();
        assert_eq!(keys[0], cranker);
        assert!(ix.accounts[0].is_signer && ix.accounts[0].is_writable);
        assert_eq!(keys[1], config_pda(&PROGRAM_ID).0);
        assert!(!ix.accounts[1].is_writable);
        assert_eq!(keys[2], executor_pda(&PROGRAM_ID).0);
        assert_eq!(keys[3], ore::BOARD_ADDRESS);
        assert_eq!(keys[4], ore::CONFIG_ADDRESS);
        assert_eq!(keys[5], round);
        assert_eq!(keys[6], ore::TREASURY_ADDRESS);
        assert_eq!(keys[7], ore::SYSTEM_PROGRAM_ID);
        assert_eq!(keys[8], ore::ORE_PROGRAM_ID);
        assert_eq!(keys[9], ore::VAR_ADDRESS);
        assert_eq!(keys[10], ore::ENTROPY_PROGRAM_ID);
        assert_eq!(keys[11], INSTRUCTIONS_SYSVAR_ID);
        assert_eq!(&keys[12..16], &[a.rig, a.authority, a.automation, a.miner]);
        assert_eq!(&keys[16..20], &[b.rig, b.authority, b.automation, b.miner]);
        for (i, m) in ix.accounts.iter().enumerate() {
            let w = !matches!(i, 1 | 7 | 8 | 10 | 11);
            assert_eq!(m.is_writable, w, "account {i} writability");
            assert_eq!(m.is_signer, i == 0);
        }
        assert_eq!(dig_ix(&PROGRAM_ID, &cranker, &round, &[]), Err(DigBuildError::Empty));
        assert_eq!(
            dig_ix(&PROGRAM_ID, &cranker, &round, &[(a, DigEntry::reuse_lease()), (a, DigEntry::reuse_lease())]),
            Err(DigBuildError::DuplicateRig(a.rig))
        );
        let many = vec![(a, DigEntry::reuse_lease()); 256];
        assert_eq!(dig_ix(&PROGRAM_ID, &cranker, &round, &many), Err(DigBuildError::TooMany(256)));
    }

    #[test]
    fn record_signal_and_end_shift_builders() {
        let rig = Address::new_from_array([1; 32]);
        let auth = Address::new_from_array([2; 32]);
        let e = DigEntry { hb_ix: 2, hb_sig_index: 1, counter: 9, round_id: 10, lease_rounds: 3 };
        let ix = record_heartbeats_ix(&PROGRAM_ID, &[(rig, e)]).unwrap();
        assert_eq!(ix.data.len(), 22);
        assert_eq!(&ix.data[..2], &[7, 1]);
        assert_eq!(ix.accounts.len(), 3);
        assert_eq!((ix.accounts[0].pubkey, ix.accounts[0].is_writable), (ore::BOARD_ADDRESS, false));
        assert_eq!((ix.accounts[1].pubkey, ix.accounts[1].is_writable), (INSTRUCTIONS_SYSVAR_ID, false));
        assert_eq!((ix.accounts[2].pubkey, ix.accounts[2].is_writable), (rig, true));
        assert!(ix.accounts.iter().all(|m| !m.is_signer));
        let too_many: Vec<_> = (0..33u8).map(|i| (Address::new_from_array([i; 32]), e)).collect();
        assert_eq!(record_heartbeats_ix(&PROGRAM_ID, &too_many), Err(DigBuildError::TooMany(33)));

        let ix = signal_p256_ix(&PROGRAM_ID, SignalKind::Break, &rig, &auth, reason::PICKUP, 4, 2, 0);
        assert_eq!(hex::encode(&ix.data), "08010104000000000000000200");
        assert_eq!(ix.accounts.iter().map(|m| (m.pubkey, m.is_signer, m.is_writable)).collect::<Vec<_>>(), vec![
            (rig, false, true),
            (auth, false, false),
            (INSTRUCTIONS_SYSVAR_ID, false, false)
        ]);
        let ix = signal_p256_ix(&PROGRAM_ID, SignalKind::Freeze, &rig, &auth, reason::FREEZE, 5, 0, 0);
        assert_eq!(hex::encode(&ix.data), "09010305000000000000000000");
        assert!(SignalKind::Break.reason_ok(7) && !SignalKind::Break.reason_ok(3) && !SignalKind::Break.reason_ok(0));
        assert!(SignalKind::Freeze.reason_ok(3) && !SignalKind::Freeze.reason_ok(1));

        let caller = Address::new_from_array([3; 32]);
        let ix = end_shift_ix(&PROGRAM_ID, &caller, &rig, 2);
        assert_eq!(ix.data, vec![11]);
        assert_eq!(ix.accounts[2].pubkey, shift_log_pda(&PROGRAM_ID, &rig, 2).0);
        assert!(ix.accounts[0].is_signer && ix.accounts[0].is_writable);
        assert!(ix.accounts[1].is_writable && ix.accounts[2].is_writable);
        assert!(!ix.accounts[3].is_writable && !ix.accounts[4].is_writable);
    }

    fn sample_rig() -> Rig {
        Rig {
            bump: 254,
            authority: Address::new_from_array([7; 32]),
            p256_pubkey: [3; 33],
            attestation_level: 1,
            tier: 1,
            state: RigState::Down,
            sgt_mint: Address::new_from_array([8; 32]),
            attestation_expiry_slot: 11,
            cap_week: 12,
            cap_shift: 13,
            cap_round: 14,
            cap_max_cost: 15,
            caps_expiry_ts: -16,
            plan_max_ev_cost: 17,
            plan_dig_lamports: 18,
            plan_split_tiles: 15,
            plan_solo_tiles: 2,
            plan_lease_rounds: 3,
            plan_flags: 1,
            plan_window_start_ts: 19,
            plan_window_end_ts: 20,
            shift_id: 21,
            hb_counter: 22,
            lease_from_round: 23,
            lease_to_round: 24,
            gap_count: 25,
            spent_shift: 26,
            spent_week: 27,
            week_start_ts: 28,
            last_dug_round: 29,
            shift_start_round: 30,
            shift_dark_rounds: 31,
            shift_rounds_dug: 32,
            lifetime_dark_rounds: 33,
            lifetime_rounds_dug: 34,
            lifetime_lamports_deployed: 35,
            streak: 36,
            freezes_left: 2,
            last_shift_day: 37,
            shift_open: true,
            break_reason: 7,
            ore_automation_bump: 253,
            ore_miner_bump: 252,
            shift_start_ts: 38,
        }
    }

    #[test]
    fn rig_encode_decode_roundtrip_and_checks() {
        let rig = sample_rig();
        let d = rig.encode();
        assert_eq!(d.len(), RIG_LEN);
        assert_eq!(d[RIG_STATE_OFFSET], 2);
        assert_eq!(&d[336..340], &[1, 7, 253, 252]);
        assert_eq!(&d[344..352], &38i64.to_le_bytes());
        assert!(d[352..].iter().all(|b| *b == 0), "reserved stays zero");
        assert_eq!(Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &d).unwrap(), rig);
        assert!(rig.focus_only());
        assert_eq!(rig.tiles(), 17);
        assert_eq!(rig.mode(), 2);
        let other = Address::new_from_array([5; 32]);
        assert!(matches!(Rig::decode(&PROGRAM_ID, &other, &d), Err(AccountError::WrongOwner(_))));
        let mut bad = d.clone();
        bad[0] = TAG_CONFIG;
        assert!(matches!(Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &bad), Err(AccountError::WrongTag { .. })));
        let mut bad = d.clone();
        bad[1] = 2;
        assert!(matches!(Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &bad), Err(AccountError::WrongTag { .. })));
        assert!(matches!(
            Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &d[..383]),
            Err(AccountError::WrongSize { .. })
        ));
        assert_eq!(Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &Rig::default().encode()).unwrap(), Rig::default());
    }

    #[test]
    fn lease_rules_mirror_the_program() {
        assert_eq!(lease_range(100, 1, 3), Some((100, 100)));
        assert_eq!(lease_range(100, 3, 2), Some((100, 101)));
        assert_eq!(lease_range(100, 0, 3), None);
        assert_eq!(lease_range(100, 2, 0), None);
        assert_eq!(lease_range(u64::MAX, 2, 3), None);
        assert_eq!(lease_range(u64::MAX, 1, 3), Some((u64::MAX, u64::MAX)));
        // programs/heads-down/program/src/logic.rs leases_extend_forward_and_count_dark_rounds_and_gaps
        let g = grant_lease(0, 0, 100, 100, 3).unwrap();
        assert_eq!(g, LeaseGrant { from: 100, to: 102, dark_added: 3, gap_added: 0 });
        let g2 = grant_lease(g.from, g.to, 100, 101, 3).unwrap();
        assert_eq!(g2, LeaseGrant { from: 101, to: 103, dark_added: 1, gap_added: 0 });
        assert_eq!(grant_lease(g2.from, g2.to, 100, 99, 3).unwrap(), LeaseGrant { from: 101, to: 103, dark_added: 0, gap_added: 0 });
        assert_eq!(grant_lease(g2.from, g2.to, 100, 107, 1).unwrap(), LeaseGrant { from: 107, to: 107, dark_added: 1, gap_added: 3 });
        assert_eq!(grant_lease(0, 0, 100, 104, 1).unwrap(), LeaseGrant { from: 104, to: 104, dark_added: 1, gap_added: 4 });
        assert_eq!(grant_lease(0, 0, 100, 98, 3).unwrap(), LeaseGrant { from: 98, to: 100, dark_added: 1, gap_added: 0 });
        assert_eq!(grant_lease(0, 0, 1, 5, 0), None);
        assert_eq!(grant_lease(0, 0, 1, u64::MAX, 3), None);
        // lease_covers needs lease_to != 0.
        let mut r = Rig::default();
        assert!(!r.lease_covers(0));
        r.lease_to_round = 5;
        assert!(r.lease_covers(0) && r.lease_covers(5) && !r.lease_covers(6));
    }

    fn event_bytes(tag: u8, fields: &[&[u8]]) -> Vec<u8> {
        let mut v = vec![tag];
        for f in fields {
            v.extend_from_slice(f);
        }
        v
    }

    #[test]
    fn events_are_attributed_to_the_right_program() {
        use base64::Engine;
        let enc = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        let rig = Address::new_from_array([4; 32]);
        let dug = event_bytes(1, &[rig.as_ref(), &5u64.to_le_bytes(), &1_000_000u64.to_le_bytes(), &0b111u32.to_le_bytes(), &600u64.to_le_bytes()]);
        let skipped = event_bytes(2, &[rig.as_ref(), &5u64.to_le_bytes(), &7u32.to_le_bytes()]);
        let pid = PROGRAM_ID.to_string();
        let ore = ore::ORE_PROGRAM_ID.to_string();
        let logs = vec![
            format!("Program {pid} invoke [1]"),
            format!("Program {ore} invoke [2]"),
            format!("Program data: {}", enc(&dug)), // logged by ORE: ignored
            format!("Program {ore} success"),
            format!("Program data: {}", enc(&dug)),
            format!("Program data: {}", enc(&skipped)),
            format!("Program data: {}", enc(&[1u8, 2, 3])), // malformed: ignored
            format!("Program {pid} success"),
            format!("Program data: {}", enc(&skipped)), // outside any invocation
        ];
        let evs = events_from_logs(&PROGRAM_ID, &logs);
        assert_eq!(
            evs,
            vec![
                HdEvent::RigDug { rig, round_id: 5, lamports: 1_000_000, mask: 7, ema_ev: 600 },
                HdEvent::RigSkipped { rig, round_id: 5, error: 7 },
            ]
        );
        assert_eq!(error_name(7), "StaleHeartbeat");
        assert_eq!(error_name(0x2560_000e), "P256MessageMismatch");
        assert_eq!(error_name(0x2560_00ff), "P256Introspect");
        assert_eq!(error_name(0x5347_0023), "SgtVerify");
        for (c, n) in [
            (24, "InvalidRigState"),
            (25, "RoundNotActive"),
            (26, "MinerNotCheckpointed"),
            (27, "MotherlodeCondition"),
            (28, "InsufficientAutomationBalance"),
            (29, "OreNoOp"),
            (30, "FocusOnly"),
            (31, "ExecutorUnderfunded"),
        ] {
            assert_eq!(error_name(c), n);
        }
        for (c, n) in [
            (32, "InvalidTokenAccount"),
            (33, "AmountOutOfRange"),
            (34, "InvalidStackParams"),
            (35, "StackJoinClosed"),
            (36, "StackIneligible"),
            (37, "StackNotEnded"),
            (38, "InvalidStackState"),
            (39, "StackSeatMismatch"),
            (40, "StackShiftMismatch"),
            (41, "StackLeaseTooLong"),
            (42, "StackSeatBroken"),
            (43, "BondNotResolvable"),
            (44, "GiftNotClaimable"),
            (45, "GiftExpiry"),
            (46, "AuctionEmpty"),
            (47, "PriceAboveMax"),
            (48, "BuryMismatch"),
        ] {
            assert_eq!(error_name(c), n);
        }
        assert_eq!(error_name(49), "Unknown");
        assert_eq!((code::STACK_SEAT_BROKEN, code::LEASE_EXPIRED, code::RIG_NOT_ARMED), (42, 8, 13));
        assert_eq!(error_name(code::STACK_SHIFT_MISMATCH), "StackShiftMismatch");
        assert_eq!(error_name(code::STACK_LEASE_TOO_LONG), "StackLeaseTooLong");
        assert_eq!(error_name(code::INVALID_STACK_STATE), "InvalidStackState");
        assert_eq!(error_name(code::INVALID_RIG_STATE), "InvalidRigState");
        assert_eq!(checkin_result_name(0), "counted");
        assert_eq!(checkin_result_name(42), "StackSeatBroken");
        assert_eq!(checkin_result_name(0x2560_000d), "P256PublicKeyMismatch");
        assert_eq!(bond_reason_name(255), "abandoned");
        assert_eq!(bond_reason_name(6), "manual");
    }

    #[test]
    fn shift_ended_is_not_double_counted() {
        use base64::Engine;
        let enc = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        let rig = Address::new_from_array([6; 32]);
        let v1 = event_bytes(4, &[rig.as_ref(), &2u64.to_le_bytes(), &1u64.to_le_bytes(), &0u64.to_le_bytes(), &0u64.to_le_bytes(), &[3]]);
        let v2 = event_bytes(10, &[&v1[1..], &422_700u64.to_le_bytes(), &422_701u64.to_le_bytes(), &[0]]);
        assert_eq!(v1.len(), 66);
        assert_eq!(v2.len(), 83);
        let pid = PROGRAM_ID.to_string();
        let both = vec![
            format!("Program {pid} invoke [1]"),
            format!("Program data: {}", enc(&v1)),
            format!("Program data: {}", enc(&v2)),
            format!("Program {pid} success"),
        ];
        let evs = events_from_logs(&PROGRAM_ID, &both);
        assert_eq!(evs.len(), 1, "{evs:?}");
        assert!(matches!(evs[0], HdEvent::ShiftEndedV2 { shift_id: 2, start_round: 422_700, end_round: 422_701, mode: 0, reason: 3, .. }));
        // A v1 program emits only tag 4: kept.
        let only_v1 = vec![both[0].clone(), both[1].clone(), both[3].clone()];
        assert!(matches!(events_from_logs(&PROGRAM_ID, &only_v1)[..], [HdEvent::ShiftEnded { shift_id: 2, .. }]));
        // Two end_shift instructions (two rigs) in one transaction: one V2 each.
        let rig2 = Address::new_from_array([7; 32]);
        let mut v1b = v1.clone();
        v1b[1..33].copy_from_slice(rig2.as_ref());
        let mut v2b = v2.clone();
        v2b[1..33].copy_from_slice(rig2.as_ref());
        let two = vec![
            format!("Program {pid} invoke [1]"),
            format!("Program data: {}", enc(&v1)),
            format!("Program data: {}", enc(&v2)),
            format!("Program {pid} success"),
            format!("Program {pid} invoke [1]"),
            format!("Program data: {}", enc(&v1b)),
            format!("Program data: {}", enc(&v2b)),
            format!("Program {pid} success"),
        ];
        let evs = events_from_logs(&PROGRAM_ID, &two);
        assert_eq!(evs.iter().filter(|e| matches!(e, HdEvent::ShiftEndedV2 { .. })).count(), 2);
        assert_eq!(evs.len(), 2);
    }

    #[test]
    fn every_tag_decodes_by_exact_length() {
        let rig = Address::new_from_array([9; 32]);
        let other = Address::new_from_array([8; 32]);
        let samples: Vec<(Vec<u8>, &str)> = vec![
            (event_bytes(5, &[rig.as_ref(), other.as_ref(), &20u64.to_le_bytes()]), "SeekerVerified"),
            (event_bytes(6, &[rig.as_ref(), other.as_ref(), &[1, 2]]), "RigRegistered"),
            (event_bytes(7, &[rig.as_ref()]), "RigClosed"),
            (event_bytes(8, &[rig.as_ref(), &3u64.to_le_bytes(), &2u64.to_le_bytes()]), "HeartbeatsRecorded"),
            (event_bytes(9, &[rig.as_ref(), &3u64.to_le_bytes(), &[8]]), "ShiftBroken"),
            (event_bytes(3, &[rig.as_ref(), &3u64.to_le_bytes()]), "ShiftArmed"),
        ];
        for (b, name) in &samples {
            let e = parse_event(b).unwrap_or_else(|| panic!("{name}"));
            assert_eq!(e.name(), *name);
            assert_eq!(e.rig(), Some(rig));
            let mut longer = b.clone();
            longer.push(0);
            assert_eq!(parse_event(&longer), None, "{name} with a trailing byte");
            assert_eq!(parse_event(&b[..b.len() - 1]), None, "{name} truncated");
        }
        assert_eq!(parse_event(&[42, 1, 2]), Some(HdEvent::Other { tag: 42, body: vec![1, 2] }));
        assert_eq!(parse_event(&[0]), None);
        assert_eq!(parse_event(&[]), None);
        assert_eq!(reason_name(8), "unlocked");
        assert_eq!(mode_name(2), "focus_only");
    }

    #[test]
    fn skr_events_decode_by_exact_length() {
        let a = Address::new_from_array([1; 32]);
        let b = Address::new_from_array([2; 32]);
        let c = Address::new_from_array([3; 32]);
        let d = Address::new_from_array([4; 32]);
        let u = |v: u64| v.to_le_bytes();
        let samples: Vec<(Vec<u8>, HdEvent)> = vec![
            (
                event_bytes(11, &[a.as_ref(), b.as_ref(), &u(7), &u(200_000_000), &u(10), &u(19), &3u32.to_le_bytes(), &[5, 8]]),
                HdEvent::StackOpened { table: a, host: b, table_id: 7, bond: 200_000_000, start_round: 10, end_round: 19, grace_gaps: 3, flags: 5, max_seats: 8 },
            ),
            (
                event_bytes(12, &[a.as_ref(), b.as_ref(), c.as_ref(), d.as_ref(), &u(9), &[2]]),
                HdEvent::StackJoined { table: a, rig: b, authority: c, sgt_mint: d, bond: 9, seat_index: 2 },
            ),
            (
                event_bytes(13, &[a.as_ref(), b.as_ref(), &u(11), &u(2), &42u32.to_le_bytes()]),
                HdEvent::StackCheckin { table: a, rig: b, round_id: 11, checked_rounds: 2, result: 42 },
            ),
            (
                event_bytes(14, &[a.as_ref(), &u(600), &u(400), &u(560), &u(40), &[3, 2]]),
                HdEvent::StackSettled { table: a, total_bonds: 600, finisher_bonds: 400, payouts_total: 560, bury_amount: 40, seats: 3, finishers: 2 },
            ),
            (
                event_bytes(15, &[a.as_ref(), b.as_ref(), c.as_ref(), &u(280), &[1]]),
                HdEvent::StackClaimed { table: a, rig: b, authority: c, amount: 280, kind: CLAIM_REFUND },
            ),
            (
                event_bytes(16, &[a.as_ref(), b.as_ref(), c.as_ref(), &u(4), &u(500)]),
                HdEvent::FocusBondLocked { bond: a, rig: b, authority: c, shift_id: 4, amount: 500 },
            ),
            (
                event_bytes(17, &[a.as_ref(), b.as_ref(), &u(4), &u(500)]),
                HdEvent::FocusBondReleased { bond: a, rig: b, shift_id: 4, amount: 500 },
            ),
            (
                event_bytes(18, &[a.as_ref(), b.as_ref(), &u(4), &u(500), &[255]]),
                HdEvent::FocusBondForfeited { bond: a, rig: b, shift_id: 4, amount: 500, reason: BOND_ABANDONED },
            ),
            (
                event_bytes(19, &[a.as_ref(), b.as_ref(), c.as_ref(), &u(5), &(-3i64).to_le_bytes(), &[1]]),
                HdEvent::GiftCreated { gift: a, sender: b, recipient: c, lamports: 5, expiry_ts: -3, recipient_kind: 1 },
            ),
            (
                event_bytes(20, &[a.as_ref(), b.as_ref(), &u(5), &[0]]),
                HdEvent::GiftClaimed { gift: a, claimer: b, lamports: 5, recipient_kind: 0 },
            ),
            (event_bytes(21, &[a.as_ref(), b.as_ref(), &u(5)]), HdEvent::GiftRefunded { gift: a, sender: b, lamports: 5 }),
            (
                event_bytes(22, &[a.as_ref(), &u(40), &u(340), &u(100_000_000), &u(77), &[LOT_FROM_BOND]]),
                HdEvent::BuryLotAdded { source: a, amount: 40, lot_skr: 340, start_price: 100_000_000, start_slot: 77, source_kind: 2 },
            ),
            (
                event_bytes(23, &[a.as_ref(), &u(1), &u(2), &u(3), &u(4), &u(5), &u(6)]),
                HdEvent::BuryAuctionSold { buyer: a, skr_amount: 1, price: 2, ore_paid: 3, ore_burned: 4, ore_shared: 5, lot_remaining: 6 },
            ),
        ];
        assert_eq!(samples.len(), 13);
        for (bytes, want) in &samples {
            let tag = bytes[0];
            assert_eq!(bytes.len(), EVENT_LEN[usize::from(tag)], "tag {tag}: the contract length");
            let got = parse_event(bytes).unwrap_or_else(|| panic!("tag {tag}"));
            assert_eq!(&got, want);
            assert_eq!(got.tag(), tag);
            let mut longer = bytes.clone();
            longer.push(0);
            assert_eq!(parse_event(&longer), None, "tag {tag} with a trailing byte");
            assert_eq!(parse_event(&bytes[..bytes.len() - 1]), None, "tag {tag} truncated");
        }
        // The seat and bond events name their rig; table, gift and Bury events do not.
        assert_eq!(samples[1].1.rig(), Some(b));
        assert_eq!(samples[2].1.rig(), Some(b));
        assert_eq!(samples[7].1.rig(), Some(b));
        assert_eq!(samples[0].1.rig(), None);
        assert_eq!(samples[11].1.rig(), None);
        assert_eq!(samples[2].1.table(), Some(a));
        assert_eq!(samples[5].1.table(), None);
        assert_eq!(EVENT_LEN.len(), 24, "tags 0..=23");
        // A v1.2 event is attributed like any other: only while heads_down is innermost.
        use base64::Engine;
        let enc = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        let pid = PROGRAM_ID.to_string();
        let token = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
        let logs = vec![
            format!("Program {pid} invoke [1]"),
            format!("Program {token} invoke [2]"),
            format!("Program data: {}", enc(&samples[3].0)), // logged inside the SPL Token CPI: ignored
            format!("Program {token} success"),
            format!("Program data: {}", enc(&samples[11].0)),
            format!("Program data: {}", enc(&samples[3].0)),
            format!("Program {pid} success"),
        ];
        let evs = events_from_logs(&PROGRAM_ID, &logs);
        assert_eq!(evs.iter().map(HdEvent::name).collect::<Vec<_>>(), vec!["BuryLotAdded", "StackSettled"]);
    }
}
