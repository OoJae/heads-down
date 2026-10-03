//! INTERFACE v1.2 §11 (SKR), the part the crank touches: the StackTable / StackSeat /
//! FocusBond / GiftEscrow / BuryVault layouts, their PDAs and vault addresses, and the
//! permissionless instructions the crank sends (`stack_checkin` in verify and observe mode,
//! `settle_stack`, `forfeit_focus_bond`, `refund_gift`, and `init_bury_vault` with the Bury
//! lot's ATA). Every builder is checked byte for byte against
//! `programs/heads-down/vectors/instructions.json` in `tests/golden.rs`.
//!
//! It also mirrors, integer for integer, the two pure rules the program settles a table with
//! (`seat_finishes` and `stack_payouts`), so the crank can say what a settle will do before it
//! pays for one, and the rule `forfeit_focus_bond` / `release_focus_bond` resolve a bond with.
//!
//! SKR is collateral, bond and gift currency here. Nothing the crank sends mints SKR or pays
//! anything for holding it: a check-in only records that a phone's heartbeat for a round
//! landed in that round, a settle only applies the table's own split, a forfeit only moves a
//! broken bond to the Bury lot, and a refund only returns an expired gift to its sender.

use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

use crate::bytes::{read_address, read_i64, read_u32, read_u64, read_u8};
use crate::hd::{self, AccountError, DigBuildError, DigEntry, Rig, RigState, ShiftLog, INSTRUCTIONS_SYSVAR_ID};
use crate::ore;

/// SKR mint: classic SPL Token, 6 decimals, no freeze authority.
pub const SKR_MINT: Address = Address::from_str_const("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3");
/// ORE mint: classic SPL Token, 11 decimals.
pub const ORE_MINT: Address = Address::from_str_const("oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp");
/// Classic SPL Token, the only token program `heads_down` invokes.
pub const SPL_TOKEN_PROGRAM_ID: Address = Address::from_str_const("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
/// Associated Token Account program (vault addresses; the crank also invokes it to create the
/// Bury lot's ATA).
pub const ATA_PROGRAM_ID: Address = Address::from_str_const("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
/// One whole SKR in base units.
pub const ONE_SKR: u64 = 1_000_000;
/// SPL Token account size.
pub const TOKEN_ACCOUNT_LEN: usize = 165;

/// StackTable account tag.
pub const TAG_STACK_TABLE: u8 = 5;
/// StackSeat account tag.
pub const TAG_STACK_SEAT: u8 = 6;
/// FocusBond account tag.
pub const TAG_FOCUS_BOND: u8 = 7;
/// GiftEscrow account tag.
pub const TAG_GIFT_ESCROW: u8 = 8;
/// BuryVault account tag.
pub const TAG_BURY_VAULT: u8 = 9;
/// StackTable size.
pub const STACK_TABLE_LEN: usize = 208;
/// StackSeat size.
pub const STACK_SEAT_LEN: usize = 200;
/// FocusBond size.
pub const FOCUS_BOND_LEN: usize = 160;
/// GiftEscrow size.
pub const GIFT_ESCROW_LEN: usize = 128;
/// BuryVault size.
pub const BURY_VAULT_LEN: usize = 192;

/// Byte offset of `StackTable.status` (memcmp filter target).
pub const STACK_TABLE_STATUS_OFFSET: usize = 110;
/// Byte offset of `StackSeat.table` (memcmp filter target).
pub const STACK_SEAT_TABLE_OFFSET: usize = 8;
/// Byte offset of `StackSeat.outcome` (memcmp filter target).
pub const STACK_SEAT_OUTCOME_OFFSET: usize = 178;

/// `stack_checkin` instruction tag.
pub const IX_STACK_CHECKIN: u8 = 17;
/// `settle_stack` instruction tag.
pub const IX_SETTLE_STACK: u8 = 18;
/// `forfeit_focus_bond` instruction tag.
pub const IX_FORFEIT_FOCUS_BOND: u8 = 22;
/// `refund_gift` instruction tag.
pub const IX_REFUND_GIFT: u8 = 25;
/// `init_bury_vault` instruction tag.
pub const IX_INIT_BURY_VAULT: u8 = 26;

/// Accounts before the `(seat, rig)` pairs of `stack_checkin`.
pub const CHECKIN_FIXED_ACCOUNTS: usize = 3;
/// Accounts before the seats of `settle_stack`.
pub const SETTLE_FIXED_ACCOUNTS: usize = 6;
/// Seats per table and per `stack_checkin` at most.
pub const MAX_SEATS: usize = 8;
/// Finishers' share of the forfeits, in basis points (0 at bury-only tables).
pub const FINISHER_BPS: u16 = 8_000;
/// Basis-point denominator.
pub const BPS_DENOMINATOR: u16 = 10_000;

/// `StackTable.flags` bit 0: remote table (Seekers only, keyed by the SGT mint).
pub const FLAG_REMOTE: u8 = 1;
/// `StackTable.flags` bit 1: every forfeit goes to the Bury lot.
pub const FLAG_BURY_ONLY: u8 = 2;
/// `StackTable.flags` bit 2: an unexpired attestation is required to join.
pub const FLAG_ATTESTED_ONLY: u8 = 4;

/// `StackTable.status`.
pub mod status {
    /// Joins before `start_round`, check-ins inside the window, then settle.
    pub const OPEN: u8 = 0;
    /// Outcomes and payouts are final; seats claim.
    pub const SETTLED: u8 = 1;
    /// Never settled before the timeout: every seat claims its own bond back.
    pub const REFUNDING: u8 = 2;
}

/// `StackSeat.outcome`.
pub mod outcome {
    /// Not settled yet.
    pub const PENDING: u8 = 0;
    /// Held out.
    pub const FINISHED: u8 = 1;
    /// Broke, missed the end round, or more gaps than grace.
    pub const FORFEITED: u8 = 2;
}

/// A short name for a table's `flags` (logs and captions).
pub fn flags_name(flags: u8) -> &'static str {
    match (flags & FLAG_REMOTE != 0, flags & FLAG_BURY_ONLY != 0) {
        (false, false) if flags & FLAG_ATTESTED_ONLY != 0 => "in-person, attested-only",
        (false, false) => "in-person",
        (false, true) => "in-person, bury-only",
        (true, false) => "remote",
        (true, true) => "remote, bury-only",
    }
}

// ---------------------------------------------------------------------------------------
// PDAs and vaults.

/// `["stack", host, table_id u64 LE]`.
pub fn stack_table_pda(program_id: &Address, host: &Address, table_id: u64) -> (Address, u8) {
    Address::find_program_address(&[b"stack", host.as_ref(), &table_id.to_le_bytes()], program_id)
}

/// `["stackseat", table, key]`: `key` is the rig at in-person tables, the SGT mint at remote ones.
pub fn stack_seat_pda(program_id: &Address, table: &Address, key: &Address) -> (Address, u8) {
    Address::find_program_address(&[b"stackseat", table.as_ref(), key.as_ref()], program_id)
}

/// `["bond", rig, shift_id u64 LE]`.
pub fn focus_bond_pda(program_id: &Address, rig: &Address, shift_id: u64) -> (Address, u8) {
    Address::find_program_address(&[b"bond", rig.as_ref(), &shift_id.to_le_bytes()], program_id)
}

/// `["gift", sender, nonce u64 LE]`.
pub fn gift_pda(program_id: &Address, sender: &Address, nonce: u64) -> (Address, u8) {
    Address::find_program_address(&[b"gift", sender.as_ref(), &nonce.to_le_bytes()], program_id)
}

/// `["bury"]`: the singleton Bury auction.
pub fn bury_vault_pda(program_id: &Address) -> (Address, u8) {
    Address::find_program_address(&[b"bury"], program_id)
}

/// `ATA(owner, mint)` under classic SPL Token: `[owner, SPL Token, mint]` under the ATA program.
pub fn ata(owner: &Address, mint: &Address) -> Address {
    Address::find_program_address(&[owner.as_ref(), SPL_TOKEN_PROGRAM_ID.as_ref(), mint.as_ref()], &ATA_PROGRAM_ID).0
}

/// The balance of a classic SPL Token account (`None` unless it is owned by SPL Token, 165
/// bytes and initialized).
pub fn token_amount(owner: &Address, data: &[u8]) -> Option<u64> {
    if owner != &SPL_TOKEN_PROGRAM_ID || data.len() != TOKEN_ACCOUNT_LEN || read_u8(data, 108)? != 1 {
        return None;
    }
    read_u64(data, 64)
}

// ---------------------------------------------------------------------------------------
// Accounts.

/// `StackTable` (208 bytes, `["stack", host, table_id]`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct StackTable {
    pub bump: u8,
    pub host: Address,
    /// `ATA(table, SKR)`.
    pub vault: Address,
    pub table_id: u64,
    /// SKR base units per seat.
    pub bond: u64,
    /// First ORE round of the window.
    pub start_round: u64,
    /// Last ORE round of the window (inclusive).
    pub end_round: u64,
    /// Window rounds a seat may miss.
    pub grace_gaps: u32,
    pub flags: u8,
    pub max_seats: u8,
    /// See [`status`].
    pub status: u8,
    pub seat_count: u8,
    pub finishers: u8,
    pub claimed_count: u8,
    pub total_bonds: u64,
    pub finisher_bonds: u64,
    pub payouts_total: u64,
    pub bury_amount: u64,
    pub claimed_total: u64,
    /// After this, an unsettled table refunds every bond at the first claim.
    pub refund_after_ts: i64,
    pub opened_ts: i64,
    pub opened_round: u64,
}

impl StackTable {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        hd::check_header(program_id, owner, data, TAG_STACK_TABLE, STACK_TABLE_LEN)?;
        let e = || AccountError::WrongSize { expected: STACK_TABLE_LEN, got: data.len() };
        Ok(StackTable {
            bump: read_u8(data, 2).ok_or_else(e)?,
            host: read_address(data, 8).ok_or_else(e)?,
            vault: read_address(data, 40).ok_or_else(e)?,
            table_id: read_u64(data, 72).ok_or_else(e)?,
            bond: read_u64(data, 80).ok_or_else(e)?,
            start_round: read_u64(data, 88).ok_or_else(e)?,
            end_round: read_u64(data, 96).ok_or_else(e)?,
            grace_gaps: read_u32(data, 104).ok_or_else(e)?,
            flags: read_u8(data, 108).ok_or_else(e)?,
            max_seats: read_u8(data, 109).ok_or_else(e)?,
            status: read_u8(data, STACK_TABLE_STATUS_OFFSET).ok_or_else(e)?,
            seat_count: read_u8(data, 111).ok_or_else(e)?,
            finishers: read_u8(data, 112).ok_or_else(e)?,
            claimed_count: read_u8(data, 113).ok_or_else(e)?,
            total_bonds: read_u64(data, 120).ok_or_else(e)?,
            finisher_bonds: read_u64(data, 128).ok_or_else(e)?,
            payouts_total: read_u64(data, 136).ok_or_else(e)?,
            bury_amount: read_u64(data, 144).ok_or_else(e)?,
            claimed_total: read_u64(data, 152).ok_or_else(e)?,
            refund_after_ts: read_i64(data, 160).ok_or_else(e)?,
            opened_ts: read_i64(data, 168).ok_or_else(e)?,
            opened_round: read_u64(data, 176).ok_or_else(e)?,
        })
    }

    /// Serialize to the 208-byte layout (tests).
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; STACK_TABLE_LEN];
        d[0] = TAG_STACK_TABLE;
        d[1] = hd::ACCOUNT_VERSION;
        d[2] = self.bump;
        let mut put = |off: usize, b: &[u8]| d[off..off + b.len()].copy_from_slice(b);
        put(8, self.host.as_ref());
        put(40, self.vault.as_ref());
        put(72, &self.table_id.to_le_bytes());
        put(80, &self.bond.to_le_bytes());
        put(88, &self.start_round.to_le_bytes());
        put(96, &self.end_round.to_le_bytes());
        put(104, &self.grace_gaps.to_le_bytes());
        put(108, &[self.flags, self.max_seats, self.status, self.seat_count, self.finishers, self.claimed_count]);
        put(120, &self.total_bonds.to_le_bytes());
        put(128, &self.finisher_bonds.to_le_bytes());
        put(136, &self.payouts_total.to_le_bytes());
        put(144, &self.bury_amount.to_le_bytes());
        put(152, &self.claimed_total.to_le_bytes());
        put(160, &self.refund_after_ts.to_le_bytes());
        put(168, &self.opened_ts.to_le_bytes());
        put(176, &self.opened_round.to_le_bytes());
        d
    }

    /// Rounds in the window.
    pub fn window_len(&self) -> u64 {
        window_len(self.start_round, self.end_round)
    }

    /// Open, and `round` is inside `[start_round, end_round]`: `stack_checkin` is accepted.
    pub fn in_window(&self, round: u64) -> bool {
        self.status == status::OPEN && self.start_round <= round && round <= self.end_round
    }

    /// Open and past its window: `settle_stack` is accepted.
    pub fn settleable(&self, round: u64) -> bool {
        self.status == status::OPEN && round > self.end_round
    }

    /// Finishers' share of the forfeits at this table.
    pub fn finisher_bps(&self) -> u16 {
        if self.flags & FLAG_BURY_ONLY != 0 {
            0
        } else {
            FINISHER_BPS
        }
    }
}

/// `StackSeat` (200 bytes, `["stackseat", table, key]`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct StackSeat {
    pub bump: u8,
    pub table: Address,
    pub rig: Address,
    /// `rig.authority` at join: the only recipient of the payout and the seat rent.
    pub authority: Address,
    pub sgt_mint: Address,
    pub bond: u64,
    /// The rig's shift the seat is bound to; 0 until the first counted check-in.
    pub shift_id: u64,
    /// Window rounds counted.
    pub checked_rounds: u64,
    /// Last round counted (0 = none).
    pub last_round: u64,
    pub payout: u64,
    pub seat_index: u8,
    /// A check-in saw a BREAK / FREEZE in the bound shift.
    pub broken: bool,
    /// See [`outcome`].
    pub outcome: u8,
    pub sgt_verified: bool,
}

impl StackSeat {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        hd::check_header(program_id, owner, data, TAG_STACK_SEAT, STACK_SEAT_LEN)?;
        let e = || AccountError::WrongSize { expected: STACK_SEAT_LEN, got: data.len() };
        Ok(StackSeat {
            bump: read_u8(data, 2).ok_or_else(e)?,
            table: read_address(data, STACK_SEAT_TABLE_OFFSET).ok_or_else(e)?,
            rig: read_address(data, 40).ok_or_else(e)?,
            authority: read_address(data, 72).ok_or_else(e)?,
            sgt_mint: read_address(data, 104).ok_or_else(e)?,
            bond: read_u64(data, 136).ok_or_else(e)?,
            shift_id: read_u64(data, 144).ok_or_else(e)?,
            checked_rounds: read_u64(data, 152).ok_or_else(e)?,
            last_round: read_u64(data, 160).ok_or_else(e)?,
            payout: read_u64(data, 168).ok_or_else(e)?,
            seat_index: read_u8(data, 176).ok_or_else(e)?,
            broken: read_u8(data, 177).ok_or_else(e)? != 0,
            outcome: read_u8(data, STACK_SEAT_OUTCOME_OFFSET).ok_or_else(e)?,
            sgt_verified: read_u8(data, 179).ok_or_else(e)? != 0,
        })
    }

    /// Serialize to the 200-byte layout (tests).
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; STACK_SEAT_LEN];
        d[0] = TAG_STACK_SEAT;
        d[1] = hd::ACCOUNT_VERSION;
        d[2] = self.bump;
        let mut put = |off: usize, b: &[u8]| d[off..off + b.len()].copy_from_slice(b);
        put(8, self.table.as_ref());
        put(40, self.rig.as_ref());
        put(72, self.authority.as_ref());
        put(104, self.sgt_mint.as_ref());
        put(136, &self.bond.to_le_bytes());
        put(144, &self.shift_id.to_le_bytes());
        put(152, &self.checked_rounds.to_le_bytes());
        put(160, &self.last_round.to_le_bytes());
        put(168, &self.payout.to_le_bytes());
        put(176, &[self.seat_index, u8::from(self.broken), self.outcome, u8::from(self.sgt_verified)]);
        d
    }

    /// Window rounds this seat has not counted.
    pub fn gaps(&self, table: &StackTable) -> u64 {
        table.window_len().saturating_sub(self.checked_rounds)
    }

    /// The program's finish rule for this seat (INTERFACE §11.5).
    pub fn finishes(&self, table: &StackTable) -> bool {
        seat_finishes(self.broken, self.last_round, self.checked_rounds, table.start_round, table.end_round, table.grace_gaps)
    }
}

/// `FocusBond` (160 bytes, `["bond", rig, shift_id]`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct FocusBond {
    pub bump: u8,
    pub rig: Address,
    /// Release and both rents go only here.
    pub authority: Address,
    /// `ATA(bond, SKR)`.
    pub vault: Address,
    pub shift_id: u64,
    pub amount: u64,
    /// `rig.shift_start_round` at lock (== the bonded shift's `ShiftLog.start_round`).
    pub shift_start_round: u64,
    /// `rig.shift_start_ts` at lock (== the bonded shift's `ShiftLog.start_ts`).
    pub shift_start_ts: i64,
    pub locked_ts: i64,
}

impl FocusBond {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        hd::check_header(program_id, owner, data, TAG_FOCUS_BOND, FOCUS_BOND_LEN)?;
        let e = || AccountError::WrongSize { expected: FOCUS_BOND_LEN, got: data.len() };
        Ok(FocusBond {
            bump: read_u8(data, 2).ok_or_else(e)?,
            rig: read_address(data, 8).ok_or_else(e)?,
            authority: read_address(data, 40).ok_or_else(e)?,
            vault: read_address(data, 72).ok_or_else(e)?,
            shift_id: read_u64(data, 104).ok_or_else(e)?,
            amount: read_u64(data, 112).ok_or_else(e)?,
            shift_start_round: read_u64(data, 120).ok_or_else(e)?,
            shift_start_ts: read_i64(data, 128).ok_or_else(e)?,
            locked_ts: read_i64(data, 136).ok_or_else(e)?,
        })
    }

    /// Serialize to the 160-byte layout (tests).
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; FOCUS_BOND_LEN];
        d[0] = TAG_FOCUS_BOND;
        d[1] = hd::ACCOUNT_VERSION;
        d[2] = self.bump;
        let mut put = |off: usize, b: &[u8]| d[off..off + b.len()].copy_from_slice(b);
        put(8, self.rig.as_ref());
        put(40, self.authority.as_ref());
        put(72, self.vault.as_ref());
        put(104, &self.shift_id.to_le_bytes());
        put(112, &self.amount.to_le_bytes());
        put(120, &self.shift_start_round.to_le_bytes());
        put(128, &self.shift_start_ts.to_le_bytes());
        put(136, &self.locked_ts.to_le_bytes());
        d
    }
}

/// `GiftEscrow` (128 bytes, `["gift", sender, nonce]`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct GiftEscrow {
    pub bump: u8,
    /// The refund and the rent go only here.
    pub sender: Address,
    /// A wallet, or an SGT mint.
    pub recipient: Address,
    pub nonce: u64,
    pub lamports: u64,
    pub created_ts: i64,
    /// Claims before, refunds from.
    pub expiry_ts: i64,
    /// 0 wallet, 1 SGT mint.
    pub recipient_kind: u8,
}

impl GiftEscrow {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        hd::check_header(program_id, owner, data, TAG_GIFT_ESCROW, GIFT_ESCROW_LEN)?;
        let e = || AccountError::WrongSize { expected: GIFT_ESCROW_LEN, got: data.len() };
        Ok(GiftEscrow {
            bump: read_u8(data, 2).ok_or_else(e)?,
            sender: read_address(data, 8).ok_or_else(e)?,
            recipient: read_address(data, 40).ok_or_else(e)?,
            nonce: read_u64(data, 72).ok_or_else(e)?,
            lamports: read_u64(data, 80).ok_or_else(e)?,
            created_ts: read_i64(data, 88).ok_or_else(e)?,
            expiry_ts: read_i64(data, 96).ok_or_else(e)?,
            recipient_kind: read_u8(data, 104).ok_or_else(e)?,
        })
    }

    /// Serialize to the 128-byte layout (tests).
    pub fn encode(&self) -> Vec<u8> {
        let mut d = vec![0u8; GIFT_ESCROW_LEN];
        d[0] = TAG_GIFT_ESCROW;
        d[1] = hd::ACCOUNT_VERSION;
        d[2] = self.bump;
        let mut put = |off: usize, b: &[u8]| d[off..off + b.len()].copy_from_slice(b);
        put(8, self.sender.as_ref());
        put(40, self.recipient.as_ref());
        put(72, &self.nonce.to_le_bytes());
        put(80, &self.lamports.to_le_bytes());
        put(88, &self.created_ts.to_le_bytes());
        put(96, &self.expiry_ts.to_le_bytes());
        put(104, &[self.recipient_kind]);
        d
    }

    /// `refund_gift` is accepted from `expiry_ts` (`now >= expiry_ts`).
    pub fn refundable_at(&self, now_ts: i64) -> bool {
        now_ts >= self.expiry_ts
    }
}

/// `BuryVault` (192 bytes, `["bury"]`): the fields the crank reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(missing_docs)]
pub struct BuryVault {
    pub bump: u8,
    /// `ATA(BuryVault, SKR)`: the lot.
    pub skr_vault: Address,
    /// `ATA(BuryVault, ORE)`: the `bury` sender.
    pub ore_vault: Address,
    /// SKR for sale.
    pub lot_skr: u64,
    pub auction_start_slot: u64,
    pub start_price: u64,
    pub total_skr_in: u64,
    /// Deposits.
    pub lots: u64,
}

impl BuryVault {
    /// Decode after checking owner, size, tag and version.
    pub fn decode(program_id: &Address, owner: &Address, data: &[u8]) -> Result<Self, AccountError> {
        hd::check_header(program_id, owner, data, TAG_BURY_VAULT, BURY_VAULT_LEN)?;
        let e = || AccountError::WrongSize { expected: BURY_VAULT_LEN, got: data.len() };
        Ok(BuryVault {
            bump: read_u8(data, 2).ok_or_else(e)?,
            skr_vault: read_address(data, 8).ok_or_else(e)?,
            ore_vault: read_address(data, 40).ok_or_else(e)?,
            lot_skr: read_u64(data, 72).ok_or_else(e)?,
            auction_start_slot: read_u64(data, 80).ok_or_else(e)?,
            start_price: read_u64(data, 88).ok_or_else(e)?,
            total_skr_in: read_u64(data, 120).ok_or_else(e)?,
            lots: read_u64(data, 160).ok_or_else(e)?,
        })
    }
}

// ---------------------------------------------------------------------------------------
// Instructions.

/// `stack_checkin` (tag 17): `0 [] ORE Board | 1 [] instructions sysvar | 2 [] StackTable`,
/// then `[w] seat_i, [w] rig_i` per entry; data `17 | n (1..=8) | n × entry` (the 20-byte
/// `dig` entry). `hb_ix = 0xFF` is observe mode: count the lease a `dig` or
/// `record_heartbeats` already applied in this round. Any other `hb_ix` is verify mode: the
/// entry's HEARTBEAT is verified and applied here, exactly as `record_heartbeats` does.
///
/// A seat or a rig twice fails the whole transaction on-chain (`DuplicateRig`), so it is
/// refused here.
pub fn stack_checkin_ix(
    program_id: &Address,
    table: &Address,
    seats: &[(Address, Address, DigEntry)],
) -> Result<Instruction, DigBuildError> {
    if seats.is_empty() {
        return Err(DigBuildError::Empty);
    }
    if seats.len() > MAX_SEATS {
        return Err(DigBuildError::TooMany(seats.len()));
    }
    let n = u8::try_from(seats.len()).map_err(|_| DigBuildError::TooMany(seats.len()))?;
    let mut seen = std::collections::HashSet::with_capacity(2 * seats.len());
    for (seat, rig, _) in seats {
        if !seen.insert(*seat) {
            return Err(DigBuildError::DuplicateRig(*seat));
        }
        if !seen.insert(*rig) {
            return Err(DigBuildError::DuplicateRig(*rig));
        }
    }
    let mut accounts = Vec::with_capacity(CHECKIN_FIXED_ACCOUNTS + 2 * seats.len());
    accounts.push(AccountMeta::new_readonly(ore::BOARD_ADDRESS, false));
    accounts.push(AccountMeta::new_readonly(INSTRUCTIONS_SYSVAR_ID, false));
    accounts.push(AccountMeta::new_readonly(*table, false));
    let mut data = Vec::with_capacity(2 + hd::DIG_ENTRY_LEN * seats.len());
    data.push(IX_STACK_CHECKIN);
    data.push(n);
    for (seat, rig, e) in seats {
        accounts.push(AccountMeta::new(*seat, false));
        accounts.push(AccountMeta::new(*rig, false));
        data.extend_from_slice(&e.encode());
    }
    Ok(Instruction { program_id: *program_id, accounts, data })
}

/// `settle_stack` (tag 18): `0 [w] StackTable | 1 [] ORE Board | 2 [w] table SKR vault |
/// 3 [w] BuryVault | 4 [w] BuryVault SKR ATA | 5 [] SPL Token`, then `[w]` every StackSeat of
/// the table, each once, in any order. Permissionless once `Board.round_id > end_round`.
/// `vault` is the address stored in the table (`ATA(table, SKR)`).
pub fn settle_stack_ix(program_id: &Address, table: &Address, vault: &Address, seats: &[Address]) -> Instruction {
    let bury = bury_vault_pda(program_id).0;
    let mut accounts = Vec::with_capacity(SETTLE_FIXED_ACCOUNTS + seats.len());
    accounts.extend([
        AccountMeta::new(*table, false),
        AccountMeta::new_readonly(ore::BOARD_ADDRESS, false),
        AccountMeta::new(*vault, false),
        AccountMeta::new(bury, false),
        AccountMeta::new(ata(&bury, &SKR_MINT), false),
        AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
    ]);
    accounts.extend(seats.iter().map(|s| AccountMeta::new(*s, false)));
    Instruction { program_id: *program_id, accounts, data: vec![IX_SETTLE_STACK] }
}

/// `forfeit_focus_bond` (tag 22): `0 [w] FocusBond | 1 [] ShiftLog ["shift", bond.rig,
/// bond.shift_id] | 2 [] bond.rig | 3 [w] bond SKR vault | 4 [w] BuryVault | 5 [w] BuryVault
/// SKR ATA | 6 [w] bond authority (receives both rents) | 7 [] SPL Token`. Permissionless: the
/// SKR can only go to the Bury lot and the rents only to the stored owner.
pub fn forfeit_focus_bond_ix(program_id: &Address, bond_address: &Address, bond: &FocusBond) -> Instruction {
    let bury = bury_vault_pda(program_id).0;
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*bond_address, false),
            AccountMeta::new_readonly(hd::shift_log_pda(program_id, &bond.rig, bond.shift_id).0, false),
            AccountMeta::new_readonly(bond.rig, false),
            AccountMeta::new(bond.vault, false),
            AccountMeta::new(bury, false),
            AccountMeta::new(ata(&bury, &SKR_MINT), false),
            AccountMeta::new(bond.authority, false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
        ],
        data: vec![IX_FORFEIT_FOCUS_BOND],
    }
}

/// `refund_gift` (tag 25): `0 [w] GiftEscrow | 1 [w] sender (= gift.sender)`. Permissionless
/// from `expiry_ts`: every lamport goes to the stored sender.
pub fn refund_gift_ix(program_id: &Address, gift: &Address, sender: &Address) -> Instruction {
    Instruction {
        program_id: *program_id,
        accounts: vec![AccountMeta::new(*gift, false), AccountMeta::new(*sender, false)],
        data: vec![IX_REFUND_GIFT],
    }
}

/// `init_bury_vault` (tag 26): `0 [s,w] payer | 1 [w] BuryVault ["bury"] | 2 [] System`.
/// Init-only, no admin, no parameter.
pub fn init_bury_vault_ix(program_id: &Address, payer: &Address) -> Instruction {
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*payer, true),
            AccountMeta::new(bury_vault_pda(program_id).0, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data: vec![IX_INIT_BURY_VAULT],
    }
}

/// Associated Token Account `CreateIdempotent` (tag 1) for `ATA(owner, mint)` under classic
/// SPL Token: `payer (s,w), ata (w), owner, mint, System, SPL Token`.
pub fn create_ata_idempotent_ix(payer: &Address, owner: &Address, mint: &Address) -> Instruction {
    Instruction {
        program_id: ATA_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*payer, true),
            AccountMeta::new(ata(owner, mint), false),
            AccountMeta::new_readonly(*owner, false),
            AccountMeta::new_readonly(*mint, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
        ],
        data: vec![1],
    }
}

// ---------------------------------------------------------------------------------------
// The rules the program settles with, mirrored integer for integer (INTERFACE §11.5, §11.6).

/// Rounds in `[start, end]`.
pub fn window_len(start: u64, end: u64) -> u64 {
    end.saturating_sub(start).saturating_add(1)
}

/// The finish rule: not broken, a check-in counted `end` itself, and at most `grace` window
/// rounds missed (the program's `skr::seat_finishes`).
pub fn seat_finishes(broken: bool, last_round: u64, checked_rounds: u64, start: u64, end: u64, grace: u32) -> bool {
    let gaps = window_len(start, end).saturating_sub(checked_rounds);
    !broken && last_round == end && gaps <= u64::from(grace)
}

/// What a settle does.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Settlement {
    /// `B`: every seat's bond.
    pub total_bonds: u64,
    /// `W`: the finishers' bonds.
    pub finisher_bonds: u64,
    /// Seats that finish.
    pub finishers: u8,
    /// Sum of the payouts.
    pub payouts_total: u64,
    /// `B − Σ payouts`: the SKR that moves to the Bury lot.
    pub bury: u64,
    /// Each seat's payout, in the order given.
    pub payouts: Vec<u64>,
}

/// The program's `skr::stack_payouts`: with `F = B − W`, finisher `i` gets
/// `bond_i + floor(F · bps · bond_i / (10⁴ · W))` (u128), everyone else 0, and the Bury lot
/// the rest. `None` on a shape error or overflow (the program fails the settle then).
pub fn stack_payouts(bonds: &[u64], finished: &[bool], finisher_bps: u16) -> Option<Settlement> {
    if bonds.len() != finished.len() || bonds.len() > MAX_SEATS || finisher_bps > BPS_DENOMINATOR {
        return None;
    }
    let (mut total, mut winners, mut k) = (0u64, 0u64, 0u8);
    for (b, f) in bonds.iter().zip(finished) {
        total = total.checked_add(*b)?;
        if *f {
            winners = winners.checked_add(*b)?;
            k = k.checked_add(1)?;
        }
    }
    let forfeits = total.checked_sub(winners)?;
    let mut payouts = Vec::with_capacity(bonds.len());
    let mut paid = 0u64;
    for (b, f) in bonds.iter().zip(finished) {
        let o = if *f && winners > 0 {
            let num = u128::from(forfeits).checked_mul(u128::from(finisher_bps))?.checked_mul(u128::from(*b))?;
            let den = u128::from(BPS_DENOMINATOR).checked_mul(u128::from(winners))?;
            let share = u64::try_from(num.checked_div(den)?).ok()?;
            b.checked_add(share)?
        } else {
            0
        };
        paid = paid.checked_add(o)?;
        payouts.push(o);
    }
    Some(Settlement {
        total_bonds: total,
        finisher_bonds: winners,
        finishers: k,
        payouts_total: paid,
        bury: total.checked_sub(paid)?,
        payouts,
    })
}

/// What `settle_stack` will do to `table` given every one of its seats (`None` if the seats
/// do not add up: not exactly `seat_count` seats of this table, or bonds that differ from
/// `total_bonds`, which the program refuses with `StackSeatMismatch`).
pub fn preview_settle(table_address: &Address, table: &StackTable, seats: &[StackSeat]) -> Option<Settlement> {
    if seats.len() != usize::from(table.seat_count) || seats.iter().any(|s| s.table != *table_address) {
        return None;
    }
    let bonds: Vec<u64> = seats.iter().map(|s| s.bond).collect();
    let finished: Vec<bool> = seats.iter().map(|s| s.finishes(table)).collect();
    let st = stack_payouts(&bonds, &finished, table.finisher_bps())?;
    (st.total_bonds == table.total_bonds).then_some(st)
}

/// How a Focus Bond resolves (INTERFACE §11.6).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BondResolution {
    /// The bonded shift sealed `completed`: `release_focus_bond` (the SKR returns to its owner).
    Release,
    /// `forfeit_focus_bond` is accepted, and `FocusBondForfeited.reason` will be this: the
    /// ShiftLog's reason (1..=8), or 255 when the bonded shift can never be sealed.
    Forfeit(u8),
    /// The bonded shift is still open and unsealed: neither instruction is accepted yet.
    NotYet,
}

/// `log` is the bonded shift's ShiftLog (same rig, id, start round and start time).
fn same_shift(log: &ShiftLog, bond: &FocusBond) -> bool {
    log.rig == bond.rig
        && log.shift_id == bond.shift_id
        && log.start_round == bond.shift_start_round
        && log.start_ts == bond.shift_start_ts
}

/// The program's rule for `bond`: `log` is the account at `["shift", bond.rig, bond.shift_id]`
/// if it is a ShiftLog, `rig` the account at `bond.rig` if it is a Rig.
pub fn resolve_bond(bond: &FocusBond, log: Option<&ShiftLog>, rig: Option<&Rig>) -> BondResolution {
    match log {
        Some(l) if same_shift(l, bond) => {
            if l.break_reason == hd::reason::COMPLETED {
                BondResolution::Release
            } else {
                BondResolution::Forfeit(l.break_reason)
            }
        }
        // The slot was free at lock, so another shift's log there means the rig was closed and
        // re-registered: the bonded shift can never be sealed.
        Some(_) => BondResolution::Forfeit(hd::BOND_ABANDONED),
        None => {
            let alive = rig.is_some_and(|g| {
                g.shift_id == bond.shift_id
                    && g.shift_start_round == bond.shift_start_round
                    && g.shift_start_ts == bond.shift_start_ts
            });
            if alive {
                BondResolution::NotYet
            } else {
                BondResolution::Forfeit(hd::BOND_ABANDONED)
            }
        }
    }
}

/// A shift a check-in may bind to and count: open, no BREAK / FREEZE recorded, Armed or Down.
pub fn shift_is_clean(rig: &Rig) -> bool {
    rig.shift_open && rig.break_reason == 0 && matches!(rig.state, RigState::Armed | RigState::Down)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table() -> StackTable {
        StackTable {
            bump: 254,
            host: Address::new_from_array([1; 32]),
            vault: Address::new_from_array([2; 32]),
            table_id: 3,
            bond: 200 * ONE_SKR,
            start_round: 100,
            end_round: 109,
            grace_gaps: 2,
            flags: 0,
            max_seats: 4,
            status: status::OPEN,
            seat_count: 3,
            finishers: 0,
            claimed_count: 0,
            total_bonds: 600 * ONE_SKR,
            finisher_bonds: 0,
            payouts_total: 0,
            bury_amount: 0,
            claimed_total: 0,
            refund_after_ts: 1_790_000_000,
            opened_ts: 1_789_000_000,
            opened_round: 90,
        }
    }

    fn seat(i: u8, checked: u64, last: u64, broken: bool) -> StackSeat {
        StackSeat {
            bump: 255,
            table: Address::new_from_array([9; 32]),
            rig: Address::new_from_array([10 + i; 32]),
            authority: Address::new_from_array([20 + i; 32]),
            sgt_mint: Address::default(),
            bond: 200 * ONE_SKR,
            shift_id: 1,
            checked_rounds: checked,
            last_round: last,
            payout: 0,
            seat_index: i,
            broken,
            outcome: outcome::PENDING,
            sgt_verified: false,
        }
    }

    #[test]
    fn accounts_roundtrip_and_check_their_header() {
        let t = table();
        let d = t.encode();
        assert_eq!(d.len(), 208);
        assert_eq!((d[0], d[1], d[108], d[109], d[110], d[111]), (5, 1, 0, 4, 0, 3));
        assert_eq!(StackTable::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &d).unwrap(), t);
        assert!(StackTable::decode(&hd::PROGRAM_ID, &ore::SYSTEM_PROGRAM_ID, &d).is_err(), "owner");
        assert!(StackTable::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &d[..207]).is_err(), "size");
        let s = seat(1, 4, 104, true);
        let d = s.encode();
        assert_eq!(d.len(), 200);
        assert_eq!((d[0], d[176], d[177], d[178]), (6, 1, 1, 0));
        assert_eq!(StackSeat::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &d).unwrap(), s);
        // A seat is not a table, and the other way around.
        assert!(StackTable::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &d).is_err());
        assert!(StackSeat::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &t.encode()).is_err());
        let b = FocusBond {
            bump: 250,
            rig: Address::new_from_array([3; 32]),
            authority: Address::new_from_array([4; 32]),
            vault: Address::new_from_array([5; 32]),
            shift_id: 6,
            amount: 7,
            shift_start_round: 8,
            shift_start_ts: -9,
            locked_ts: 10,
        };
        assert_eq!(b.encode().len(), 160);
        assert_eq!(FocusBond::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &b.encode()).unwrap(), b);
        let g = GiftEscrow {
            bump: 251,
            sender: Address::new_from_array([3; 32]),
            recipient: Address::new_from_array([4; 32]),
            nonce: 5,
            lamports: 6,
            created_ts: 7,
            expiry_ts: 8,
            recipient_kind: 1,
        };
        assert_eq!(g.encode().len(), 128);
        assert_eq!(GiftEscrow::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &g.encode()).unwrap(), g);
        assert!(!g.refundable_at(7) && g.refundable_at(8) && g.refundable_at(9), "refunds from expiry_ts, inclusive");
        assert!(BuryVault::decode(&hd::PROGRAM_ID, &hd::PROGRAM_ID, &[0u8; 192]).is_err(), "tag 0");
    }

    #[test]
    fn pinned_addresses() {
        assert_eq!(
            bury_vault_pda(&hd::PROGRAM_ID),
            ("6i46qfoKvAQssmf9A6yJ8rihGfP5XvUQfgrHsSzEm9ZS".parse().unwrap(), 255),
            "INTERFACE §11.2"
        );
        // The golden vectors' Bury lot: ATA(BuryVault, SKR).
        assert_eq!(ata(&bury_vault_pda(&hd::PROGRAM_ID).0, &SKR_MINT).to_string(), "2v3JD4WP7o5x2sUSyB59beLxu7S3ovSXt5mbf8v7rHnX");
    }

    #[test]
    fn the_finish_rule_matches_the_program() {
        // programs/heads-down/program/src/skr.rs the_finish_rule: window 100..=109, grace 2.
        assert!(seat_finishes(false, 109, 10, 100, 109, 2));
        assert!(seat_finishes(false, 109, 8, 100, 109, 2));
        assert!(!seat_finishes(false, 109, 7, 100, 109, 2));
        assert!(!seat_finishes(false, 108, 10, 100, 109, 2));
        assert!(!seat_finishes(true, 109, 10, 100, 109, 2));
        assert!(!seat_finishes(false, 0, 0, 100, 109, 9));
        assert_eq!(window_len(100, 109), 10);
        assert_eq!(window_len(5, 5), 1);
        let t = table();
        assert_eq!(seat(0, 8, 109, false).gaps(&t), 2);
        assert!(seat(0, 8, 109, false).finishes(&t));
        assert!(!seat(0, 8, 109, true).finishes(&t));
        assert!(t.in_window(100) && t.in_window(109) && !t.in_window(99) && !t.in_window(110));
        assert!(t.settleable(110) && !t.settleable(109));
        let settled = StackTable { status: status::SETTLED, ..t };
        assert!(!settled.in_window(105) && !settled.settleable(110));
    }

    #[test]
    fn payouts_match_the_program() {
        // programs/heads-down/program/src/skr.rs forfeits_split_80_20_with_dust_to_bury
        let s = stack_payouts(&[100; 4], &[true, true, false, true], FINISHER_BPS).unwrap();
        assert_eq!(s.payouts, vec![126, 126, 0, 126]);
        assert_eq!((s.bury, s.payouts_total, s.finishers, s.finisher_bonds), (22, 378, 3, 300));
        let bond = 200 * ONE_SKR;
        let s = stack_payouts(&[bond; 4], &[false, true, false, false], FINISHER_BPS).unwrap();
        assert_eq!(s.payouts[1], bond + 480 * ONE_SKR);
        assert_eq!(s.bury, 120 * ONE_SKR);
        // Everyone finishes; nobody finishes; bury-only.
        let s = stack_payouts(&[100, 100, 100], &[true; 3], FINISHER_BPS).unwrap();
        assert_eq!((s.payouts.clone(), s.bury), (vec![100, 100, 100], 0));
        let s = stack_payouts(&[100, 100], &[false; 2], FINISHER_BPS).unwrap();
        assert_eq!((s.payouts.clone(), s.bury, s.finishers), (vec![0, 0], 200, 0));
        let s = stack_payouts(&[50, 50, 50], &[true, false, true], 0).unwrap();
        assert_eq!((s.payouts.clone(), s.bury), (vec![50, 0, 50], 50));
        // Shape errors and overflow are refusals, never panics.
        assert_eq!(stack_payouts(&[1, 2], &[true], FINISHER_BPS), None);
        assert_eq!(stack_payouts(&[1, 2], &[true, true], 10_001), None);
        assert_eq!(stack_payouts(&[u64::MAX, 1], &[true, false], FINISHER_BPS), None);
        assert_eq!(stack_payouts(&[1; 9], &[true; 9], FINISHER_BPS), None);
        // Conservation for every finisher subset of 1..=8 seats with uneven bonds.
        let mut seed = 0x1234_5678_9abc_def0u64;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for n in 1..=8usize {
            for mask in 0..(1u32 << n) {
                let bonds: Vec<u64> = (0..n).map(|_| 1 + next() % 5_000_000_000).collect();
                let finished: Vec<bool> = (0..n).map(|i| mask & (1 << i) != 0).collect();
                for bps in [0u16, 8_000, 10_000, 1] {
                    let s = stack_payouts(&bonds, &finished, bps).unwrap();
                    assert_eq!(s.payouts.iter().sum::<u64>() + s.bury, bonds.iter().sum::<u64>());
                    assert_eq!(s.payouts_total, s.payouts.iter().sum::<u64>());
                }
            }
        }
    }

    #[test]
    fn settle_preview_needs_every_seat() {
        let t_addr = Address::new_from_array([9; 32]);
        let t = table();
        // The golden settle: two finish, one broke. 200 + 80 SKR each, 40 SKR to the Bury lot.
        let seats = [seat(0, 10, 109, false), seat(1, 10, 109, false), seat(2, 5, 104, true)];
        let s = preview_settle(&t_addr, &t, &seats).unwrap();
        assert_eq!(s.payouts, vec![280 * ONE_SKR, 280 * ONE_SKR, 0]);
        assert_eq!((s.bury, s.finishers, s.total_bonds), (40 * ONE_SKR, 2, 600 * ONE_SKR));
        assert_eq!(preview_settle(&t_addr, &t, &seats[..2]), None, "a seat is missing");
        let mut other = seats;
        other[1].table = Address::new_from_array([8; 32]);
        assert_eq!(preview_settle(&t_addr, &t, &other), None, "a seat of another table");
        let bury_only = StackTable { flags: FLAG_BURY_ONLY, ..t };
        let s = preview_settle(&t_addr, &bury_only, &seats).unwrap();
        assert_eq!((s.payouts.clone(), s.bury), (vec![200 * ONE_SKR, 200 * ONE_SKR, 0], 200 * ONE_SKR));
        assert_eq!(flags_name(FLAG_BURY_ONLY), "in-person, bury-only");
        assert_eq!(flags_name(FLAG_REMOTE | FLAG_ATTESTED_ONLY), "remote");
        assert_eq!(flags_name(FLAG_ATTESTED_ONLY), "in-person, attested-only");
        assert_eq!(flags_name(0), "in-person");
    }

    #[test]
    fn bonds_resolve_like_the_program() {
        let bond = FocusBond {
            bump: 250,
            rig: Address::new_from_array([3; 32]),
            authority: Address::new_from_array([4; 32]),
            vault: Address::new_from_array([5; 32]),
            shift_id: 2,
            amount: 300 * ONE_SKR,
            shift_start_round: 500,
            shift_start_ts: 1_790_000_000,
            locked_ts: 1_790_000_100,
        };
        let log = |reason: u8| ShiftLog {
            rig: bond.rig,
            shift_id: 2,
            start_round: 500,
            end_round: 600,
            dark_rounds: 90,
            rounds_dug: 0,
            lamports_deployed: 0,
            break_reason: reason,
            mode: 2,
            start_ts: 1_790_000_000,
            end_ts: 1_790_030_000,
        };
        let rig = Rig { shift_id: 2, shift_start_round: 500, shift_start_ts: 1_790_000_000, shift_open: true, ..Rig::default() };
        assert_eq!(resolve_bond(&bond, Some(&log(0)), Some(&rig)), BondResolution::Release);
        for r in 1..=8u8 {
            assert_eq!(resolve_bond(&bond, Some(&log(r)), Some(&rig)), BondResolution::Forfeit(r));
        }
        // Not sealed yet, and the rig still has that shift: neither.
        assert_eq!(resolve_bond(&bond, None, Some(&rig)), BondResolution::NotYet);
        // The rig was closed while the shift was open.
        assert_eq!(resolve_bond(&bond, None, None), BondResolution::Forfeit(255));
        // Closed and re-registered: the same shift id, another start.
        let reborn = Rig { shift_start_round: 900, ..rig.clone() };
        assert_eq!(resolve_bond(&bond, None, Some(&reborn)), BondResolution::Forfeit(255));
        let later = Rig { shift_id: 3, ..rig.clone() };
        assert_eq!(resolve_bond(&bond, None, Some(&later)), BondResolution::Forfeit(255));
        // The slot holds a later incarnation's log.
        let other = ShiftLog { start_ts: 1_790_500_000, ..log(0) };
        assert_eq!(resolve_bond(&bond, Some(&other), Some(&rig)), BondResolution::Forfeit(255));
    }

    #[test]
    fn checkin_builder_orders_accounts_and_refuses_duplicates() {
        let table = Address::new_from_array([7; 32]);
        let (s1, r1) = (Address::new_from_array([1; 32]), Address::new_from_array([2; 32]));
        let (s2, r2) = (Address::new_from_array([3; 32]), Address::new_from_array([4; 32]));
        let fresh = DigEntry { hb_ix: 2, hb_sig_index: 0, counter: 9, round_id: 100, lease_rounds: 1 };
        let ix = stack_checkin_ix(&hd::PROGRAM_ID, &table, &[(s1, r1, fresh), (s2, r2, DigEntry::reuse_lease())]).unwrap();
        assert_eq!(ix.data.len(), 2 + 40);
        assert_eq!(&ix.data[..3], &[17, 2, 2]);
        assert_eq!(ix.data[22], 0xFF, "the second entry observes");
        let metas: Vec<(Address, bool, bool)> = ix.accounts.iter().map(|m| (m.pubkey, m.is_signer, m.is_writable)).collect();
        assert_eq!(
            metas,
            vec![
                (ore::BOARD_ADDRESS, false, false),
                (INSTRUCTIONS_SYSVAR_ID, false, false),
                (table, false, false),
                (s1, false, true),
                (r1, false, true),
                (s2, false, true),
                (r2, false, true),
            ]
        );
        assert_eq!(stack_checkin_ix(&hd::PROGRAM_ID, &table, &[]), Err(DigBuildError::Empty));
        let dup_rig = [(s1, r1, fresh), (s2, r1, fresh)];
        assert_eq!(stack_checkin_ix(&hd::PROGRAM_ID, &table, &dup_rig), Err(DigBuildError::DuplicateRig(r1)));
        let dup_seat = [(s1, r1, fresh), (s1, r2, fresh)];
        assert_eq!(stack_checkin_ix(&hd::PROGRAM_ID, &table, &dup_seat), Err(DigBuildError::DuplicateRig(s1)));
        let nine: Vec<_> = (0..9u8).map(|i| (Address::new_from_array([50 + i; 32]), Address::new_from_array([70 + i; 32]), fresh)).collect();
        assert_eq!(stack_checkin_ix(&hd::PROGRAM_ID, &table, &nine), Err(DigBuildError::TooMany(9)));
    }

    #[test]
    fn token_accounts_are_read_strictly() {
        let mut d = vec![0u8; 165];
        d[64..72].copy_from_slice(&5u64.to_le_bytes());
        d[108] = 1;
        assert_eq!(token_amount(&SPL_TOKEN_PROGRAM_ID, &d), Some(5));
        assert_eq!(token_amount(&ore::SYSTEM_PROGRAM_ID, &d), None, "not SPL Token");
        assert_eq!(token_amount(&SPL_TOKEN_PROGRAM_ID, &d[..164]), None);
        d[108] = 0;
        assert_eq!(token_amount(&SPL_TOKEN_PROGRAM_ID, &d), None, "uninitialized");
    }
}
