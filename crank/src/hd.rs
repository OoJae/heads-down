//! The `heads_down` program interface, built strictly from `programs/heads-down/INTERFACE.md`:
//! PDAs, the Config and Rig account layouts, the signed P-256 preimages, the batched `dig`
//! instruction, error codes and events. The program is written in parallel; nothing here
//! imports it. Where the contract is silent, the choice made is listed in
//! `crank/INTERFACE-NOTES.md`.

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

/// `dig` instruction tag.
pub const IX_DIG: u8 = 6;
/// `hb_ix` value meaning "reuse the rig's current on-chain lease".
pub const HB_REUSE_LEASE: u8 = 0xFF;
/// Size of one per-rig `dig` entry.
pub const DIG_ENTRY_LEN: usize = 20;
/// Fixed accounts before the per-rig groups.
pub const DIG_FIXED_ACCOUNTS: usize = 12;
/// Accounts per rig.
pub const DIG_ACCOUNTS_PER_RIG: usize = 4;
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

fn check_header(
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
    /// Lamports reimbursed to the cranker per rig-round dug.
    pub crank_fee: u64,
    /// The Discretionary `fee` every rig's Automation must use.
    pub executor_fee: u64,
    /// Bury share.
    pub bury_bps: u16,
    /// 1 = `dig` disabled.
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

/// Rig state machine (`Rig.state`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum RigState {
    /// 0
    Idle,
    /// 1
    Armed,
    /// 2
    Down,
    /// 3
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

    /// `dig` step 2: `state ∈ {Armed, Down}`.
    pub fn diggable(self) -> bool {
        matches!(self, RigState::Armed | RigState::Down)
    }
}

/// `Rig` (384 bytes), every field of INTERFACE.md.
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
}

/// `plan_flags` bit 0: focus-only, never deploys.
pub const PLAN_FLAG_FOCUS_ONLY: u8 = 1;
/// Byte offset of `Rig.state` (memcmp filter target).
pub const RIG_STATE_OFFSET: usize = 75;

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
        d
    }

    /// True if the rig's on-chain lease covers `round_id` (`lease_from ≤ round ≤ lease_to`).
    pub fn lease_covers(&self, round_id: u64) -> bool {
        self.lease_from_round <= round_id && round_id <= self.lease_to_round
    }

    /// `plan_flags` bit 0.
    pub fn focus_only(&self) -> bool {
        self.plan_flags & PLAN_FLAG_FOCUS_ONLY != 0
    }

    /// `k = split + solo` (u16, cannot overflow).
    pub fn tiles(&self) -> u16 {
        u16::from(self.plan_split_tiles) + u16::from(self.plan_solo_tiles)
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

/// One per-rig `dig` entry (20 bytes):
/// `hb_ix u8 | hb_sig_index u8 | counter u64 | round_id u64 | lease_rounds u8 | _pad u8`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DigEntry {
    /// Top-level index of the Secp256r1SigVerify instruction, or [`HB_REUSE_LEASE`].
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

/// Errors building a `dig` instruction.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum DigBuildError {
    /// No rigs.
    #[error("empty batch")]
    Empty,
    /// More than 255 rigs.
    #[error("too many rigs for a u8 count: {0}")]
    TooMany(usize),
    /// A rig appears twice (the program fails the tx with `DuplicateRig`).
    #[error("duplicate rig {0}")]
    DuplicateRig(Address),
}

/// Build `heads_down::dig` with the fixed account order of INTERFACE.md:
///
/// ```text
/// 0 cranker (s,w)   4 ore_config (w)   8 ore_program         12.. per rig i:
/// 1 config          5 round (w)        9 entropy_var (w)          rig (w), authority (w),
/// 2 executor (w)    6 treasury (w)    10 entropy_program          automation (w), miner (w)
/// 3 board (w)       7 system_program  11 instructions sysvar
/// ```
///
/// `round` is the Round PDA for the `Board.round_id` the tx is meant to land in.
pub fn dig_ix(
    program_id: &Address,
    cranker: &Address,
    round: &Address,
    rigs: &[(RigAccounts, DigEntry)],
) -> Result<Instruction, DigBuildError> {
    if rigs.is_empty() {
        return Err(DigBuildError::Empty);
    }
    let n = u8::try_from(rigs.len()).map_err(|_| DigBuildError::TooMany(rigs.len()))?;
    let mut seen = std::collections::HashSet::with_capacity(rigs.len());
    for (a, _) in rigs {
        if !seen.insert(a.rig) {
            return Err(DigBuildError::DuplicateRig(a.rig));
        }
    }
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

/// `ProgramError::Custom` codes of `heads_down`.
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
        c if c & 0xFFF0_0000 == 0x2560_0000 => "P256Introspect",
        c if c & 0xFFFF_0000 == 0x5347_0000 => "SgtVerify",
        _ => "Unknown",
    }
}

/// Events logged with `sol_log_data` (first byte = tag). Encoding assumed: a single data
/// field, fields packed little-endian in declaration order (see `INTERFACE-NOTES.md`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HdEvent {
    /// Tag 1.
    RigDug {
        /// Rig.
        rig: Address,
        /// ORE round.
        round_id: u64,
        /// Lamports actually debited from the Automation.
        lamports: u64,
        /// Squares deployed.
        mask: u32,
        /// Gate value at dig time.
        ema_ev: u64,
    },
    /// Tag 2.
    RigSkipped {
        /// Rig.
        rig: Address,
        /// ORE round.
        round_id: u64,
        /// heads_down error code.
        error: u32,
    },
    /// Tag 3.
    ShiftArmed {
        /// Rig.
        rig: Address,
        /// New shift id.
        shift_id: u64,
    },
    /// Tag 4, 5 and anything newer: kept raw.
    Other {
        /// Event tag.
        tag: u8,
        /// Raw bytes after the tag.
        body: Vec<u8>,
    },
}

/// Parse one `sol_log_data` payload.
pub fn parse_event(b: &[u8]) -> Option<HdEvent> {
    let (&tag, body) = b.split_first()?;
    match tag {
        1 if body.len() == 32 + 8 + 8 + 4 + 8 => Some(HdEvent::RigDug {
            rig: read_address(body, 0)?,
            round_id: read_u64(body, 32)?,
            lamports: read_u64(body, 40)?,
            mask: read_u32(body, 48)?,
            ema_ev: read_u64(body, 52)?,
        }),
        2 if body.len() == 32 + 8 + 4 => Some(HdEvent::RigSkipped {
            rig: read_address(body, 0)?,
            round_id: read_u64(body, 32)?,
            error: read_u32(body, 40)?,
        }),
        3 if body.len() == 32 + 8 => {
            Some(HdEvent::ShiftArmed { rig: read_address(body, 0)?, shift_id: read_u64(body, 32)? })
        }
        1..=3 => None,
        _ => Some(HdEvent::Other { tag, body: body.to_vec() }),
    }
}

/// Extract `heads_down` events from transaction logs. Only `Program data:` lines emitted
/// while `program_id` is the innermost executing program are considered, so an event-shaped
/// payload logged by ORE, the entropy program or anything else is ignored.
pub fn events_from_logs(program_id: &Address, logs: &[String]) -> Vec<HdEvent> {
    use base64::Engine;
    let pid = program_id.to_string();
    let mut stack: Vec<String> = Vec::new();
    let mut out = Vec::new();
    for line in logs {
        if let Some(rest) = line.strip_prefix("Program ") {
            let mut parts = rest.split_whitespace();
            let first = parts.next().unwrap_or("");
            let second = parts.next().unwrap_or("");
            if second == "invoke" {
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
                            out.push(ev);
                        }
                    }
                }
            }
        }
    }
    out
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
    fn rig_encode_decode_roundtrip_and_checks() {
        let rig = Rig {
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
        };
        let d = rig.encode();
        assert_eq!(d.len(), RIG_LEN);
        assert_eq!(d[RIG_STATE_OFFSET], 2);
        assert_eq!(Rig::decode(&PROGRAM_ID, &PROGRAM_ID, &d).unwrap(), rig);
        assert!(rig.focus_only());
        assert_eq!(rig.tiles(), 17);
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
    }

    #[test]
    fn lease_range_rules() {
        assert_eq!(lease_range(100, 1, 3), Some((100, 100)));
        assert_eq!(lease_range(100, 3, 2), Some((100, 101)));
        assert_eq!(lease_range(100, 0, 3), None);
        assert_eq!(lease_range(100, 2, 0), None);
        assert_eq!(lease_range(u64::MAX, 2, 3), None);
        assert_eq!(lease_range(u64::MAX, 1, 3), Some((u64::MAX, u64::MAX)));
    }

    #[test]
    fn events_are_attributed_to_the_right_program() {
        use base64::Engine;
        let enc = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        let rig = Address::new_from_array([4; 32]);
        let mut dug = vec![1u8];
        dug.extend_from_slice(rig.as_ref());
        dug.extend_from_slice(&5u64.to_le_bytes());
        dug.extend_from_slice(&1_005_000u64.to_le_bytes());
        dug.extend_from_slice(&0b111u32.to_le_bytes());
        dug.extend_from_slice(&600u64.to_le_bytes());
        let mut skipped = vec![2u8];
        skipped.extend_from_slice(rig.as_ref());
        skipped.extend_from_slice(&5u64.to_le_bytes());
        skipped.extend_from_slice(&7u32.to_le_bytes());
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
                HdEvent::RigDug { rig, round_id: 5, lamports: 1_005_000, mask: 7, ema_ev: 600 },
                HdEvent::RigSkipped { rig, round_id: 5, error: 7 },
            ]
        );
        assert_eq!(error_name(7), "StaleHeartbeat");
        assert_eq!(error_name(0x2560_000e), "P256Introspect");
    }
}
