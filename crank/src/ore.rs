//! ORE v3 account layouts, pins, PDAs and the two instructions the crank touches.
//!
//! Everything here is read from ORE at the pinned commit
//! `b92c5043581a4ad513401f7d5aabd1eb21148c12` (see `docs/ORE.md`, section 1). ORE accounts are
//! Steel accounts: an 8-byte header whose first byte is the discriminator, then a `repr(C)`
//! struct with no padding. Every decoder here checks **owner, exact length and discriminator**
//! before reading a single field, and returns a [`LayoutError`] instead of panicking. A
//! [`LayoutError`] on a pinned account is what trips the crank's circuit breaker
//! ([`crate::breaker`]).

use sha3::{Digest, Keccak256};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

use crate::bytes::{read_address, read_u16, read_u64, read_u64_array};

/// `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv` (`api/src/lib.rs:19`).
pub const ORE_PROGRAM_ID: Address =
    Address::from_str_const("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv");
/// Board singleton (`consts.rs:110`).
pub const BOARD_ADDRESS: Address =
    Address::from_str_const("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi");
/// ORE Config singleton (`consts.rs:116`).
pub const CONFIG_ADDRESS: Address =
    Address::from_str_const("9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy");
/// Treasury singleton (`consts.rs:113`).
pub const TREASURY_ADDRESS: Address =
    Address::from_str_const("45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG");
/// Entropy Var used by `deploy` on a round's first deploy (`consts.rs:104`).
pub const VAR_ADDRESS: Address =
    Address::from_str_const("BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E");
/// Entropy program (owner of the Var).
pub const ENTROPY_PROGRAM_ID: Address =
    Address::from_str_const("3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X");
/// ORE's ProgramData account (BPF upgradeable loader). Header: `u32 tag = 3 | u64 slot | Option<Pubkey>`.
pub const PROGRAMDATA_ADDRESS: Address =
    Address::from_str_const("GXa6JV9AwsccP3hxvKFcZGp4w3MMtf7PJ6HYuTSyokfJ");
/// ORE's last upgrade slot when the layouts were pinned (`docs/ORE.md` section 1).
pub const PINNED_PROGRAMDATA_SLOT: u64 = 450_496_378;
/// `BPFLoaderUpgradeab1e11111111111111111111111`.
pub const BPF_UPGRADEABLE_LOADER_ID: Address =
    Address::from_str_const("BPFLoaderUpgradeab1e11111111111111111111111");
/// System program.
pub const SYSTEM_PROGRAM_ID: Address = Address::from_str_const("11111111111111111111111111111111");

/// 1 ORE in base units (11 decimals, `consts.rs:8-11`).
pub const ONE_ORE: u64 = 100_000_000_000;
/// `CHECKPOINT_FEE` (`consts.rs:89`).
pub const CHECKPOINT_FEE: u64 = 10_000;
/// ORE instruction tags (`instruction.rs:5-23`).
pub const IX_CHECKPOINT: u8 = 2;
/// ORE `deploy` tag.
pub const IX_DEPLOY: u8 = 6;
/// Number of squares on the board.
pub const SQUARES: usize = 25;
/// Solo squares per round chosen by `distribution_mask` (`state/round.rs:125-164`).
pub const SOLO_SQUARES: usize = 10;
/// `AutomationStrategy::Discretionary` (`state/automation.rs`).
pub const STRATEGY_DISCRETIONARY: u64 = 2;

/// Seeds (`consts.rs`).
pub const AUTOMATION_SEED: &[u8] = b"automation";
/// Miner PDA seed.
pub const MINER_SEED: &[u8] = b"miner";
/// Round PDA seed.
pub const ROUND_SEED: &[u8] = b"round";

/// The ORE accounts the crank reads, with their pinned size and discriminator
/// (`docs/ORE.md` sections 1, 3 and 8, and `state/mod.rs` `OreAccount`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum OreKind {
    /// Board singleton.
    Board,
    /// Per-round account.
    Round,
    /// Treasury singleton.
    Treasury,
    /// ORE Config singleton.
    Config,
    /// A user's Automation.
    Automation,
    /// A user's Miner.
    Miner,
}

impl OreKind {
    /// Pinned account size in bytes (header included).
    pub const fn size(self) -> usize {
        match self {
            OreKind::Board => 40,
            OreKind::Round => 952,
            OreKind::Treasury => 48,
            OreKind::Config => 232,
            OreKind::Automation => 160,
            OreKind::Miner => 752,
        }
    }

    /// Pinned discriminator (first header byte).
    pub const fn discriminator(self) -> u8 {
        match self {
            OreKind::Board => 105,
            OreKind::Round => 109,
            OreKind::Treasury => 104,
            OreKind::Config => 101,
            OreKind::Automation => 100,
            OreKind::Miner => 103,
        }
    }

    /// Short label for logs and metrics.
    pub const fn label(self) -> &'static str {
        match self {
            OreKind::Board => "board",
            OreKind::Round => "round",
            OreKind::Treasury => "treasury",
            OreKind::Config => "ore_config",
            OreKind::Automation => "automation",
            OreKind::Miner => "miner",
        }
    }
}

/// Why an ORE account did not match its pin.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum LayoutError {
    /// The account does not exist.
    #[error("{} account missing", .0.label())]
    Missing(OreKind),
    /// Not owned by the ORE program.
    #[error("{} owner {owner} is not ORE", .kind.label())]
    WrongOwner {
        /// Which account.
        kind: OreKind,
        /// Actual owner.
        owner: Address,
    },
    /// Size differs from the pin.
    #[error("{} size {got} != pinned {expected}", .kind.label())]
    WrongSize {
        /// Which account.
        kind: OreKind,
        /// Pinned size.
        expected: usize,
        /// Observed size.
        got: usize,
    },
    /// Discriminator differs from the pin.
    #[error("{} discriminator {got} != pinned {expected}", .kind.label())]
    WrongDiscriminator {
        /// Which account.
        kind: OreKind,
        /// Pinned discriminator.
        expected: u8,
        /// Observed discriminator.
        got: u8,
    },
    /// A value failed a sanity pin (`docs/ORE.md` section 8, Level 1).
    #[error("{} failed sanity check: {reason}", .kind.label())]
    Insane {
        /// Which account.
        kind: OreKind,
        /// What was wrong.
        reason: &'static str,
    },
}

/// Owner + exact size + discriminator check shared by every decoder.
pub fn check_layout(kind: OreKind, owner: &Address, data: &[u8]) -> Result<(), LayoutError> {
    if owner != &ORE_PROGRAM_ID {
        return Err(LayoutError::WrongOwner { kind, owner: *owner });
    }
    if data.len() != kind.size() {
        return Err(LayoutError::WrongSize { kind, expected: kind.size(), got: data.len() });
    }
    let got = data.first().copied().unwrap_or(0);
    if got != kind.discriminator() {
        return Err(LayoutError::WrongDiscriminator { kind, expected: kind.discriminator(), got });
    }
    Ok(())
}

fn field_err(kind: OreKind) -> LayoutError {
    // Unreachable after `check_layout` (every offset is inside the pinned size), but the
    // decoders never index unchecked.
    LayoutError::WrongSize { kind, expected: kind.size(), got: 0 }
}

/// `Board` (`state/board.rs`): `round_id @8, start_slot @16, end_slot @24, production_cost_ema @32`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Board {
    /// Current round id.
    pub round_id: u64,
    /// First slot of the round (`deploy.rs:47-50` sets it on the first deploy).
    pub start_slot: u64,
    /// `u64::MAX` until the round's first deploy, then `start_slot + round_slots`.
    pub end_slot: u64,
    /// Lamports per whole ORE, 20-round EMA (`reset.rs:239-251`).
    pub production_cost_ema: u64,
}

impl Board {
    /// Decode with the layout pin and the Level-1 value sanity pins.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Board;
        check_layout(K, owner, data)?;
        let b = Board {
            round_id: read_u64(data, 8).ok_or_else(|| field_err(K))?,
            start_slot: read_u64(data, 16).ok_or_else(|| field_err(K))?,
            end_slot: read_u64(data, 24).ok_or_else(|| field_err(K))?,
            production_cost_ema: read_u64(data, 32).ok_or_else(|| field_err(K))?,
        };
        if b.end_slot != u64::MAX && b.end_slot <= b.start_slot {
            return Err(LayoutError::Insane { kind: K, reason: "end_slot <= start_slot" });
        }
        if b.production_cost_ema == 0 {
            return Err(LayoutError::Insane { kind: K, reason: "production_cost_ema == 0" });
        }
        Ok(b)
    }

    /// True once the round has had its first deploy.
    pub fn started(&self) -> bool {
        self.end_slot != u64::MAX
    }

    /// ORE's deploy window check (`deploy.rs:33`): `start_slot <= slot < end_slot`.
    pub fn accepts_deploy_at(&self, slot: u64) -> bool {
        slot >= self.start_slot && slot < self.end_slot
    }
}

/// `Treasury` (`state/treasury.rs`): `motherlode @8` (ORE base units).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Treasury {
    /// Motherlode pot in ORE base units (11 decimals).
    pub motherlode: u64,
}

impl Treasury {
    /// Decode with the layout pin.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Treasury;
        check_layout(K, owner, data)?;
        Ok(Treasury { motherlode: read_u64(data, 8).ok_or_else(|| field_err(K))? })
    }
}

/// `Round` (`state/round.rs`): the fields the crank needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Round {
    /// Round id.
    pub id: u64,
    /// SOL per square (`deployed[25] @16..216`).
    pub deployed: [u64; SQUARES],
    /// Unique miners per square (`count[25] @416..616`).
    pub count: [u64; SQUARES],
    /// Slot after which the account may be closed (`expires_at @648`).
    pub expires_at: u64,
    /// Unique miners in the round (`total_miners @912`).
    pub total_miners: u64,
}

impl Round {
    /// Decode with the layout pin.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Round;
        check_layout(K, owner, data)?;
        Ok(Round {
            id: read_u64(data, 8).ok_or_else(|| field_err(K))?,
            deployed: read_u64_array::<SQUARES>(data, 16).ok_or_else(|| field_err(K))?,
            count: read_u64_array::<SQUARES>(data, 416).ok_or_else(|| field_err(K))?,
            expires_at: read_u64(data, 648).ok_or_else(|| field_err(K))?,
            total_miners: read_u64(data, 912).ok_or_else(|| field_err(K))?,
        })
    }

    /// Sum of SOL on the board.
    pub fn total_deployed(&self) -> u64 {
        self.deployed.iter().fold(0u64, |a, v| a.saturating_add(*v))
    }
}

/// ORE `Config`: `intermission_slots @152`, `round_slots @160` (docs/ORE.md pins these).
///
/// The pinned source declares `entropy_var_address @168` and `entropy_program_id @200`, but
/// the live account (read 2026-09-29) holds `64` then zeros there, and `deploy` uses the
/// compile-time `VAR_ADDRESS` / `entropy_api::ID`, not these fields. So they are not read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OreConfig {
    /// Slots between `end_slot` and the earliest `reset`.
    pub intermission_slots: u64,
    /// Slots per round.
    pub round_slots: u64,
}

impl OreConfig {
    /// Decode with the layout pin.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Config;
        check_layout(K, owner, data)?;
        let c = OreConfig {
            intermission_slots: read_u64(data, 152).ok_or_else(|| field_err(K))?,
            round_slots: read_u64(data, 160).ok_or_else(|| field_err(K))?,
        };
        if c.round_slots == 0 {
            return Err(LayoutError::Insane { kind: K, reason: "round_slots == 0" });
        }
        Ok(c)
    }
}

/// `AutomationConditions` (`state/automation.rs`), 24 bytes at `@136`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AutomationConditions {
    /// Stored, never enforced by ORE (`docs/ORE.md` section 9).
    pub max_production_cost: u64,
    /// Whole ORE.
    pub min_motherlode: u16,
    /// Whole ORE.
    pub max_motherlode: u16,
    /// Random strategy only.
    pub split_tiles: u16,
    /// Random strategy only.
    pub solo_tiles: u16,
}

impl AutomationConditions {
    /// ORE `deploy.rs:79-84`: a deploy silently no-ops (`Ok(())`) when
    /// `motherlode > max * ONE_ORE || motherlode < min * ONE_ORE`.
    pub fn motherlode_allows(&self, motherlode: u64) -> bool {
        // u16::MAX * ONE_ORE = 6.55e15 < u64::MAX: cannot overflow.
        let max = u64::from(self.max_motherlode).saturating_mul(ONE_ORE);
        let min = u64::from(self.min_motherlode).saturating_mul(ONE_ORE);
        !(motherlode > max || motherlode < min)
    }
}

/// `Automation` (`state/automation.rs:9-43`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Automation {
    /// Per-square cap for Discretionary (`deploy.rs:201-207`).
    pub amount: u64,
    /// The wallet that owns it.
    pub authority: Address,
    /// Lamports left.
    pub balance: u64,
    /// Who may deploy.
    pub executor: Address,
    /// Fixed fee (Discretionary) charged once per round on the first deploy.
    pub fee: u64,
    /// Strategy enum as u64.
    pub strategy: u64,
    /// Reload winnings.
    pub reload: u64,
    /// Conditions.
    pub conditions: AutomationConditions,
}

impl Automation {
    /// Decode with the layout pin.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Automation;
        check_layout(K, owner, data)?;
        let e = || field_err(K);
        Ok(Automation {
            amount: read_u64(data, 8).ok_or_else(e)?,
            authority: read_address(data, 16).ok_or_else(e)?,
            balance: read_u64(data, 48).ok_or_else(e)?,
            executor: read_address(data, 56).ok_or_else(e)?,
            fee: read_u64(data, 88).ok_or_else(e)?,
            strategy: read_u64(data, 96).ok_or_else(e)?,
            reload: read_u64(data, 112).ok_or_else(e)?,
            conditions: AutomationConditions {
                max_production_cost: read_u64(data, 136).ok_or_else(e)?,
                min_motherlode: read_u16(data, 144).ok_or_else(e)?,
                max_motherlode: read_u16(data, 146).ok_or_else(e)?,
                split_tiles: read_u16(data, 148).ok_or_else(e)?,
                solo_tiles: read_u16(data, 150).ok_or_else(e)?,
            },
        })
    }
}

/// `Miner` (`state/miner.rs:8-66`): the fields the crank needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Miner {
    /// Owner wallet.
    pub authority: Address,
    /// Last round checkpointed.
    pub checkpoint_id: u64,
    /// Reserve for the checkpoint bot.
    pub checkpoint_fee: u64,
    /// SOL on each square in `round_id`.
    pub deployed: [u64; SQUARES],
    /// Round the miner last deployed in.
    pub round_id: u64,
    /// Unrefined ORE.
    pub rewards_ore: u64,
}

impl Miner {
    /// Decode with the layout pin.
    pub fn decode(owner: &Address, data: &[u8]) -> Result<Self, LayoutError> {
        const K: OreKind = OreKind::Miner;
        check_layout(K, owner, data)?;
        let e = || field_err(K);
        Ok(Miner {
            authority: read_address(data, 8).ok_or_else(e)?,
            checkpoint_id: read_u64(data, 48).ok_or_else(e)?,
            checkpoint_fee: read_u64(data, 56).ok_or_else(e)?,
            deployed: read_u64_array::<SQUARES>(data, 64).ok_or_else(e)?,
            round_id: read_u64(data, 664).ok_or_else(e)?,
            rewards_ore: read_u64(data, 704).ok_or_else(e)?,
        })
    }

    /// `deploy.rs:251-256` `assert!`s this before resetting the miner for a new round.
    pub fn deploy_would_pass_checkpoint_assert(&self, board_round_id: u64) -> bool {
        self.round_id == board_round_id || self.checkpoint_id == self.round_id
    }

    /// True if an ORE `checkpoint` for `self.round_id` would change something.
    pub fn needs_checkpoint(&self) -> bool {
        self.checkpoint_id != self.round_id
    }

    /// Squares the Miner holds **in `board_round_id`** (`deployed > 0`), which `dig` excludes
    /// from its choice; 0 when the Miner's `deployed` belongs to another round.
    pub fn held_mask(&self, board_round_id: u64) -> u32 {
        if self.round_id != board_round_id {
            return 0;
        }
        self.deployed.iter().enumerate().fold(0u32, |m, (i, v)| if *v > 0 { m | (1 << i) } else { m })
    }

    /// SOL the Miner already has on the board in `board_round_id` (0 for another round);
    /// `None` on overflow. Zero means `dig` would be the rig's first deploy this round, which
    /// is when ORE charges the Automation's fee.
    pub fn deployed_in(&self, board_round_id: u64) -> Option<u64> {
        if self.round_id != board_round_id {
            return Some(0);
        }
        self.deployed.iter().try_fold(0u64, |a, v| a.checked_add(*v))
    }
}

/// Parse the slot of the last upgrade from ORE's ProgramData header
/// (`u32 tag = 3 | u64 slot | Option<Pubkey>`), after checking the loader owns it.
pub fn programdata_slot(owner: &Address, data: &[u8]) -> Option<u64> {
    if owner != &BPF_UPGRADEABLE_LOADER_ID {
        return None;
    }
    let tag = data.get(0..4)?;
    if tag != [3, 0, 0, 0] {
        return None;
    }
    read_u64(data, 4)
}

/// `["automation", authority]` under ORE.
pub fn automation_pda(authority: &Address) -> Address {
    Address::find_program_address(&[AUTOMATION_SEED, authority.as_ref()], &ORE_PROGRAM_ID).0
}

/// `["miner", authority]` under ORE.
pub fn miner_pda(authority: &Address) -> Address {
    Address::find_program_address(&[MINER_SEED, authority.as_ref()], &ORE_PROGRAM_ID).0
}

/// `["round", round_id u64 LE]` under ORE.
pub fn round_pda(round_id: u64) -> Address {
    Address::find_program_address(&[ROUND_SEED, &round_id.to_le_bytes()], &ORE_PROGRAM_ID).0
}

/// ORE `checkpoint` (tag 2, empty struct), permissionless (`checkpoint.rs:15`) and idempotent
/// (`checkpoint.rs:31-33`). Accounts exactly as `sdk.rs:334-354`:
/// `signer(s,w), authority(w), automation(w), board(w), miner(w), round(w), treasury(w), system`.
/// `round_id` is the **miner's** `round_id` (the round being settled). If that round account
/// has already been closed, ORE only re-derives the PDA and marks it checkpointed.
pub fn checkpoint_ix(signer: &Address, authority: &Address, round_id: u64) -> Instruction {
    Instruction {
        program_id: ORE_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*signer, true),
            AccountMeta::new(*authority, false),
            AccountMeta::new(automation_pda(authority), false),
            AccountMeta::new(BOARD_ADDRESS, false),
            AccountMeta::new(miner_pda(authority), false),
            AccountMeta::new(round_pda(round_id), false),
            AccountMeta::new(TREASURY_ADDRESS, false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data: vec![IX_CHECKPOINT],
    }
}

/// ORE `distribution_mask` (`state/round.rs:125-164`), bit for bit: a keccak-seeded
/// Fisher-Yates shuffle of 0..25; the first 10 shuffled indices are the **solo** squares
/// (bit = 1). Split squares have bit = 0.
pub fn distribution_mask(round_id: u64) -> u32 {
    let mut randomness: [u8; 32] = Keccak256::digest(round_id.to_le_bytes()).into();
    let mut indices: [u8; SQUARES] = [0; SQUARES];
    for (i, v) in indices.iter_mut().enumerate() {
        // i < 25, fits in u8.
        *v = u8::try_from(i).unwrap_or(0);
    }
    let mut offset = 0usize;
    for i in (1..SQUARES).rev() {
        if offset + 2 > randomness.len() {
            randomness = Keccak256::digest(randomness).into();
            offset = 0;
        }
        let r = u16::from_le_bytes([randomness[offset], randomness[offset + 1]]);
        let j = usize::from(r) % (i + 1);
        indices.swap(i, j);
        offset += 2;
    }
    indices[..SOLO_SQUARES].iter().fold(0u32, |m, &idx| m | (1u32 << idx))
}

/// The tile choice `heads_down::dig` makes (INTERFACE §6.4, the program's
/// `logic::select_tiles`): the `split` least-crowded split squares and the `solo`
/// least-crowded solo squares by `round.deployed`, ties to the lowest index, never a square
/// in `held` (the Miner already holds it this round, so ORE would skip it). The crank predicts
/// it for logs and metrics only; the program computes it on-chain and the crank has no say.
pub fn select_tiles_excluding(round_id: u64, deployed: &[u64; SQUARES], held: u32, split: u8, solo: u8) -> u32 {
    let solo_mask = distribution_mask(round_id);
    let mut order: Vec<usize> = (0..SQUARES).collect();
    // Stable: equal amounts keep index order.
    order.sort_by_key(|&i| deployed[i]);
    let (mut want_split, mut want_solo) = (split, solo);
    let mut mask = 0u32;
    for i in order {
        let bit = 1u32 << i;
        if held & bit != 0 {
            continue;
        }
        if solo_mask & bit != 0 {
            if want_solo > 0 {
                mask |= bit;
                want_solo -= 1;
            }
        } else if want_split > 0 {
            mask |= bit;
            want_split -= 1;
        }
        if want_split == 0 && want_solo == 0 {
            break;
        }
    }
    mask
}

/// [`select_tiles_excluding`] for a Miner that holds nothing this round.
pub fn select_tiles(round_id: u64, deployed: &[u64; SQUARES], split: u8, solo: u8) -> u32 {
    select_tiles_excluding(round_id, deployed, 0, split, solo)
}

/// `k = popcount(mask)` of the squares `dig` will choose, without the Round: the choice
/// takes `min(split, free split squares) + min(solo, free solo squares)` whatever the
/// amounts on them, so the count only depends on the round's solo mask and `held`.
pub fn tiles_available(round_id: u64, held: u32, split: u8, solo: u8) -> u32 {
    let solo_mask = distribution_mask(round_id);
    let board = (1u32 << SQUARES) - 1;
    let free = board & !held;
    let free_solo = (free & solo_mask).count_ones();
    let free_split = (free & !solo_mask).count_ones();
    u32::from(split).min(free_split) + u32::from(solo).min(free_solo)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn steel(kind: OreKind) -> Vec<u8> {
        let mut d = vec![0u8; kind.size()];
        d[0] = kind.discriminator();
        d
    }

    #[test]
    fn distribution_mask_matches_ore_properties() {
        // ORE's own unit tests (state/round.rs:191-240): exactly 10 bits, only the low 25
        // bits, deterministic, and diverse across rounds.
        let mut seen = std::collections::HashSet::new();
        for id in 0..1000u64 {
            let m = distribution_mask(id);
            assert_eq!(m.count_ones(), 10, "round {id}");
            assert_eq!(m & !((1u32 << 25) - 1), 0);
            assert_eq!(m, distribution_mask(id));
            if id < 100 {
                seen.insert(m);
            }
        }
        assert!(seen.len() > 50);
    }

    #[test]
    fn select_tiles_prefers_least_crowded_and_lowest_index() {
        let rid = 422_601;
        let solo = distribution_mask(rid);
        let mut deployed = [100u64; SQUARES];
        // Make the highest-index split square the emptiest.
        let last_split = (0..SQUARES).rev().find(|i| solo & (1 << i) == 0).unwrap();
        deployed[last_split] = 1;
        let m = select_tiles(rid, &deployed, 2, 0);
        assert_eq!(m.count_ones(), 2);
        assert_ne!(m & (1 << last_split), 0, "least crowded split square chosen");
        assert_eq!(m & solo, 0, "no solo squares when solo = 0");
        let first_split = (0..SQUARES).find(|i| solo & (1 << i) == 0).unwrap();
        assert_ne!(m & (1 << first_split), 0, "tie broken by lowest index");
        // All 15 split + all 10 solo = the whole board; asking for more is clamped.
        assert_eq!(select_tiles(rid, &deployed, 15, 10), (1 << 25) - 1);
        assert_eq!(select_tiles(rid, &deployed, 255, 255), (1 << 25) - 1);
    }

    #[test]
    fn held_squares_are_excluded_and_k_is_the_popcount() {
        let rid = 422_700;
        let solo = distribution_mask(rid);
        assert_eq!(solo, 0x124f304, "golden vectors' solo mask for round 422700");
        let deployed = [100u64; SQUARES];
        let first_split = (0..SQUARES).find(|i| solo & (1 << i) == 0).unwrap();
        let held = 1u32 << first_split;
        let m = select_tiles_excluding(rid, &deployed, held, 2, 0);
        assert_eq!(m & held, 0, "a held square is never chosen");
        assert_eq!(m.count_ones(), 2);
        // Everything split but one square held: k is capped by what is free.
        let all_split = ((1u32 << SQUARES) - 1) & !solo;
        let held = all_split & !(1u32 << first_split);
        assert_eq!(tiles_available(rid, held, 15, 0), 1);
        assert_eq!(select_tiles_excluding(rid, &deployed, held, 15, 0).count_ones(), 1);
        for (h, sp, so) in [(0u32, 10u8, 0u8), (0, 15, 10), (0, 255, 255), (held, 3, 4), (solo, 4, 4), (0x1ff_ffff, 1, 1)] {
            assert_eq!(select_tiles_excluding(rid, &deployed, h, sp, so).count_ones(), tiles_available(rid, h, sp, so));
        }
        // The golden dig vectors: 10 split squares on the pinned round.
        let golden = [
            370_000_000u64, 381_000_000, 392_000_000, 378_000_000, 389_000_000, 375_000_000, 386_000_000, 372_000_000,
            383_000_000, 394_000_000, 380_000_000, 391_000_000, 377_000_000, 388_000_000, 374_000_000, 385_000_000,
            371_000_000, 382_000_000, 393_000_000, 379_000_000, 390_000_000, 376_000_000, 387_000_000, 373_000_000,
            384_000_000,
        ];
        assert_eq!(select_tiles(rid, &golden, 10, 0), 0x008b04ab, "RigDug.mask of dig_fresh_heartbeat");
        let mut m = Miner {
            authority: Address::default(),
            checkpoint_id: 0,
            checkpoint_fee: 0,
            deployed: [0; SQUARES],
            round_id: rid,
            rewards_ore: 0,
        };
        m.deployed[3] = 5;
        m.deployed[7] = 6;
        assert_eq!(m.held_mask(rid), (1 << 3) | (1 << 7));
        assert_eq!(m.held_mask(rid + 1), 0);
        assert_eq!(m.deployed_in(rid), Some(11));
        assert_eq!(m.deployed_in(rid + 1), Some(0));
        m.deployed[0] = u64::MAX;
        assert_eq!(m.deployed_in(rid), None);
    }

    #[test]
    fn layout_pins_reject_wrong_owner_size_discriminator() {
        let mut board = steel(OreKind::Board);
        board[8..16].copy_from_slice(&7u64.to_le_bytes());
        board[24..32].copy_from_slice(&u64::MAX.to_le_bytes());
        board[32..40].copy_from_slice(&5u64.to_le_bytes());
        let b = Board::decode(&ORE_PROGRAM_ID, &board).unwrap();
        assert_eq!(b.round_id, 7);
        assert!(!b.started());

        assert!(matches!(
            Board::decode(&SYSTEM_PROGRAM_ID, &board),
            Err(LayoutError::WrongOwner { .. })
        ));
        let mut longer = board.clone();
        longer.push(0);
        assert!(matches!(Board::decode(&ORE_PROGRAM_ID, &longer), Err(LayoutError::WrongSize { .. })));
        let mut bad = board.clone();
        bad[0] = 104;
        assert!(matches!(
            Board::decode(&ORE_PROGRAM_ID, &bad),
            Err(LayoutError::WrongDiscriminator { .. })
        ));
        let mut zero_ema = board.clone();
        zero_ema[32..40].copy_from_slice(&0u64.to_le_bytes());
        assert!(matches!(Board::decode(&ORE_PROGRAM_ID, &zero_ema), Err(LayoutError::Insane { .. })));
        let mut inverted = board;
        inverted[16..24].copy_from_slice(&10u64.to_le_bytes());
        inverted[24..32].copy_from_slice(&10u64.to_le_bytes());
        assert!(matches!(Board::decode(&ORE_PROGRAM_ID, &inverted), Err(LayoutError::Insane { .. })));
        // Truncated input never panics.
        assert!(Board::decode(&ORE_PROGRAM_ID, &[105]).is_err());
        assert!(Miner::decode(&ORE_PROGRAM_ID, &[]).is_err());
    }

    #[test]
    fn automation_and_miner_offsets() {
        let mut a = steel(OreKind::Automation);
        a[8..16].copy_from_slice(&1_000u64.to_le_bytes());
        a[16..48].copy_from_slice(&[7u8; 32]);
        a[48..56].copy_from_slice(&50_000u64.to_le_bytes());
        a[56..88].copy_from_slice(&[9u8; 32]);
        a[88..96].copy_from_slice(&5_000u64.to_le_bytes());
        a[96..104].copy_from_slice(&2u64.to_le_bytes());
        a[144..146].copy_from_slice(&3u16.to_le_bytes());
        a[146..148].copy_from_slice(&400u16.to_le_bytes());
        let d = Automation::decode(&ORE_PROGRAM_ID, &a).unwrap();
        assert_eq!(d.amount, 1_000);
        assert_eq!(d.authority, Address::new_from_array([7; 32]));
        assert_eq!(d.balance, 50_000);
        assert_eq!(d.executor, Address::new_from_array([9; 32]));
        assert_eq!(d.fee, 5_000);
        assert_eq!(d.strategy, STRATEGY_DISCRETIONARY);
        assert!(!d.conditions.motherlode_allows(2 * ONE_ORE));
        assert!(d.conditions.motherlode_allows(3 * ONE_ORE));
        assert!(d.conditions.motherlode_allows(400 * ONE_ORE));
        assert!(!d.conditions.motherlode_allows(400 * ONE_ORE + 1));

        let mut m = steel(OreKind::Miner);
        m[8..40].copy_from_slice(&[7u8; 32]);
        m[48..56].copy_from_slice(&41u64.to_le_bytes());
        m[664..672].copy_from_slice(&42u64.to_le_bytes());
        let d = Miner::decode(&ORE_PROGRAM_ID, &m).unwrap();
        assert_eq!(d.checkpoint_id, 41);
        assert_eq!(d.round_id, 42);
        assert!(d.needs_checkpoint());
        assert!(d.deploy_would_pass_checkpoint_assert(42));
        assert!(!d.deploy_would_pass_checkpoint_assert(43));
    }

    #[test]
    fn checkpoint_ix_matches_ore_sdk_layout() {
        let signer = Address::new_from_array([1; 32]);
        let auth = Address::new_from_array([2; 32]);
        let ix = checkpoint_ix(&signer, &auth, 5);
        assert_eq!(ix.program_id, ORE_PROGRAM_ID);
        assert_eq!(ix.data, vec![2]);
        assert_eq!(ix.accounts.len(), 8);
        assert!(ix.accounts[0].is_signer && ix.accounts[0].is_writable);
        assert_eq!(ix.accounts[1].pubkey, auth);
        assert_eq!(ix.accounts[2].pubkey, automation_pda(&auth));
        assert_eq!(ix.accounts[3].pubkey, BOARD_ADDRESS);
        assert_eq!(ix.accounts[4].pubkey, miner_pda(&auth));
        assert_eq!(ix.accounts[5].pubkey, round_pda(5));
        assert_eq!(ix.accounts[6].pubkey, TREASURY_ADDRESS);
        assert!(!ix.accounts[7].is_writable);
        assert!(ix.accounts[..7].iter().all(|m| m.is_writable));
    }

    #[test]
    fn programdata_header() {
        let mut d = vec![3u8, 0, 0, 0];
        d.extend_from_slice(&PINNED_PROGRAMDATA_SLOT.to_le_bytes());
        d.push(1);
        d.extend_from_slice(&[0u8; 32]);
        assert_eq!(programdata_slot(&BPF_UPGRADEABLE_LOADER_ID, &d), Some(PINNED_PROGRAMDATA_SLOT));
        assert_eq!(programdata_slot(&SYSTEM_PROGRAM_ID, &d), None);
        d[0] = 2;
        assert_eq!(programdata_slot(&BPF_UPGRADEABLE_LOADER_ID, &d), None);
    }
}
